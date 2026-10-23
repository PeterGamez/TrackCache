package com.github.petergamez.trackcache;

/**
 * Core configuration POJO for TrackCache Redis caching.
 * Can be used in standalone Lavaplayer applications or extended by Lavalink plugin.
 */
public class TrackCacheConfig {

    private boolean enabled = true;
    private String host = "localhost";
    private int port = 6379;
    private String password = "";
    private int database = 0;
    private boolean ssl = false;
    private long ttlMinutes = 1440; // 24 hours
    private String keyPrefix = "trackcache:";

    private boolean cachePlaylists = true;
    private boolean cacheIndividualPlaylistTracks = true;
    private boolean cacheEmpty = false;
    private boolean cacheSearches = true;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
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

    public long getTtlMinutes() {
        return ttlMinutes;
    }

    public void setTtlMinutes(long ttlMinutes) {
        this.ttlMinutes = ttlMinutes;
    }

    public String getKeyPrefix() {
        return keyPrefix;
    }

    public void setKeyPrefix(String keyPrefix) {
        this.keyPrefix = keyPrefix;
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
