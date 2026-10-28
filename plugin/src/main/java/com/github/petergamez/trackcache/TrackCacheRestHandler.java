package com.github.petergamez.trackcache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.petergamez.trackcache.config.TrackCacheConfigUpdater;
import com.github.petergamez.trackcache.normalizer.UrlNormalizer;
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

    private final TrackCache trackCache;
    private final UrlNormalizer normalizer;
    private final TrackCachePluginConfig config;
    private final ObjectMapper objectMapper;

    public TrackCacheRestHandler(TrackCache trackCache,
                                 UrlNormalizer normalizer,
                                 TrackCachePluginConfig config,
                                 ObjectMapper objectMapper) {
        this.trackCache = trackCache;
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
        return ResponseEntity.ok(trackCache.getStats());
    }

    @GetMapping("/config")
    public ResponseEntity<Map<String, Object>> getConfig(
            @RequestHeader(value = "X-TrackCache-Password", required = false) String trackCachePassword) {
        if (!isAuthorized(trackCachePassword)) {
            return unauthorizedResponse();
        }
        return ResponseEntity.ok(TrackCacheConfigUpdater.toSnapshotMap(config));
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
            TrackCacheConfigUpdater.applyUpdates(config, body);
        } catch (IllegalArgumentException e) {
            return badRequestResponse(e.getMessage());
        }

        log.info("TrackCache configuration updated dynamically via REST API: enabled={}, TTL={}, Cache={}",
                config.isEnabled(), config.getRedis().getTtl().toMap(), config.getCache().toMap());

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("message", "Configuration updated successfully");
        response.putAll(TrackCacheConfigUpdater.toSnapshotMap(config));
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
        String cachedJson = cacheable ? trackCache.peekCachedResult(canonicalKey) : null;

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
            boolean evicted = trackCache.evict(identifier);
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
        long count = trackCache.clearAll();
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

    private Map<String, Object> buildPurgeInvalidResponse() {
        long count = trackCache.purgeInvalidKeys();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("purgedCount", count);
        response.put("message", "Purged " + count + " invalid HTTP and youtube:http keys");
        return response;
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
