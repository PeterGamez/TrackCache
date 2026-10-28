package com.github.petergamez.trackcache.redis;

import com.github.petergamez.trackcache.config.TrackCacheConfig;
import com.github.petergamez.trackcache.util.TrackCacheStats;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import io.lettuce.core.api.sync.RedisCommands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Manages the Lettuce Redis connection lifecycle, circuit breaker health state,
 * and non-blocking background reconnection.
 */
@Component
public class RedisConnectionManager implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RedisConnectionManager.class);
    private static final long HEALTH_CHECK_COOLDOWN_MS = 10_000;

    private final TrackCacheConfig config;
    private final RedisClient redisClient;
    private volatile StatefulRedisConnection<String, String> connection;
    private volatile RedisCommands<String, String> syncCommands;
    private volatile RedisAsyncCommands<String, String> asyncCommands;

    private volatile boolean healthy;
    private final AtomicLong lastErrorTime = new AtomicLong(0);
    private final AtomicBoolean reconnectInProgress = new AtomicBoolean(false);
    private volatile Runnable onConnectedCallback;

    @Autowired
    public RedisConnectionManager(TrackCacheConfig config,
                                  @Nullable RedisClient redisClient,
                                  @Nullable StatefulRedisConnection<String, String> connection) {
        this.config = config;
        this.redisClient = redisClient;
        this.connection = connection;
        if (connection != null && connection.isOpen()) {
            this.syncCommands = connection.sync();
            this.asyncCommands = connection.async();
            this.healthy = true;
        } else {
            this.healthy = false;
            this.lastErrorTime.set(System.currentTimeMillis());
        }
    }

    public void setOnConnectedCallback(Runnable onConnectedCallback) {
        this.onConnectedCallback = onConnectedCallback;
        if (isOpen()) {
            onConnectedCallback.run();
        }
    }

    public boolean isOpen() {
        StatefulRedisConnection<String, String> conn = this.connection;
        return conn != null && conn.isOpen();
    }

    public boolean isHealthy() {
        return healthy && isOpen();
    }

    public RedisCommands<String, String> getSyncCommands(ExecutorService reconnectExecutor) {
        return isReady(reconnectExecutor) ? this.syncCommands : null;
    }

    public RedisAsyncCommands<String, String> getAsyncCommands(ExecutorService reconnectExecutor) {
        return isReady(reconnectExecutor) ? this.asyncCommands : null;
    }

    private boolean isReady(ExecutorService reconnectExecutor) {
        if (!config.isEnabled()) {
            return false;
        }
        if (isOpen() && this.syncCommands != null && this.asyncCommands != null) {
            return healthy || allowHealthProbe();
        }
        if (redisClient != null && reconnectExecutor != null) {
            triggerAsyncReconnectIfReady(reconnectExecutor);
        }
        return false;
    }

    private boolean allowHealthProbe() {
        long now = System.currentTimeMillis();
        long lastErr = lastErrorTime.get();
        return (now - lastErr > HEALTH_CHECK_COOLDOWN_MS) && lastErrorTime.compareAndSet(lastErr, now);
    }

    private void triggerAsyncReconnectIfReady(ExecutorService reconnectExecutor) {
        long now = System.currentTimeMillis();
        long lastErr = lastErrorTime.get();
        if (now - lastErr > HEALTH_CHECK_COOLDOWN_MS && reconnectInProgress.compareAndSet(false, true)) {
            lastErrorTime.set(now);
            try {
                reconnectExecutor.submit(this::reconnectInBackground);
            } catch (Exception e) {
                reconnectInProgress.set(false);
            }
        }
    }

    private void reconnectInBackground() {
        try {
            synchronized (this) {
                if (isOpen() && this.syncCommands != null) {
                    markHealthy();
                    return;
                }
                if (this.redisClient == null || !config.isEnabled()) {
                    return;
                }
                StatefulRedisConnection<String, String> newConn = this.redisClient.connect();
                this.connection = newConn;
                this.syncCommands = newConn.sync();
                this.asyncCommands = newConn.async();
                markHealthy();
                Runnable callback = this.onConnectedCallback;
                if (callback != null) {
                    callback.run();
                }
            }
        } catch (Exception e) {
            recordError("Failed to reconnect to Redis in background", e, null);
        } finally {
            reconnectInProgress.set(false);
        }
    }

    public void markHealthy() {
        if (!healthy) {
            healthy = true;
            log.info("Redis connection is healthy and active.");
        }
    }

    public void recordError(String message, Exception e, @Nullable TrackCacheStats stats) {
        if (stats != null) {
            stats.recordError();
        }
        healthy = false;
        lastErrorTime.set(System.currentTimeMillis());
        log.warn("{}: {} (temporarily marking Redis as unavailable for 10s)", message, e.getMessage());
    }

    @Override
    public void close() {
        syncCommands = null;
        asyncCommands = null;
        StatefulRedisConnection<String, String> conn = this.connection;
        this.connection = null;
        if (conn != null && conn.isOpen()) {
            try {
                conn.close();
            } catch (Exception ignored) {
            }
        }
    }
}

