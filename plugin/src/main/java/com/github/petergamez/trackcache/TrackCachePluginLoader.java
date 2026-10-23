package com.github.petergamez.trackcache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;

@AutoConfiguration
@ComponentScan(basePackages = "com.github.petergamez.trackcache")
@Import({
        TrackCachePluginConfig.class,
        RedisConfiguration.class,
        UrlNormalizer.class,
        TrackCacheService.class,
        TrackCacheRestInterceptor.class,
        TrackCacheResponseBodyAdvice.class,
        TrackCacheRestHandler.class
})
public class TrackCachePluginLoader {

    private static final Logger log = LoggerFactory.getLogger(TrackCachePluginLoader.class);

    public TrackCachePluginLoader(TrackCachePluginConfig config) {
        log.info("TrackCache Plugin for Lavalink v4 loaded successfully! (REST API: {})",
                config.isRestApi() ? "ENABLED" : "DISABLED");
    }
}
