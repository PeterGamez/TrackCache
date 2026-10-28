package com.github.petergamez.trackcache;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.lettuce.core.KeyScanCursor;
import io.lettuce.core.LettuceFutures;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisFuture;
import io.lettuce.core.ScanArgs;
import io.lettuce.core.ScanCursor;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import io.lettuce.core.api.sync.RedisCommands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

@Service
public class TrackCacheService implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(TrackCacheService.class);

    private final TrackCacheConfig config;
    private final UrlNormalizer normalizer;
    private final ObjectMapper objectMapper;
    private final RedisClient redisClient;
    private volatile StatefulRedisConnection<String, String> connection;
    private volatile RedisCommands<String, String> syncCommands;
    private volatile RedisAsyncCommands<String, String> asyncCommands;

    private final AtomicLong hits = new AtomicLong(0);
    private final AtomicLong misses = new AtomicLong(0);
    private final AtomicLong errors = new AtomicLong(0);
    private final AtomicLong puts = new AtomicLong(0);

    private volatile boolean redisHealthy;
    private final AtomicLong lastErrorTime = new AtomicLong(0);
    private final AtomicBoolean reconnectInProgress = new AtomicBoolean(false);
    private static final long HEALTH_CHECK_COOLDOWN_MS = 10_000;

    private volatile long cachedKeyCount = -1;
    private volatile long lastKeyCountScanTime = 0;
    private final AtomicBoolean keyCountScanInProgress = new AtomicBoolean(false);
    private static final long KEY_COUNT_CACHE_TTL_MS = 15_000;

    private final ExecutorService cacheExecutor = new ThreadPoolExecutor(
            2,
            Math.max(4, Runtime.getRuntime().availableProcessors()),
            60L,
            TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(2000),
            new ThreadFactory() {
                private final AtomicInteger counter = new AtomicInteger(1);
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "trackcache-async-" + counter.getAndIncrement());
                    t.setDaemon(true);
                    return t;
                }
            },
            new ThreadPoolExecutor.DiscardOldestPolicy()
    );

    @Autowired
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
        if (connection != null && connection.isOpen()) {
            this.syncCommands = connection.sync();
            this.asyncCommands = connection.async();
            this.redisHealthy = true;
            schedulePurgeInvalidKeys();
        } else {
            this.redisHealthy = false;
            this.lastErrorTime.set(System.currentTimeMillis());
        }
    }

    public TrackCacheService(TrackCacheConfig config,
                             UrlNormalizer normalizer,
                             ObjectMapper objectMapper,
                             @Nullable RedisClient redisClient) {
        this(config, normalizer, objectMapper, redisClient, null);
    }

    private boolean isConnectionReady() {
        if (!config.isEnabled()) {
            return false;
        }
        StatefulRedisConnection<String, String> conn = this.connection;
        if (conn != null && conn.isOpen() && this.syncCommands != null && this.asyncCommands != null) {
            return redisHealthy || allowHealthProbe();
        }
        if (redisClient != null) {
            triggerAsyncReconnectIfReady();
        }
        return false;
    }

    private RedisCommands<String, String> getCommands() {
        return isConnectionReady() ? this.syncCommands : null;
    }

    private RedisAsyncCommands<String, String> getAsyncCommands() {
        return isConnectionReady() ? this.asyncCommands : null;
    }

    private boolean allowHealthProbe() {
        long now = System.currentTimeMillis();
        long lastErr = lastErrorTime.get();
        return (now - lastErr > HEALTH_CHECK_COOLDOWN_MS) && lastErrorTime.compareAndSet(lastErr, now);
    }

    private void triggerAsyncReconnectIfReady() {
        long now = System.currentTimeMillis();
        long lastErr = lastErrorTime.get();
        if (now - lastErr > HEALTH_CHECK_COOLDOWN_MS && reconnectInProgress.compareAndSet(false, true)) {
            lastErrorTime.set(now);
            try {
                cacheExecutor.submit(this::reconnectInBackground);
            } catch (Exception e) {
                reconnectInProgress.set(false);
            }
        }
    }

    private void reconnectInBackground() {
        try {
            synchronized (this) {
                if (this.connection != null && this.connection.isOpen() && this.syncCommands != null) {
                    markRedisHealthy();
                    return;
                }
                if (this.redisClient == null || !config.isEnabled()) {
                    return;
                }
                StatefulRedisConnection<String, String> newConn = this.redisClient.connect();
                this.connection = newConn;
                this.syncCommands = newConn.sync();
                this.asyncCommands = newConn.async();
                markRedisHealthy();
                schedulePurgeInvalidKeys();
            }
        } catch (Exception e) {
            handleRedisError("Failed to reconnect to Redis in background", e);
        } finally {
            reconnectInProgress.set(false);
        }
    }

    private void markRedisHealthy() {
        if (!redisHealthy) {
            redisHealthy = true;
            log.info("Redis connection is healthy and active.");
        }
    }

    private void invalidateKeyCountCache() {
        this.cachedKeyCount = -1;
        this.lastKeyCountScanTime = 0;
    }

    public boolean isEnabled() {
        return config.isEnabled();
    }

    public boolean isCacheable(String identifier) {
        return normalizer.isCacheable(identifier);
    }

    public String getCachedResult(String identifier) {
        return getCachedResultInternal(identifier, true);
    }

    public String peekCachedResult(String identifier) {
        return getCachedResultInternal(identifier, false);
    }

    private String getCachedResultInternal(String identifier, boolean recordStats) {
        if (!config.isEnabled() || identifier == null || identifier.trim().isEmpty()) {
            return null;
        }

        String fullKey = normalizer.toFullCacheKey(identifier, config.getRedis().getKeyPrefix());
        if (fullKey == null) {
            return null;
        }

        try {
            RedisCommands<String, String> commands = getCommands();
            if (commands == null) {
                if (recordStats) {
                    misses.incrementAndGet();
                }
                return null;
            }

            String cached = commands.get(fullKey);
            markRedisHealthy();

            if (cached != null && !cached.isEmpty()) {
                if (recordStats) {
                    hits.incrementAndGet();
                }
                log.debug("Cache HIT for identifier: '{}' [key: {}]", identifier, fullKey);
                return cached;
            } else {
                if (recordStats) {
                    misses.incrementAndGet();
                }
                log.debug("Cache MISS for identifier: '{}' [key: {}]", identifier, fullKey);
                return null;
            }
        } catch (Exception e) {
            handleRedisError("Error reading from Redis for identifier: " + identifier, e);
            if (recordStats) {
                misses.incrementAndGet();
            }
            return null;
        }
    }

    public void cacheResult(String identifier, Object responseBody) {
        if (!config.isEnabled() || identifier == null || responseBody == null) {
            return;
        }

        String fullKey = normalizer.toFullCacheKey(identifier, config.getRedis().getKeyPrefix());
        if (fullKey == null || (getAsyncCommands() == null && !redisHealthy)) {
            return;
        }

        try {
            cacheExecutor.submit(() -> doCacheResult(fullKey, identifier, responseBody));
        } catch (RejectedExecutionException e) {
            log.warn("TrackCache async worker rejected caching task for: {}", identifier);
        }
    }

    private void doCacheResult(String fullKey, String identifier, Object responseBody) {
        try {
            Object resolvedBody = responseBody instanceof Supplier<?> supplier ? supplier.get() : responseBody;
            if (resolvedBody == null) {
                return;
            }

            JsonNode rootNode;
            if (resolvedBody instanceof String jsonStr) {
                rootNode = objectMapper.readTree(jsonStr);
            } else if (resolvedBody instanceof JsonNode jsonNode) {
                rootNode = jsonNode.deepCopy();
            } else {
                rootNode = objectMapper.valueToTree(resolvedBody);
            }

            if (!(rootNode instanceof ObjectNode objectNode)) {
                return;
            }

            sanitizeLoadResultNode(objectNode);

            String loadType = objectNode.hasNonNull("loadType") ? objectNode.get("loadType").asText().trim() : "";

            // Strict whitelist of Lavalink v4 loadType values
            if (!"track".equals(loadType)
                    && !"playlist".equals(loadType)
                    && !"search".equals(loadType)
                    && !"empty".equals(loadType)) {
                log.debug("Skipping caching response with non-cacheable loadType '{}' for: '{}'", loadType, identifier);
                return;
            }

            if ("empty".equals(loadType) && !config.getCache().isEmpty()) {
                log.debug("Skipping caching empty response for: '{}'", identifier);
                return;
            }

            JsonNode dataNode = objectNode.get("data");
            JsonNode tracksToDecompose = null;
            boolean shouldCacheFull = true;

            if ("track".equals(loadType)) {
                if (!isTrackNodeCacheable(dataNode)) {
                    log.debug("Skipping caching track with uncacheable attributes (HTTP source or isStream) for: '{}'", identifier);
                    return;
                }
            } else if ("playlist".equals(loadType)) {
                if (dataNode == null || !dataNode.isObject()
                        || !dataNode.has("tracks") || !dataNode.get("tracks").isArray()
                        || dataNode.get("tracks").isEmpty()) {
                    log.debug("Skipping caching empty or malformed playlist response for: '{}'", identifier);
                    return;
                }
                shouldCacheFull = config.getCache().isPlaylists();
                if (config.getCache().isIndividualPlaylistTracks()) {
                    tracksToDecompose = dataNode.get("tracks");
                }
            } else if ("search".equals(loadType)) {
                if (dataNode == null || !dataNode.isArray()) {
                    log.debug("Skipping caching malformed search response for: '{}'", identifier);
                    return;
                }
                if (dataNode.isEmpty() && !config.getCache().isEmpty()) {
                    log.debug("Skipping caching empty search array response for: '{}'", identifier);
                    return;
                }
                shouldCacheFull = config.getCache().isSearches();
                if (config.getCache().isIndividualSearchTracks()) {
                    tracksToDecompose = dataNode;
                }
            }

            if (shouldCacheFull) {
                RedisCommands<String, String> commands = getCommands();
                if (commands == null) {
                    return;
                }

                Duration ttl = getTtlDuration(loadType);
                String json = objectMapper.writeValueAsString(objectNode);

                if (ttl != null) {
                    commands.setex(fullKey, ttl.getSeconds(), json);
                } else {
                    commands.set(fullKey, json);
                }
                markRedisHealthy();
                puts.incrementAndGet();
                invalidateKeyCountCache();

                String ttlDisplay = ttl != null
                        ? (ttl.toHours() > 0 ? ttl.toHours() + "h" : ttl.toMinutes() + "m")
                        : "persistent";
                log.info("Cached result for: '{}' [loadType: {}, key: {}, ttl: {}]",
                        identifier, loadType, fullKey, ttlDisplay);
            } else {
                log.debug("Skipping caching full {} response for: '{}'", loadType, identifier);
            }

            if (tracksToDecompose != null && !tracksToDecompose.isEmpty()) {
                decomposeAndCacheTrackNodes(tracksToDecompose, getTtlDuration("track"), loadType);
            }
        } catch (Exception e) {
            handleRedisError("Error writing to Redis for identifier: " + identifier, e);
        }
    }

    public boolean isTrackNodeCacheable(JsonNode trackNode) {
        if (trackNode == null || !trackNode.isObject()) {
            return false;
        }
        JsonNode infoNode = trackNode.get("info");
        if (infoNode == null || !infoNode.isObject()) {
            return false;
        }

        // Live streams must never be cached
        if (infoNode.hasNonNull("isStream") && infoNode.get("isStream").asBoolean(false)) {
            return false;
        }

        // Direct HTTP or local source tracks must never be cached
        String sourceName = infoNode.hasNonNull("sourceName") ? infoNode.get("sourceName").asText().trim() : "";
        if ("http".equalsIgnoreCase(sourceName) || "local".equalsIgnoreCase(sourceName)) {
            return false;
        }

        // Validate URI: reject generic HTTP/HTTPS URLs
        String uri = infoNode.hasNonNull("uri") ? infoNode.get("uri").asText().trim() : null;
        if (uri != null && !uri.isEmpty() && !"null".equalsIgnoreCase(uri)) {
            if ((uri.startsWith("http://") || uri.startsWith("https://")) && !normalizer.isCacheable(uri)) {
                return false;
            }
        }

        return true;
    }

    private void decomposeAndCacheTrackNodes(Iterable<JsonNode> tracksNode, Duration ttl, String source) {
        try {
            RedisAsyncCommands<String, String> async = getAsyncCommands();
            if (async == null) {
                return;
            }

            List<RedisFuture<String>> futures = new ArrayList<>();
            Set<String> seenKeys = new HashSet<>();
            String keyPrefix = config.getRedis().getKeyPrefix();

            for (JsonNode trackNode : tracksNode) {
                if (!isTrackNodeCacheable(trackNode)) {
                    continue;
                }

                JsonNode infoNode = trackNode.get("info");
                String uri = getNonEmptyText(infoNode, "uri");
                String trackId = getNonEmptyText(infoNode, "identifier");
                String sourceName = getNonEmptyText(infoNode, "sourceName");

                String trackCanonicalKey = uri != null ? normalizer.normalize(uri) : null;
                if ((trackCanonicalKey == null || trackCanonicalKey.isEmpty())
                        && trackId != null && !trackId.startsWith("http://") && !trackId.startsWith("https://")) {
                    String platform = sourceName != null ? sourceName.toLowerCase(Locale.ROOT) : "track";
                    trackCanonicalKey = platform + ":track:" + trackId;
                }

                if (trackCanonicalKey == null || !normalizer.isCacheableKey(trackCanonicalKey)) {
                    continue;
                }

                String fullTrackKey = keyPrefix + trackCanonicalKey;
                if (!seenKeys.add(fullTrackKey)) {
                    continue;
                }

                ObjectNode singleTrackResult = objectMapper.createObjectNode();
                singleTrackResult.put("loadType", "track");
                singleTrackResult.set("data", trackNode);

                String singleTrackJson = objectMapper.writeValueAsString(singleTrackResult);

                if (ttl != null) {
                    futures.add(async.setex(fullTrackKey, ttl.getSeconds(), singleTrackJson));
                } else {
                    futures.add(async.set(fullTrackKey, singleTrackJson));
                }
            }

            if (!futures.isEmpty()) {
                boolean completed = LettuceFutures.awaitAll(Duration.ofSeconds(5), futures.toArray(new RedisFuture[0]));
                if (completed) {
                    markRedisHealthy();
                    puts.addAndGet(futures.size());
                    invalidateKeyCountCache();
                    log.debug("Pipelined and cached {} individual tracks from {}.", futures.size(), source);
                } else {
                    log.warn("Timed out waiting for all decomposed track writes from {}.", source);
                }
            }
        } catch (Exception e) {
            handleRedisError("Failed to decompose tracks from " + source, e);
        }
    }

    private String getNonEmptyText(JsonNode node, String field) {
        if (node == null || !node.hasNonNull(field)) {
            return null;
        }
        String text = node.get(field).asText().trim();
        return (text.isEmpty() || "null".equalsIgnoreCase(text)) ? null : text;
    }

    private long deleteKeys(RedisCommands<String, String> commands, String... keys) {
        if (keys == null || keys.length == 0) {
            return 0;
        }
        Long deleted;
        try {
            deleted = commands.unlink(keys);
        } catch (Exception e) {
            deleted = commands.del(keys);
        }
        invalidateKeyCountCache();
        return deleted != null ? deleted : 0;
    }

    public boolean evict(String identifier) {
        if (identifier == null || identifier.trim().isEmpty()) return false;
        try {
            RedisCommands<String, String> commands = getCommands();
            if (commands == null) return false;
            String prefix = config.getRedis().getKeyPrefix();
            String trimmed = identifier.trim();
            String fullKey;
            if (trimmed.startsWith(prefix)) {
                fullKey = trimmed;
            } else {
                String canonical = normalizer.normalize(trimmed);
                fullKey = prefix + (canonical != null && !canonical.isEmpty() ? canonical : trimmed);
            }
            long deleted = deleteKeys(commands, fullKey);
            markRedisHealthy();
            return deleted > 0;
        } catch (Exception e) {
            handleRedisError("Error evicting key for: " + identifier, e);
            return false;
        }
    }

    public void schedulePurgeInvalidKeys() {
        if (!config.isEnabled()) return;
        try {
            cacheExecutor.submit(this::purgeInvalidKeys);
        } catch (Exception e) {
            log.debug("Could not schedule invalid keys purge: {}", e.getMessage());
        }
    }

    public long clearKeysMatching(String pattern) {
        try {
            RedisCommands<String, String> commands = getCommands();
            if (commands == null) return 0;
            ScanCursor cursor = ScanCursor.INITIAL;
            ScanArgs scanArgs = ScanArgs.Builder.matches(pattern).limit(500);
            long totalDeleted = 0;
            do {
                KeyScanCursor<String> scanResult = commands.scan(cursor, scanArgs);
                List<String> keys = scanResult.getKeys();
                if (keys != null && !keys.isEmpty()) {
                    totalDeleted += deleteKeys(commands, keys.toArray(new String[0]));
                }
                cursor = scanResult;
            } while (!cursor.isFinished());
            markRedisHealthy();
            return totalDeleted;
        } catch (Exception e) {
            handleRedisError("Error clearing keys matching: " + pattern, e);
            return 0;
        }
    }

    public long purgeInvalidKeys() {
        try {
            RedisCommands<String, String> commands = getCommands();
            if (commands == null) return 0;

            String prefix = config.getRedis().getKeyPrefix();
            ScanCursor cursor = ScanCursor.INITIAL;
            ScanArgs scanArgs = ScanArgs.Builder.matches(prefix + "*").limit(500);

            long totalDeleted = 0;
            List<String> batchToDelete = new ArrayList<>(100);

            do {
                KeyScanCursor<String> scanResult = commands.scan(cursor, scanArgs);
                List<String> keys = scanResult.getKeys();
                if (keys != null) {
                    for (String key : keys) {
                        String strippedKey = key.startsWith(prefix) ? key.substring(prefix.length()) : key;
                        if (!normalizer.isCacheableKey(strippedKey)) {
                            batchToDelete.add(key);
                            if (batchToDelete.size() >= 100) {
                                totalDeleted += deleteKeys(commands, batchToDelete.toArray(new String[0]));
                                batchToDelete.clear();
                            }
                        }
                    }
                }
                cursor = scanResult;
            } while (!cursor.isFinished());

            if (!batchToDelete.isEmpty()) {
                totalDeleted += deleteKeys(commands, batchToDelete.toArray(new String[0]));
            }

            markRedisHealthy();
            if (totalDeleted > 0) {
                log.info("Purged {} invalid/legacy HTTP and uncacheable keys from Redis in a single scan pass.", totalDeleted);
            }
            return totalDeleted;
        } catch (Exception e) {
            handleRedisError("Error purging invalid keys from Redis", e);
            return 0;
        }
    }

    public long clearAll() {
        return clearKeysMatching(config.getRedis().getKeyPrefix() + "*");
    }

    public Map<String, Object> getStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        long h = hits.get();
        long m = misses.get();
        long totalRequests = h + m;
        double hitRatio = totalRequests > 0 ? ((double) h / totalRequests) * 100.0 : 0.0;

        boolean connOpen = connection != null && connection.isOpen();
        stats.put("enabled", config.isEnabled());
        stats.put("redisHealthy", redisHealthy && connOpen);
        stats.put("hits", h);
        stats.put("misses", m);
        stats.put("puts", puts.get());
        stats.put("errors", errors.get());
        stats.put("hitRatioPercent", Math.round(hitRatio * 100.0) / 100.0);

        try {
            long now = System.currentTimeMillis();
            if (cachedKeyCount >= 0 && (now - lastKeyCountScanTime) < KEY_COUNT_CACHE_TTL_MS) {
                stats.put("totalCachedKeys", cachedKeyCount);
            } else {
                RedisCommands<String, String> commands = getCommands();
                if (commands != null) {
                    if (keyCountScanInProgress.compareAndSet(false, true)) {
                        try {
                            String pattern = config.getRedis().getKeyPrefix() + "*";
                            ScanCursor cursor = ScanCursor.INITIAL;
                            ScanArgs scanArgs = ScanArgs.Builder.matches(pattern).limit(1000);
                            long totalKeys = 0;
                            do {
                                KeyScanCursor<String> scanResult = commands.scan(cursor, scanArgs);
                                totalKeys += scanResult.getKeys().size();
                                cursor = scanResult;
                            } while (!cursor.isFinished());
                            markRedisHealthy();
                            cachedKeyCount = totalKeys;
                            lastKeyCountScanTime = System.currentTimeMillis();
                            stats.put("totalCachedKeys", totalKeys);
                            stats.put("redisHealthy", true);
                        } finally {
                            keyCountScanInProgress.set(false);
                        }
                    } else {
                        stats.put("totalCachedKeys", cachedKeyCount >= 0 ? cachedKeyCount : "scanning");
                    }
                } else {
                    stats.put("totalCachedKeys", "unavailable");
                }
            }
        } catch (Exception e) {
            handleRedisError("Error scanning key count for stats", e);
            stats.put("redisHealthy", false);
            stats.put("totalCachedKeys", "error: " + e.getMessage());
        }

        return stats;
    }

    public Duration getTtlDuration(String loadType) {
        TrackCacheConfig.RedisConfig.TtlConfig ttl = config.getRedis().getTtl();
        if (!ttl.isEnabled()) {
            return null;
        }
        long hours;
        if ("search".equalsIgnoreCase(loadType)) {
            hours = ttl.getSearchHours();
        } else if ("playlist".equalsIgnoreCase(loadType)) {
            hours = ttl.getPlaylistHours();
        } else {
            hours = ttl.getTrackHours();
        }
        return hours > 0 ? Duration.ofHours(hours) : null;
    }

    private void handleRedisError(String message, Exception e) {
        errors.incrementAndGet();
        redisHealthy = false;
        lastErrorTime.set(System.currentTimeMillis());
        log.warn("{}: {} (temporarily marking Redis as unavailable for 10s)", message, e.getMessage());
    }

    public void sanitizeLoadResultNode(ObjectNode objectNode) {
        if (objectNode == null) return;

        // 1. Normalize loadType to Lavalink v4 lowercase specification
        String loadType = "";
        if (objectNode.hasNonNull("loadType")) {
            String rawType = objectNode.get("loadType").asText().trim();
            loadType = "NONE".equalsIgnoreCase(rawType) ? "empty" : rawType.toLowerCase(Locale.ROOT);
            objectNode.put("loadType", loadType);
        }

        // 2. Normalize payload structure according to Lavalink v4
        if ("search".equals(loadType)) {
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
            if (objectNode.has("data")) {
                sanitizeTrackNode(objectNode.get("data"));
            }
        } else if ("playlist".equals(loadType)) {
            if (objectNode.has("data") && objectNode.get("data").isObject()) {
                ObjectNode playlistData = (ObjectNode) objectNode.get("data");
                sanitizeJsonObjectField(playlistData, "pluginInfo");
                if (playlistData.has("tracks") && playlistData.get("tracks").isArray()) {
                    for (JsonNode track : playlistData.get("tracks")) {
                        sanitizeTrackNode(track);
                    }
                }
            }
        } else if ("empty".equals(loadType)) {
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

        sanitizeJsonObjectField(track, "pluginInfo");
        sanitizeJsonObjectField(track, "userData");
    }

    private void sanitizeJsonObjectField(ObjectNode parent, String fieldName) {
        if (!parent.has(fieldName) || parent.get(fieldName).isNull()) {
            parent.set(fieldName, objectMapper.createObjectNode());
        } else {
            parent.set(fieldName, sanitizeKotlinJsonElement(parent.get(fieldName)));
        }
    }

    /**
     * Unwraps kotlinx.serialization.json.JsonPrimitive objects that were serialized via Jackson
     * reflection as {"string": boolean, "content": "..."} back into native JSON primitives.
     */
    private JsonNode sanitizeKotlinJsonElement(JsonNode node) {
        if (node == null || node.isNull()) {
            return objectMapper.nullNode();
        }
        if (node instanceof ObjectNode obj) {
            if (obj.size() == 2 && obj.has("string") && obj.has("content")
                    && obj.get("string").isBoolean()
                    && (obj.get("content").isTextual() || obj.get("content").isNull())) {
                boolean isString = obj.get("string").asBoolean();
                String content = obj.get("content").isNull() ? "null" : obj.get("content").asText();
                if (isString) {
                    return objectMapper.getNodeFactory().textNode(content);
                }
                if ("null".equalsIgnoreCase(content)) {
                    return objectMapper.nullNode();
                }
                if ("true".equalsIgnoreCase(content)) {
                    return objectMapper.getNodeFactory().booleanNode(true);
                }
                if ("false".equalsIgnoreCase(content)) {
                    return objectMapper.getNodeFactory().booleanNode(false);
                }
                try {
                    if (content.contains(".") || content.contains("e") || content.contains("E")) {
                        return objectMapper.getNodeFactory().numberNode(Double.parseDouble(content));
                    }
                    return objectMapper.getNodeFactory().numberNode(Long.parseLong(content));
                } catch (NumberFormatException ignored) {
                    return objectMapper.getNodeFactory().textNode(content);
                }
            }
            ObjectNode cleaned = objectMapper.createObjectNode();
            Iterator<Map.Entry<String, JsonNode>> fields = obj.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                cleaned.set(entry.getKey(), sanitizeKotlinJsonElement(entry.getValue()));
            }
            return cleaned;
        }
        if (node instanceof ArrayNode arr) {
            ArrayNode cleaned = objectMapper.createArrayNode();
            for (JsonNode elem : arr) {
                cleaned.add(sanitizeKotlinJsonElement(elem));
            }
            return cleaned;
        }
        return node;
    }

    public TrackCacheConfig getConfig() {
        return config;
    }

    @Override
    public void close() {
        try {
            cacheExecutor.shutdown();
            if (!cacheExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                cacheExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            cacheExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        } catch (Exception ignored) {
        }
        syncCommands = null;
        asyncCommands = null;
        StatefulRedisConnection<String, String> conn = this.connection;
        this.connection = null;
        if (conn != null && conn.isOpen()) {
            try {
                conn.close();
            } catch (Exception ignored) {
            }
        }
    }
}
