package com.github.petergamez.trackcache;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/v4/trackcache")
@ConditionalOnProperty(prefix = "plugins.trackcache.rest-api", name = "enabled", havingValue = "true", matchIfMissing = true)
public class TrackCacheRestHandler {

    private static final Logger log = LoggerFactory.getLogger(TrackCacheRestHandler.class);

    private final TrackCacheService trackCacheService;
    private final UrlNormalizer normalizer;
    private final TrackCachePluginConfig config;
    private final ObjectMapper objectMapper;

    public TrackCacheRestHandler(TrackCacheService trackCacheService,
                                 UrlNormalizer normalizer,
                                 TrackCachePluginConfig config,
                                 ObjectMapper objectMapper) {
        this.trackCacheService = trackCacheService;
        this.normalizer = normalizer;
        this.config = config;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats(
            @RequestHeader(value = "X-TrackCache-Password", required = false) String trackCachePassword) {
        if (!isAuthorized(trackCachePassword)) {
            return unauthorizedResponse();
        }
        return ResponseEntity.ok(trackCacheService.getStats());
    }

    @GetMapping("/config")
    public ResponseEntity<Map<String, Object>> getConfig(
            @RequestHeader(value = "X-TrackCache-Password", required = false) String trackCachePassword) {
        if (!isAuthorized(trackCachePassword)) {
            return unauthorizedResponse();
        }
        return ResponseEntity.ok(buildConfigSnapshot());
    }

    @RequestMapping(value = "/config", method = {RequestMethod.POST, RequestMethod.PATCH})
    public ResponseEntity<Map<String, Object>> updateConfig(
            @RequestBody(required = false) Map<String, Object> body,
            @RequestHeader(value = "X-TrackCache-Password", required = false) String trackCachePassword) {
        if (!isAuthorized(trackCachePassword)) {
            return unauthorizedResponse();
        }
        if (body == null || body.isEmpty()) {
            return badRequestResponse("Request body cannot be empty");
        }

        try {
            Boolean topLevelEnabled = getBoolean(body, "enabled");
            if (topLevelEnabled != null) {
                config.setEnabled(topLevelEnabled);
            }

            if (body.get("ttl") instanceof Map<?, ?> ttlMap) {
                applyTtlUpdates(ttlMap, true);
            } else {
                applyTtlUpdates(body, false);
            }

            if (body.get("cache") instanceof Map<?, ?> cacheMap) {
                applyCacheUpdates(cacheMap);
            } else {
                applyCacheUpdates(body);
            }
        } catch (IllegalArgumentException e) {
            return badRequestResponse(e.getMessage());
        }

        log.info("TrackCache configuration updated dynamically via REST API: enabled={}, TTL={}, Cache={}",
                config.isEnabled(), config.getRedis().getTtl().toMap(), config.getCache().toMap());

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("message", "Configuration updated successfully");
        response.putAll(buildConfigSnapshot());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/resolve")
    public ResponseEntity<Map<String, Object>> resolve(
            @RequestParam("identifier") String identifier,
            @RequestHeader(value = "X-TrackCache-Password", required = false) String trackCachePassword) {
        if (!isAuthorized(trackCachePassword)) {
            return unauthorizedResponse();
        }
        String canonicalKey = normalizer.normalize(identifier);
        boolean cacheable = canonicalKey != null && normalizer.isCacheableKey(canonicalKey);
        if (!cacheable) {
            canonicalKey = null;
        }
        String cachedJson = cacheable ? trackCacheService.peekCachedResult(canonicalKey) : null;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("identifier", identifier);
        result.put("cacheable", cacheable);
        result.put("canonicalKey", canonicalKey);
        result.put("cached", cachedJson != null);
        if (cachedJson != null) {
            try {
                result.put("cachedData", objectMapper.readTree(cachedJson));
            } catch (Exception e) {
                result.put("cachedData", cachedJson);
            }
        } else {
            result.put("cachedData", null);
        }

        return ResponseEntity.ok(result);
    }

    @PostMapping("/clear")
    public ResponseEntity<Map<String, Object>> clear(
            @RequestParam(value = "identifier", required = false) String identifier,
            @RequestParam(value = "purgeOnly", defaultValue = "false") boolean purgeOnly,
            @RequestHeader(value = "X-TrackCache-Password", required = false) String trackCachePassword) {
        if (!isAuthorized(trackCachePassword)) {
            return unauthorizedResponse();
        }
        if (identifier != null && !identifier.trim().isEmpty()) {
            boolean evicted = trackCacheService.evict(identifier);
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("success", evicted);
            response.put("message", evicted ? "Key evicted successfully" : "Key not found or error");
            response.put("identifier", identifier);
            response.put("canonicalKey", normalizer.normalize(identifier));
            return ResponseEntity.ok(response);
        }
        if (purgeOnly) {
            return ResponseEntity.ok(buildPurgeInvalidResponse());
        }
        long count = trackCacheService.clearAll();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("clearedCount", count);
        response.put("message", "Cleared " + count + " cached keys");
        return ResponseEntity.ok(response);
    }

    @PostMapping("/purge-invalid")
    public ResponseEntity<Map<String, Object>> purgeInvalid(
            @RequestHeader(value = "X-TrackCache-Password", required = false) String trackCachePassword) {
        if (!isAuthorized(trackCachePassword)) {
            return unauthorizedResponse();
        }
        return ResponseEntity.ok(buildPurgeInvalidResponse());
    }

    private Map<String, Object> buildConfigSnapshot() {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("enabled", config.isEnabled());
        snapshot.put("ttl", config.getRedis().getTtl().toMap());
        snapshot.put("cache", config.getCache().toMap());
        return snapshot;
    }

    private Map<String, Object> buildPurgeInvalidResponse() {
        long count = trackCacheService.purgeInvalidKeys();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("purgedCount", count);
        response.put("message", "Purged " + count + " invalid HTTP and youtube:http keys");
        return response;
    }

    private void applyTtlUpdates(Map<?, ?> map, boolean nestedTtlMap) {
        TrackCacheConfig.RedisConfig.TtlConfig ttl = config.getRedis().getTtl();

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

    private void applyCacheUpdates(Map<?, ?> map) {
        TrackCacheConfig.CacheConfig cache = config.getCache();

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

    private Boolean getBoolean(Map<?, ?> map, String... keys) {
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

    private Long getNonNegativeLong(Map<?, ?> map, String fieldLabel, String... keys) {
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

    private ResponseEntity<Map<String, Object>> badRequestResponse(String message) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("error", "Bad Request");
        error.put("message", message);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
    }

    private boolean isAuthorized(String trackCachePassword) {
        String requiredPassword = config.getRestApi().getPassword();
        if (requiredPassword == null || requiredPassword.trim().isEmpty()) {
            return true;
        }
        if (trackCachePassword == null) {
            return false;
        }
        return MessageDigest.isEqual(
                requiredPassword.trim().getBytes(StandardCharsets.UTF_8),
                trackCachePassword.trim().getBytes(StandardCharsets.UTF_8)
        );
    }

    private ResponseEntity<Map<String, Object>> unauthorizedResponse() {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("error", "Unauthorized");
        error.put("message", "Invalid or missing X-TrackCache-Password header");
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(error);
    }
}
