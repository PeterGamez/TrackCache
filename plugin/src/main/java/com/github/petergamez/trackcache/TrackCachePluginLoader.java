package com.github.petergamez.trackcache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class TrackCachePluginLoader {

    private static final Logger log = LoggerFactory.getLogger(TrackCachePluginLoader.class);

    public TrackCachePluginLoader(TrackCachePluginConfig config) {
        log.info("TrackCache Plugin for Lavalink v4 loaded successfully! (REST API: {})",
                config.getRestApi().isEnabled() ? "ENABLED" : "DISABLED");
    }
}
