package com.github.petergamez.trackcache;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/v4/trackcache")
@ConditionalOnProperty(prefix = "plugins.trackcache.rest-api", name = "enabled", havingValue = "true", matchIfMissing = true)
public class TrackCacheRestHandler {

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

    @GetMapping("/resolve")
    public ResponseEntity<Map<String, Object>> resolve(
            @RequestParam("identifier") String identifier,
            @RequestHeader(value = "X-TrackCache-Password", required = false) String trackCachePassword) {
        if (!isAuthorized(trackCachePassword)) {
            return unauthorizedResponse();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        String canonicalKey = normalizer.normalize(identifier);
        String cachedJson = trackCacheService.getCachedResult(identifier);

        result.put("identifier", identifier);
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
            @RequestHeader(value = "X-TrackCache-Password", required = false) String trackCachePassword) {
        if (!isAuthorized(trackCachePassword)) {
            return unauthorizedResponse();
        }
        Map<String, Object> response = new LinkedHashMap<>();

        if (identifier != null && !identifier.trim().isEmpty()) {
            boolean evicted = trackCacheService.evict(identifier);
            response.put("success", evicted);
            response.put("message", evicted ? "Key evicted successfully" : "Key not found or error");
            response.put("identifier", identifier);
            response.put("canonicalKey", normalizer.normalize(identifier));
        } else {
            long count = trackCacheService.clearAll();
            response.put("success", true);
            response.put("clearedCount", count);
            response.put("message", "Cleared " + count + " cached keys");
        }

        return ResponseEntity.ok(response);
    }

    private boolean isAuthorized(String trackCachePassword) {
        String requiredPassword = config.getRestApi().getPassword();
        if (requiredPassword == null || requiredPassword.trim().isEmpty()) {
            return true; // Optional: disabled if not specified
        }
        return trackCachePassword != null && requiredPassword.equals(trackCachePassword.trim());
    }

    private ResponseEntity<Map<String, Object>> unauthorizedResponse() {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("error", "Unauthorized");
        error.put("message", "Invalid or missing X-TrackCache-Password header");
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(error);
    }
}
