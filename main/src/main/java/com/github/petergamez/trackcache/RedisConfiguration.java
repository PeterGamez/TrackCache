package com.github.petergamez.trackcache;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

@Configuration
public class RedisConfiguration {

    private static final Logger log = LoggerFactory.getLogger(RedisConfiguration.class);

    private final TrackCacheConfig config;

    public RedisConfiguration(TrackCacheConfig config) {
        this.config = config;
    }

    @Bean
    public LettuceConnectionFactory redisConnectionFactory() {
        if (!config.isEnabled()) {
            log.info("TrackCache is disabled in configuration.");
        }
        TrackCacheConfig.RedisConfig redis = config.getRedis();
        log.info("Initializing Lettuce Redis connection to {}:{} (db: {}, ssl: {})",
                redis.getHost(), redis.getPort(), redis.getDatabase(), redis.isSsl());
        return createConnectionFactory(config);
    }

    @Bean
    public StringRedisTemplate stringRedisTemplate(LettuceConnectionFactory connectionFactory) {
        return createRedisTemplate(connectionFactory);
    }

    public static LettuceConnectionFactory createConnectionFactory(TrackCacheConfig config) {
        TrackCacheConfig.RedisConfig redis = config.getRedis();
        RedisStandaloneConfiguration serverConfig = new RedisStandaloneConfiguration();
        serverConfig.setHostName(redis.getHost());
        serverConfig.setPort(redis.getPort());
        serverConfig.setDatabase(redis.getDatabase());

        if (redis.getPassword() != null && !redis.getPassword().trim().isEmpty()) {
            serverConfig.setPassword(RedisPassword.of(redis.getPassword().trim()));
        }

        SocketOptions socketOptions = SocketOptions.builder()
                .connectTimeout(Duration.ofSeconds(3))
                .keepAlive(true)
                .build();

        ClientOptions clientOptions = ClientOptions.builder()
                .socketOptions(socketOptions)
                .autoReconnect(true)
                .build();

        LettuceClientConfiguration.LettuceClientConfigurationBuilder builder = LettuceClientConfiguration.builder()
                .commandTimeout(Duration.ofSeconds(2))
                .clientOptions(clientOptions);

        if (redis.isSsl()) {
            builder.useSsl();
        }

        LettuceConnectionFactory factory = new LettuceConnectionFactory(serverConfig, builder.build());
        factory.afterPropertiesSet();
        return factory;
    }

    public static StringRedisTemplate createRedisTemplate(LettuceConnectionFactory connectionFactory) {
        StringRedisTemplate template = new StringRedisTemplate();
        template.setConnectionFactory(connectionFactory);
        template.afterPropertiesSet();
        return template;
    }
}
