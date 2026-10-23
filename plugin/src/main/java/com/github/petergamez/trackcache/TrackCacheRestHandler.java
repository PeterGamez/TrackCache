package com.github.petergamez.trackcache;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/v4/trackcache")
@ConditionalOnProperty(prefix = "plugins.trackcache", name = "rest-api", havingValue = "true", matchIfMissing = true)
public class TrackCacheRestHandler {

    private final TrackCacheService trackCacheService;
    private final UrlNormalizer normalizer;
    private final TrackCachePluginConfig config;

    public TrackCacheRestHandler(TrackCacheService trackCacheService,
                                 UrlNormalizer normalizer,
                                 TrackCachePluginConfig config) {
        this.trackCacheService = trackCacheService;
        this.normalizer = normalizer;
        this.config = config;
    }

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats() {
        if (!config.isRestApi()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        return ResponseEntity.ok(trackCacheService.getStats());
    }

    @GetMapping("/resolve")
    public ResponseEntity<Map<String, Object>> resolve(@RequestParam("identifier") String identifier) {
        if (!config.isRestApi()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        String canonicalKey = normalizer.normalize(identifier);
        String cachedJson = trackCacheService.getCachedResult(identifier);

        result.put("identifier", identifier);
        result.put("canonicalKey", canonicalKey);
        result.put("cached", cachedJson != null);
        result.put("cachedData", cachedJson);

        return ResponseEntity.ok(result);
    }

    @PostMapping("/clear")
    public ResponseEntity<Map<String, Object>> clear(@RequestParam(value = "identifier", required = false) String identifier) {
        if (!config.isRestApi()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
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
}
