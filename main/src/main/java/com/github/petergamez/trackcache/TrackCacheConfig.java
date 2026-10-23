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
            private long trackHours = 72; // Default: 72 hours (3 days)
            private long searchHours = 2; // Default: 2 hours
            private long playlistHours = 24; // Default: 24 hours

            public boolean isEnabled() {
                return enabled;
            }

            public void setEnabled(boolean enabled) {
                this.enabled = enabled;
            }

            public long getTrackHours() {
                return trackHours;
            }

            public void setTrackHours(long trackHours) {
                this.trackHours = trackHours;
            }

            public void setTrack(long track) {
                this.trackHours = track;
            }

            public long getSearchHours() {
                return searchHours;
            }

            public void setSearchHours(long searchHours) {
                this.searchHours = searchHours;
            }

            public void setSearch(long search) {
                this.searchHours = search;
            }

            public long getPlaylistHours() {
                return playlistHours;
            }

            public void setPlaylistHours(long playlistHours) {
                this.playlistHours = playlistHours;
            }

            public void setPlaylist(long playlist) {
                this.playlistHours = playlist;
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
