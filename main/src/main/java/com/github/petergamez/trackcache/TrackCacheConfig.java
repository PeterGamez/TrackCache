package com.github.petergamez.trackcache;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Core configuration POJO for TrackCache Redis caching.
 * Can be used in standalone Lavaplayer applications or extended by Lavalink plugin.
 */
public class TrackCacheConfig {

    private volatile boolean enabled = true;
    private RedisConfig redis = new RedisConfig();
    private CacheConfig cache = new CacheConfig();

    public static class RedisConfig {
        private String host = "localhost";
        private int port = 6379;
        private String password = "";
        private int database = 0;
        private boolean ssl = false;
        private String keyPrefix = "trackcache:";
        private TtlConfig ttl = new TtlConfig();

        public static class TtlConfig {
            private volatile boolean enabled = true;
            private volatile long trackHours = 72; // Default: 72 hours (3 days)
            private volatile long playlistHours = 24; // Default: 24 hours (1 day)
            private volatile long searchHours = 2; // Default: 2 hours

            public Map<String, Object> toMap() {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("enabled", enabled);
                map.put("trackHours", trackHours);
                map.put("playlistHours", playlistHours);
                map.put("searchHours", searchHours);
                return map;
            }

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

            public long getPlaylistHours() {
                return playlistHours;
            }

            public void setPlaylistHours(long playlistHours) {
                this.playlistHours = playlistHours;
            }

            public void setPlaylist(long playlist) {
                this.playlistHours = playlist;
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

    public static class CacheConfig {
        private volatile boolean playlists = true;
        private volatile boolean individualPlaylistTracks = true;
        private volatile boolean searches = true;
        private volatile boolean individualSearchTracks = true;
        private volatile boolean empty = false;

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("playlists", playlists);
            map.put("individualPlaylistTracks", individualPlaylistTracks);
            map.put("searches", searches);
            map.put("individualSearchTracks", individualSearchTracks);
            map.put("empty", empty);
            return map;
        }

        public boolean isPlaylists() {
            return playlists;
        }

        public void setPlaylists(boolean playlists) {
            this.playlists = playlists;
        }

        public boolean isIndividualPlaylistTracks() {
            return individualPlaylistTracks;
        }

        public void setIndividualPlaylistTracks(boolean individualPlaylistTracks) {
            this.individualPlaylistTracks = individualPlaylistTracks;
        }

        public boolean isSearches() {
            return searches;
        }

        public void setSearches(boolean searches) {
            this.searches = searches;
        }

        public boolean isIndividualSearchTracks() {
            return individualSearchTracks;
        }

        public void setIndividualSearchTracks(boolean individualSearchTracks) {
            this.individualSearchTracks = individualSearchTracks;
        }

        public boolean isEmpty() {
            return empty;
        }

        public void setEmpty(boolean empty) {
            this.empty = empty;
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public RedisConfig getRedis() {
        return redis;
    }

    public void setRedis(RedisConfig redis) {
        this.redis = redis;
    }

    public CacheConfig getCache() {
        return cache;
    }

    public void setCache(CacheConfig cache) {
        this.cache = cache;
    }
}
