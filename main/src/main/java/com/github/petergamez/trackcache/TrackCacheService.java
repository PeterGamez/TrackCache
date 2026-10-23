package com.github.petergamez.trackcache;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class TrackCacheService implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(TrackCacheService.class);

    private final TrackCacheConfig config;
    private final UrlNormalizer normalizer;
    private final ObjectMapper objectMapper;
    private final RedisClient redisClient;
    private volatile StatefulRedisConnection<String, String> connection;

    private final AtomicLong hits = new AtomicLong(0);
    private final AtomicLong misses = new AtomicLong(0);
    private final AtomicLong errors = new AtomicLong(0);
    private final AtomicLong puts = new AtomicLong(0);

    private volatile boolean redisHealthy = true;
    private volatile long lastErrorTime = 0;
    private static final long HEALTH_CHECK_COOLDOWN_MS = 10_000;

    @org.springframework.beans.factory.annotation.Autowired
    public TrackCacheService(TrackCacheConfig config,
                             UrlNormalizer normalizer,
                             ObjectMapper objectMapper,
                             @Nullable RedisClient redisClient,
                             @Nullable StatefulRedisConnection<String, String> connection) {
        this.config = config;
        this.normalizer = normalizer;
        this.objectMapper = objectMapper;
        this.redisClient = redisClient;
        this.connection = connection;
    }

    public TrackCacheService(TrackCacheConfig config,
                             UrlNormalizer normalizer,
                             ObjectMapper objectMapper,
                             @Nullable RedisClient redisClient) {
        this(config, normalizer, objectMapper, redisClient, null);
    }

    private synchronized RedisCommands<String, String> getCommands() {
        if (!config.isEnabled() || !isRedisAvailable()) {
            return null;
        }
        if (connection != null && connection.isOpen()) {
            return connection.sync();
        }
        if (redisClient == null) {
            return null;
        }
        try {
            this.connection = redisClient.connect();
            return this.connection.sync();
        } catch (Exception e) {
            handleRedisError("Failed to connect to Redis", e);
            return null;
        }
    }

    public boolean isEnabled() {
        return config.isEnabled();
    }

    public String getCachedResult(String identifier) {
        if (!config.isEnabled() || !isRedisAvailable()) {
            return null;
        }

        try {
            RedisCommands<String, String> commands = getCommands();
            if (commands == null) {
                misses.incrementAndGet();
                return null;
            }

            String fullKey = normalizer.toFullCacheKey(identifier, config.getRedis().getKeyPrefix());
            String cached = commands.get(fullKey);

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
            JsonNode rootNode;

            if (responseBody instanceof String) {
                rootNode = objectMapper.readTree((String) responseBody);
            } else {
                rootNode = objectMapper.valueToTree(responseBody);
            }

            if (!(rootNode instanceof ObjectNode objectNode)) {
                return;
            }

            sanitizeLoadResultNode(objectNode);

            String loadType = objectNode.has("loadType") ? objectNode.get("loadType").asText() : "";

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
                if (config.isCacheIndividualSearchTracks()) {
                    decomposeAndCacheSearchTracks(objectNode, getTtlDuration("track"));
                }
                return;
            }
            if ("playlist".equalsIgnoreCase(loadType) && !config.isCachePlaylists()) {
                log.debug("Skipping caching full playlist response for: '{}'", identifier);
                if (config.isCacheIndividualPlaylistTracks()) {
                    decomposeAndCachePlaylistTracks(objectNode, getTtlDuration("track"));
                }
                return;
            }

            RedisCommands<String, String> commands = getCommands();
            if (commands == null) {
                return;
            }

            String fullKey = normalizer.toFullCacheKey(identifier, config.getRedis().getKeyPrefix());
            Duration ttl = getTtlDuration(loadType);
            String json = objectMapper.writeValueAsString(objectNode);

            if (ttl != null) {
                commands.setex(fullKey, ttl.getSeconds(), json);
            } else {
                commands.set(fullKey, json);
            }
            puts.incrementAndGet();
            String ttlDisplay = ttl != null
                    ? (ttl.toHours() > 0 ? ttl.toHours() + "h" : ttl.toMinutes() + "m")
                    : "persistent";
            log.info("Cached result for: '{}' [loadType: {}, key: {}, ttl: {}]",
                    identifier, loadType, fullKey, ttlDisplay);

            if ("playlist".equalsIgnoreCase(loadType) && config.isCacheIndividualPlaylistTracks()) {
                decomposeAndCachePlaylistTracks(objectNode, getTtlDuration("track"));
            } else if ("search".equalsIgnoreCase(loadType) && config.isCacheIndividualSearchTracks()) {
                decomposeAndCacheSearchTracks(objectNode, getTtlDuration("track"));
            }
        } catch (Exception e) {
            handleRedisError("Error writing to Redis for identifier: " + identifier, e);
        }
    }

    private void decomposeAndCacheSearchTracks(JsonNode searchRoot, Duration ttl) {
        try {
            JsonNode dataNode = searchRoot.get("data");
            if (dataNode == null || !dataNode.isArray() || dataNode.isEmpty()) {
                return;
            }
            decomposeAndCacheTrackNodes(dataNode, ttl, "search");
        } catch (Exception e) {
            log.warn("Failed to decompose search tracks: {}", e.getMessage());
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

            decomposeAndCacheTrackNodes(tracksNode, ttl, "playlist");
        } catch (Exception e) {
            log.warn("Failed to decompose playlist tracks: {}", e.getMessage());
        }
    }

    private void decomposeAndCacheTrackNodes(Iterable<JsonNode> tracksNode, Duration ttl, String source) {
        try {
            RedisCommands<String, String> commands = getCommands();
            if (commands == null) {
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

                sanitizeTrackNode(trackNode);

                ObjectNode singleTrackResult = objectMapper.createObjectNode();
                singleTrackResult.put("loadType", "track");
                singleTrackResult.set("data", trackNode);

                String singleTrackJson = objectMapper.writeValueAsString(singleTrackResult);
                String fullTrackKey = normalizer.toFullCacheKey(trackCanonicalKey, config.getRedis().getKeyPrefix());

                if (ttl != null) {
                    commands.setex(fullTrackKey, ttl.getSeconds(), singleTrackJson);
                } else {
                    commands.set(fullTrackKey, singleTrackJson);
                }
                puts.incrementAndGet();
                count++;
            }

            log.debug("Decomposed and cached {} individual tracks from {}.", count, source);
        } catch (Exception e) {
            log.warn("Failed to decompose tracks from {}: {}", source, e.getMessage());
        }
    }

    public boolean evict(String identifier) {
        if (!isRedisAvailable()) return false;
        try {
            RedisCommands<String, String> commands = getCommands();
            if (commands == null) return false;
            String fullKey = normalizer.toFullCacheKey(identifier, config.getRedis().getKeyPrefix());
            Long deleted = commands.del(fullKey);
            return deleted != null && deleted > 0;
        } catch (Exception e) {
            handleRedisError("Error evicting key for: " + identifier, e);
            return false;
        }
    }

    public long clearAll() {
        if (!isRedisAvailable()) return 0;
        try {
            RedisCommands<String, String> commands = getCommands();
            if (commands == null) return 0;
            List<String> keys = commands.keys(config.getRedis().getKeyPrefix() + "*");
            if (keys != null && !keys.isEmpty()) {
                Long deleted = commands.del(keys.toArray(new String[0]));
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
        stats.put("ttlEnabled", config.getRedis().getTtl().isEnabled());
        stats.put("ttlTrackHours", config.getRedis().getTtl().getTrackHours());
        stats.put("ttlSearchHours", config.getRedis().getTtl().getSearchHours());
        stats.put("ttlPlaylistHours", config.getRedis().getTtl().getPlaylistHours());
        stats.put("keyPrefix", config.getRedis().getKeyPrefix());

        try {
            if (isRedisAvailable()) {
                RedisCommands<String, String> commands = getCommands();
                if (commands != null) {
                    List<String> keys = commands.keys(config.getRedis().getKeyPrefix() + "*");
                    stats.put("totalCachedKeys", keys != null ? keys.size() : 0);
                } else {
                    stats.put("totalCachedKeys", "unavailable");
                }
            } else {
                stats.put("totalCachedKeys", "unavailable");
            }
        } catch (Exception e) {
            stats.put("totalCachedKeys", "error: " + e.getMessage());
        }

        return stats;
    }

    public Duration getTtlDuration(String loadType) {
        TrackCacheConfig.RedisConfig.TtlConfig ttl = config.getRedis().getTtl();
        if (!ttl.isEnabled()) {
            return null; // TTL disabled: persistent in Redis
        }

        if ("search".equalsIgnoreCase(loadType)) {
            long h = ttl.getSearchHours();
            return h > 0 ? Duration.ofHours(h) : null;
        }

        if ("playlist".equalsIgnoreCase(loadType)) {
            long h = ttl.getPlaylistHours();
            return h > 0 ? Duration.ofHours(h) : null;
        }

        // Default or "track"
        long h = ttl.getTrackHours();
        return h > 0 ? Duration.ofHours(h) : null;
    }

    public Duration getTtlDuration() {
        return getTtlDuration("track");
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

    public void sanitizeLoadResultNode(ObjectNode objectNode) {
        if (objectNode == null) return;

        // 1. Normalize loadType to Lavalink v4 lowercase specification
        if (objectNode.has("loadType")) {
            String rawType = objectNode.get("loadType").asText();
            if ("NONE".equalsIgnoreCase(rawType)) {
                objectNode.put("loadType", "empty");
            } else {
                objectNode.put("loadType", rawType.toLowerCase(Locale.ROOT));
            }
        }

        String loadType = objectNode.has("loadType") ? objectNode.get("loadType").asText() : "";

        // 2. Normalize payload structure according to Lavalink v4
        if ("search".equals(loadType)) {
            // Lavalink v4 requires search 'data' to be an array of tracks: Track[]
            if (objectNode.has("data")) {
                JsonNode dataNode = objectNode.get("data");
                if (dataNode.isObject() && dataNode.has("tracks") && dataNode.get("tracks").isArray()) {
                    ArrayNode tracksArray = (ArrayNode) dataNode.get("tracks");
                    for (JsonNode track : tracksArray) {
                        sanitizeTrackNode(track);
                    }
                    objectNode.set("data", tracksArray);
                } else if (dataNode.isArray()) {
                    for (JsonNode track : dataNode) {
                        sanitizeTrackNode(track);
                    }
                }
            } else {
                objectNode.set("data", objectMapper.createArrayNode());
            }
        } else if ("track".equals(loadType)) {
            // Lavalink v4 requires track 'data' to be a single Track object
            if (objectNode.has("data")) {
                sanitizeTrackNode(objectNode.get("data"));
            }
        } else if ("playlist".equals(loadType)) {
            // Lavalink v4 requires playlist 'data' to be: { info: {...}, pluginInfo: {...}, tracks: Track[] }
            if (objectNode.has("data") && objectNode.get("data").isObject()) {
                ObjectNode playlistData = (ObjectNode) objectNode.get("data");
                if (!playlistData.has("pluginInfo") || playlistData.get("pluginInfo").isNull()) {
                    playlistData.set("pluginInfo", objectMapper.createObjectNode());
                }
                if (playlistData.has("tracks") && playlistData.get("tracks").isArray()) {
                    for (JsonNode track : playlistData.get("tracks")) {
                        sanitizeTrackNode(track);
                    }
                }
            }
        } else if ("empty".equals(loadType)) {
            // Lavalink v4 requires empty 'data' to be an empty object {}
            if (!objectNode.has("data") || objectNode.get("data").isNull()) {
                objectNode.set("data", objectMapper.createObjectNode());
            }
        }
    }

    public void sanitizeTrackNode(JsonNode trackNode) {
        if (trackNode == null || !trackNode.isObject()) {
            return;
        }
        ObjectNode track = (ObjectNode) trackNode;

        // Lavalink v4 TrackInfo specification
        if (track.has("info") && track.get("info").isObject()) {
            ObjectNode info = (ObjectNode) track.get("info");

            if (info.has("seekable")) {
                if (!info.has("isSeekable")) {
                    info.set("isSeekable", info.get("seekable"));
                }
                info.remove("seekable");
            }

            if (info.has("stream")) {
                if (!info.has("isStream")) {
                    info.set("isStream", info.get("stream"));
                }
                info.remove("stream");
            }

            if (!info.has("isSeekable")) {
                boolean isStream = info.has("isStream") && info.get("isStream").asBoolean(false);
                info.put("isSeekable", !isStream);
            }

            if (!info.has("isStream")) {
                info.put("isStream", false);
            }

            if (!info.has("position")) {
                info.put("position", 0);
            }
        }

        // Lavalink v4 requires pluginInfo as ObjectNode {}
        if (!track.has("pluginInfo") || track.get("pluginInfo").isNull()) {
            track.set("pluginInfo", objectMapper.createObjectNode());
        }

        // Lavalink v4 requires userData as ObjectNode {}
        if (!track.has("userData") || track.get("userData").isNull()) {
            track.set("userData", objectMapper.createObjectNode());
        }
    }

    @Override
    public void close() {
        if (connection != null && connection.isOpen()) {
            try {
                connection.close();
            } catch (Exception ignored) {
            }
        }
    }
}
