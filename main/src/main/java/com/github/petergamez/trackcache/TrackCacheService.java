package com.github.petergamez.trackcache;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class TrackCacheService {

    private static final Logger log = LoggerFactory.getLogger(TrackCacheService.class);

    private final TrackCacheConfig config;
    private final UrlNormalizer normalizer;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    private final AtomicLong hits = new AtomicLong(0);
    private final AtomicLong misses = new AtomicLong(0);
    private final AtomicLong errors = new AtomicLong(0);
    private final AtomicLong puts = new AtomicLong(0);

    private volatile boolean redisHealthy = true;
    private volatile long lastErrorTime = 0;
    private static final long HEALTH_CHECK_COOLDOWN_MS = 10_000;

    public TrackCacheService(TrackCacheConfig config,
                             UrlNormalizer normalizer,
                             StringRedisTemplate redisTemplate,
                             ObjectMapper objectMapper) {
        this.config = config;
        this.normalizer = normalizer;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    public String getCachedResult(String identifier) {
        if (!config.isEnabled() || !isRedisAvailable()) {
            return null;
        }

        try {
            String fullKey = normalizer.toFullCacheKey(identifier, config.getKeyPrefix());
            String cached = redisTemplate.opsForValue().get(fullKey);

            if (cached != null && !cached.isEmpty()) {
                hits.incrementAndGet();
                log.debug("Cache HIT for identifier: '{}' [key: {}]", identifier, fullKey);
                return cached;
            } else {
                misses.incrementAndGet();
                log.debug("Cache MISS for identifier: '{}' [key: {}]", identifier, fullKey);
                return null;
            }
        } catch (Exception e) {
            handleRedisError("Error reading from Redis for identifier: " + identifier, e);
            misses.incrementAndGet();
            return null;
        }
    }

    public void cacheResult(String identifier, Object responseBody) {
        if (!config.isEnabled() || !isRedisAvailable() || responseBody == null) {
            return;
        }

        try {
            String json;
            JsonNode rootNode;

            if (responseBody instanceof String) {
                json = (String) responseBody;
                rootNode = objectMapper.readTree(json);
            } else {
                json = objectMapper.writeValueAsString(responseBody);
                rootNode = objectMapper.valueToTree(responseBody);
            }

            String loadType = rootNode.has("loadType") ? rootNode.get("loadType").asText() : "";

            if ("error".equalsIgnoreCase(loadType)) {
                log.debug("Skipping caching error response for: '{}'", identifier);
                return;
            }
            if ("empty".equalsIgnoreCase(loadType) && !config.isCacheEmpty()) {
                log.debug("Skipping caching empty response for: '{}'", identifier);
                return;
            }

            if ("search".equalsIgnoreCase(loadType) && !config.isCacheSearches()) {
                log.debug("Skipping caching search response for: '{}'", identifier);
                return;
            }
            if ("playlist".equalsIgnoreCase(loadType) && !config.isCachePlaylists()) {
                log.debug("Skipping caching full playlist response for: '{}'", identifier);
                if (config.isCacheIndividualPlaylistTracks()) {
                    decomposeAndCachePlaylistTracks(rootNode, Duration.ofMinutes(config.getTtlMinutes()));
                }
                return;
            }

            String fullKey = normalizer.toFullCacheKey(identifier, config.getKeyPrefix());
            Duration ttl = Duration.ofMinutes(config.getTtlMinutes());

            redisTemplate.opsForValue().set(fullKey, json, ttl);
            puts.incrementAndGet();
            log.info("Cached result for: '{}' [loadType: {}, key: {}, ttl: {}m]",
                    identifier, loadType, fullKey, config.getTtlMinutes());

            if ("playlist".equalsIgnoreCase(loadType) && config.isCacheIndividualPlaylistTracks()) {
                decomposeAndCachePlaylistTracks(rootNode, ttl);
            }
        } catch (Exception e) {
            handleRedisError("Error writing to Redis for identifier: " + identifier, e);
        }
    }

    private void decomposeAndCachePlaylistTracks(JsonNode playlistRoot, Duration ttl) {
        try {
            JsonNode dataNode = playlistRoot.get("data");
            if (dataNode == null || !dataNode.has("tracks")) {
                return;
            }

            JsonNode tracksNode = dataNode.get("tracks");
            if (!tracksNode.isArray() || tracksNode.isEmpty()) {
                return;
            }

            int count = 0;
            for (JsonNode trackNode : tracksNode) {
                JsonNode infoNode = trackNode.get("info");
                if (infoNode == null) continue;

                String uri = infoNode.has("uri") ? infoNode.get("uri").asText() : null;
                String trackId = infoNode.has("identifier") ? infoNode.get("identifier").asText() : null;
                String sourceName = infoNode.has("sourceName") ? infoNode.get("sourceName").asText() : "";

                String trackCanonicalKey = null;
                if (uri != null && !uri.isEmpty()) {
                    trackCanonicalKey = normalizer.normalize(uri);
                } else if (trackId != null && !trackId.isEmpty()) {
                    String platform = sourceName != null && !sourceName.isEmpty() ? sourceName.toLowerCase() : "track";
                    trackCanonicalKey = platform + ":track:" + trackId;
                }

                if (trackCanonicalKey == null || trackCanonicalKey.isEmpty()) {
                    continue;
                }

                ObjectNode singleTrackResult = objectMapper.createObjectNode();
                singleTrackResult.put("loadType", "track");
                singleTrackResult.set("data", trackNode);

                String singleTrackJson = objectMapper.writeValueAsString(singleTrackResult);
                String fullTrackKey = config.getKeyPrefix() + trackCanonicalKey;

                redisTemplate.opsForValue().set(fullTrackKey, singleTrackJson, ttl);
                puts.incrementAndGet();
                count++;
            }

            log.debug("Decomposed and cached {} individual tracks from playlist.", count);
        } catch (Exception e) {
            log.warn("Failed to decompose playlist tracks: {}", e.getMessage());
        }
    }

    public boolean evict(String identifier) {
        if (!isRedisAvailable()) return false;
        try {
            String fullKey = normalizer.toFullCacheKey(identifier, config.getKeyPrefix());
            Boolean deleted = redisTemplate.delete(fullKey);
            return Boolean.TRUE.equals(deleted);
        } catch (Exception e) {
            handleRedisError("Error evicting key for: " + identifier, e);
            return false;
        }
    }

    public long clearAll() {
        if (!isRedisAvailable()) return 0;
        try {
            Set<String> keys = redisTemplate.keys(config.getKeyPrefix() + "*");
            if (keys != null && !keys.isEmpty()) {
                Long deleted = redisTemplate.delete(keys);
                return deleted != null ? deleted : 0;
            }
            return 0;
        } catch (Exception e) {
            handleRedisError("Error clearing all keys", e);
            return 0;
        }
    }

    public Map<String, Object> getStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        long h = hits.get();
        long m = misses.get();
        long totalRequests = h + m;
        double hitRatio = totalRequests > 0 ? ((double) h / totalRequests) * 100.0 : 0.0;

        stats.put("enabled", config.isEnabled());
        stats.put("redisHealthy", redisHealthy);
        stats.put("hits", h);
        stats.put("misses", m);
        stats.put("puts", puts.get());
        stats.put("errors", errors.get());
        stats.put("hitRatioPercent", Math.round(hitRatio * 100.0) / 100.0);
        stats.put("ttlMinutes", config.getTtlMinutes());
        stats.put("keyPrefix", config.getKeyPrefix());

        try {
            if (isRedisAvailable()) {
                Set<String> keys = redisTemplate.keys(config.getKeyPrefix() + "*");
                stats.put("totalCachedKeys", keys != null ? keys.size() : 0);
            } else {
                stats.put("totalCachedKeys", "unavailable");
            }
        } catch (Exception e) {
            stats.put("totalCachedKeys", "error: " + e.getMessage());
        }

        return stats;
    }

    private boolean isRedisAvailable() {
        if (redisHealthy) return true;
        if (System.currentTimeMillis() - lastErrorTime > HEALTH_CHECK_COOLDOWN_MS) {
            redisHealthy = true;
            return true;
        }
        return false;
    }

    private void handleRedisError(String message, Exception e) {
        errors.incrementAndGet();
        redisHealthy = false;
        lastErrorTime = System.currentTimeMillis();
        log.warn("{}: {} (temporarily marking Redis as unavailable for 10s)", message, e.getMessage());
    }
}
