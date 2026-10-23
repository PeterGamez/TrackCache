package com.github.petergamez.trackcache;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Lavalink v4 plugin configuration binding for TrackCache.
 * Binds properties from application.yml under 'plugins.trackcache'.
 */
@Component
@ConfigurationProperties(prefix = "plugins.trackcache")
public class TrackCachePluginConfig extends TrackCacheConfig {

    private boolean restApi = true;

    public boolean isRestApi() {
        return restApi;
    }

    public void setRestApi(boolean restApi) {
        this.restApi = restApi;
    }
}
