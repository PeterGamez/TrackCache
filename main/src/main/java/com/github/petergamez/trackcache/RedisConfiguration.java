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
        log.info("Initializing Lettuce Redis connection to {}:{} (db: {}, ssl: {})",
                config.getHost(), config.getPort(), config.getDatabase(), config.isSsl());
        return createConnectionFactory(config);
    }

    @Bean
    public StringRedisTemplate stringRedisTemplate(LettuceConnectionFactory connectionFactory) {
        return createRedisTemplate(connectionFactory);
    }

    public static LettuceConnectionFactory createConnectionFactory(TrackCacheConfig config) {
        RedisStandaloneConfiguration serverConfig = new RedisStandaloneConfiguration();
        serverConfig.setHostName(config.getHost());
        serverConfig.setPort(config.getPort());
        serverConfig.setDatabase(config.getDatabase());

        if (config.getPassword() != null && !config.getPassword().trim().isEmpty()) {
            serverConfig.setPassword(RedisPassword.of(config.getPassword().trim()));
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

        if (config.isSsl()) {
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
