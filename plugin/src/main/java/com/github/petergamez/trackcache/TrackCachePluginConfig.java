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

    private RestApiConfig restApi = new RestApiConfig();

    public static class RestApiConfig {
        private boolean enabled = true;
        private String password = "";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }
    }

    public RestApiConfig getRestApi() {
        return restApi;
    }

    public void setRestApi(RestApiConfig restApi) {
        this.restApi = restApi;
    }
}
