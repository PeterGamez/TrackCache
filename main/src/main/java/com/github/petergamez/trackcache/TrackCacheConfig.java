package com.github.petergamez.trackcache;

/**
 * Core configuration POJO for TrackCache Redis caching.
 * Can be used in standalone Lavaplayer applications or extended by Lavalink plugin.
 */
public class TrackCacheConfig {

    private boolean enabled = true;
    private RedisConfig redis = new RedisConfig();

    private boolean cachePlaylists = true;
    private boolean cacheIndividualPlaylistTracks = true;
    private boolean cacheEmpty = false;
    private boolean cacheSearches = true;

    public static class RedisConfig {
        private String host = "localhost";
        private int port = 6379;
        private String password = "";
        private int database = 0;
        private boolean ssl = false;
        private String keyPrefix = "trackcache:";
        private TtlConfig ttl = new TtlConfig();

        public static class TtlConfig {
            private boolean enabled = true;
            private long hours = 24; // Default: 24 hours (1 day)

            public boolean isEnabled() {
                return enabled;
            }

            public void setEnabled(boolean enabled) {
                this.enabled = enabled;
            }

            public long getHours() {
                return hours;
            }

            public void setHours(long hours) {
                this.hours = hours;
            }
        }

        public TtlConfig getTtl() {
            return ttl;
        }

        public void setTtl(TtlConfig ttl) {
            this.ttl = ttl;
        }

        public String getHost() {
            return host;
        }

        public void setHost(String host) {
            this.host = host;
        }

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public int getDatabase() {
            return database;
        }

        public void setDatabase(int database) {
            this.database = database;
        }

        public boolean isSsl() {
            return ssl;
        }

        public void setSsl(boolean ssl) {
            this.ssl = ssl;
        }


        public String getKeyPrefix() {
            return keyPrefix;
        }

        public void setKeyPrefix(String keyPrefix) {
            this.keyPrefix = keyPrefix;
        }
    }

    public RedisConfig getRedis() {
        return redis;
    }

    public void setRedis(RedisConfig redis) {
        this.redis = redis;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    // Convenience delegates
    public String getHost() {
        return redis != null ? redis.getHost() : "localhost";
    }

    public void setHost(String host) {
        if (redis != null) redis.setHost(host);
    }

    public int getPort() {
        return redis != null ? redis.getPort() : 6379;
    }

    public void setPort(int port) {
        if (redis != null) redis.setPort(port);
    }

    public String getPassword() {
        return redis != null ? redis.getPassword() : "";
    }

    public void setPassword(String password) {
        if (redis != null) redis.setPassword(password);
    }

    public int getDatabase() {
        return redis != null ? redis.getDatabase() : 0;
    }

    public void setDatabase(int database) {
        if (redis != null) redis.setDatabase(database);
    }

    public boolean isSsl() {
        return redis != null && redis.isSsl();
    }

    public void setSsl(boolean ssl) {
        if (redis != null) redis.setSsl(ssl);
    }

    public boolean isTtlEnabled() {
        return redis != null && redis.getTtl() != null && redis.getTtl().isEnabled();
    }

    public long getTtlHours() {
        return redis != null && redis.getTtl() != null ? redis.getTtl().getHours() : 24;
    }

    public String getKeyPrefix() {
        return redis != null ? redis.getKeyPrefix() : "trackcache:";
    }

    public void setKeyPrefix(String keyPrefix) {
        if (redis != null) redis.setKeyPrefix(keyPrefix);
    }

    public boolean isCachePlaylists() {
        return cachePlaylists;
    }

    public void setCachePlaylists(boolean cachePlaylists) {
        this.cachePlaylists = cachePlaylists;
    }

    public boolean isCacheIndividualPlaylistTracks() {
        return cacheIndividualPlaylistTracks;
    }

    public void setCacheIndividualPlaylistTracks(boolean cacheIndividualPlaylistTracks) {
        this.cacheIndividualPlaylistTracks = cacheIndividualPlaylistTracks;
    }

    public boolean isCacheEmpty() {
        return cacheEmpty;
    }

    public void setCacheEmpty(boolean cacheEmpty) {
        this.cacheEmpty = cacheEmpty;
    }

    public boolean isCacheSearches() {
        return cacheSearches;
    }

    public void setCacheSearches(boolean cacheSearches) {
        this.cacheSearches = cacheSearches;
    }
}
