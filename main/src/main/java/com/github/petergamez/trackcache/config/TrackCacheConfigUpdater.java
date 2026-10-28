package com.github.petergamez.trackcache.config;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Handles runtime configuration snapshots and dynamic partial updates for {@link TrackCacheConfig}.
 */
public final class TrackCacheConfigUpdater {

    private TrackCacheConfigUpdater() {
    }

    public static Map<String, Object> toSnapshotMap(TrackCacheConfig config) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("enabled", config.isEnabled());
        snapshot.put("ttl", config.getRedis().getTtl().toMap());
        snapshot.put("cache", config.getCache().toMap());
        return snapshot;
    }

    public static void applyUpdates(TrackCacheConfig config, Map<String, Object> body) {
        Boolean topLevelEnabled = getBoolean(body, "enabled");
        if (topLevelEnabled != null) {
            config.setEnabled(topLevelEnabled);
        }

        if (body.get("ttl") instanceof Map<?, ?> ttlMap) {
            applyTtlUpdates(config.getRedis().getTtl(), ttlMap, true);
        } else {
            applyTtlUpdates(config.getRedis().getTtl(), body, false);
        }

        if (body.get("cache") instanceof Map<?, ?> cacheMap) {
            applyCacheUpdates(config.getCache(), cacheMap);
        } else {
            applyCacheUpdates(config.getCache(), body);
        }
    }

    private static void applyTtlUpdates(TrackCacheConfig.RedisConfig.TtlConfig ttl, Map<?, ?> map, boolean nestedTtlMap) {
        Boolean enabled = getBoolean(map, "ttlEnabled", "ttl-enabled", "ttl_enabled");
        if (enabled == null && nestedTtlMap) {
            enabled = getBoolean(map, "enabled");
        }
        if (enabled != null) {
            ttl.setEnabled(enabled);
        }

        Long trackHours = getNonNegativeLong(map, "trackHours", "trackHours", "track-hours", "track_hours", "track");
        if (trackHours != null) {
            ttl.setTrackHours(trackHours);
        }

        Long playlistHours = getNonNegativeLong(map, "playlistHours", "playlistHours", "playlist-hours", "playlist_hours", "playlist");
        if (playlistHours != null) {
            ttl.setPlaylistHours(playlistHours);
        }

        Long searchHours = getNonNegativeLong(map, "searchHours", "searchHours", "search-hours", "search_hours", "search");
        if (searchHours != null) {
            ttl.setSearchHours(searchHours);
        }
    }

    private static void applyCacheUpdates(TrackCacheConfig.CacheConfig cache, Map<?, ?> map) {
        Boolean playlists = getBoolean(map, "playlists");
        if (playlists != null) {
            cache.setPlaylists(playlists);
        }

        Boolean indPlaylists = getBoolean(map, "individualPlaylistTracks", "individual-playlist-tracks", "individual_playlist_tracks");
        if (indPlaylists != null) {
            cache.setIndividualPlaylistTracks(indPlaylists);
        }

        Boolean searches = getBoolean(map, "searches");
        if (searches != null) {
            cache.setSearches(searches);
        }

        Boolean indSearches = getBoolean(map, "individualSearchTracks", "individual-search-tracks", "individual_search_tracks");
        if (indSearches != null) {
            cache.setIndividualSearchTracks(indSearches);
        }

        Boolean empty = getBoolean(map, "empty");
        if (empty != null) {
            cache.setEmpty(empty);
        }
    }

    private static Boolean getBoolean(Map<?, ?> map, String... keys) {
        for (String k : keys) {
            if (map.containsKey(k)) {
                Object val = map.get(k);
                if (val == null) continue;
                if (val instanceof Boolean b) return b;
                String str = val.toString().trim();
                if ("true".equalsIgnoreCase(str)) return true;
                if ("false".equalsIgnoreCase(str)) return false;
                throw new IllegalArgumentException("Invalid boolean for key '" + k + "': " + val);
            }
        }
        return null;
    }

    private static Long getNonNegativeLong(Map<?, ?> map, String fieldLabel, String... keys) {
        for (String k : keys) {
            if (map.containsKey(k)) {
                Object val = map.get(k);
                if (val == null) continue;
                long parsed;
                if (val instanceof Number n) {
                    parsed = n.longValue();
                } else {
                    try {
                        parsed = Long.parseLong(val.toString().trim());
                    } catch (NumberFormatException e) {
                        throw new IllegalArgumentException("Invalid number for key '" + k + "': " + val);
                    }
                }
                if (parsed < 0) {
                    throw new IllegalArgumentException(fieldLabel + " cannot be negative");
                }
                return parsed;
            }
        }
        return null;
    }
}

