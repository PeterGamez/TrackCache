package com.github.petergamez.trackcache.redis;

import com.github.petergamez.trackcache.config.TrackCacheConfig;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.TimeoutOptions;
import io.lettuce.core.api.StatefulRedisConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class RedisConfiguration {

    private static final Logger log = LoggerFactory.getLogger(RedisConfiguration.class);

    private final TrackCacheConfig config;

    public RedisConfiguration(TrackCacheConfig config) {
        this.config = config;
    }

    @Bean(destroyMethod = "shutdown")
    public RedisClient redisClient() {
        TrackCacheConfig.RedisConfig redis = config.getRedis();
        if (!config.isEnabled()) {
            log.info("TrackCache is disabled in configuration (Redis client initialized on standby for {}:{}).",
                    redis.getHost(), redis.getPort());
        } else {
            log.info("Initializing Lettuce Redis connection to {}:{} (db: {}, ssl: {})",
                    redis.getHost(), redis.getPort(), redis.getDatabase(), redis.isSsl());
        }
        return createRedisClient(config);
    }

    @Bean(destroyMethod = "close")
    public StatefulRedisConnection<String, String> redisConnection(RedisClient redisClient) {
        if (!config.isEnabled() || redisClient == null) {
            return null;
        }
        try {
            return redisClient.connect();
        } catch (Exception e) {
            log.warn("Failed to connect to Redis on startup: {}. Non-blocking background reconnection will occur on demand.", e.getMessage());
            return null;
        }
    }

    public static RedisClient createRedisClient(TrackCacheConfig config) {
        TrackCacheConfig.RedisConfig redis = config.getRedis();
        String host = redis.getHost() != null && !redis.getHost().trim().isEmpty()
                ? redis.getHost().trim()
                : "localhost";
        int port = redis.getPort() > 0 ? redis.getPort() : 6379;
        int database = Math.max(0, redis.getDatabase());

        RedisURI.Builder builder = RedisURI.builder()
                .withHost(host)
                .withPort(port)
                .withDatabase(database)
                .withSsl(redis.isSsl())
                .withTimeout(Duration.ofSeconds(2));

        if (redis.getPassword() != null && !redis.getPassword().trim().isEmpty()) {
            builder.withPassword(redis.getPassword().trim().toCharArray());
        }

        SocketOptions socketOptions = SocketOptions.builder()
                .connectTimeout(Duration.ofSeconds(3))
                .keepAlive(true)
                .tcpNoDelay(true)
                .build();

        ClientOptions clientOptions = ClientOptions.builder()
                .socketOptions(socketOptions)
                .timeoutOptions(TimeoutOptions.enabled(Duration.ofSeconds(2)))
                .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                .autoReconnect(true)
                .build();

        RedisClient client = RedisClient.create(builder.build());
        client.setOptions(clientOptions);
        return client;
    }
}

