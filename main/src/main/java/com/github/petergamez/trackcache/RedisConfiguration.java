package com.github.petergamez.trackcache;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.SocketOptions;
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
        if (!config.isEnabled()) {
            log.info("TrackCache is disabled in configuration.");
        }
        TrackCacheConfig.RedisConfig redis = config.getRedis();
        log.info("Initializing Lettuce Redis connection to {}:{} (db: {}, ssl: {})",
                redis.getHost(), redis.getPort(), redis.getDatabase(), redis.isSsl());
        return createRedisClient(config);
    }

    @Bean(destroyMethod = "close")
    public StatefulRedisConnection<String, String> redisConnection(RedisClient redisClient) {
        if (!config.isEnabled()) {
            return null;
        }
        try {
            return redisClient.connect();
        } catch (Exception e) {
            log.warn("Failed to connect to Redis on startup: {}. Reconnection will occur on demand.", e.getMessage());
            return null;
        }
    }

    public static RedisClient createRedisClient(TrackCacheConfig config) {
        TrackCacheConfig.RedisConfig redis = config.getRedis();
        RedisURI.Builder builder = RedisURI.builder()
                .withHost(redis.getHost())
                .withPort(redis.getPort())
                .withDatabase(redis.getDatabase())
                .withSsl(redis.isSsl())
                .withTimeout(Duration.ofSeconds(2));

        if (redis.getPassword() != null && !redis.getPassword().trim().isEmpty()) {
            builder.withPassword(redis.getPassword().trim().toCharArray());
        }

        SocketOptions socketOptions = SocketOptions.builder()
                .connectTimeout(Duration.ofSeconds(3))
                .keepAlive(true)
                .build();

        ClientOptions clientOptions = ClientOptions.builder()
                .socketOptions(socketOptions)
                .autoReconnect(true)
                .build();

        RedisClient client = RedisClient.create(builder.build());
        client.setOptions(clientOptions);
        return client;
    }
}
