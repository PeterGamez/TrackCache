package com.github.petergamez.trackcache;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.petergamez.trackcache.config.TrackCacheConfig;
import com.github.petergamez.trackcache.normalizer.UrlNormalizer;
import com.github.petergamez.trackcache.normalizer.YouTubeNormalizer;
import com.github.petergamez.trackcache.redis.RedisConnectionManager;
import com.github.petergamez.trackcache.redis.TrackCacheCleaner;
import com.github.petergamez.trackcache.util.LoadResultSanitizer;
import com.github.petergamez.trackcache.util.TrackCacheStats;
import com.github.petergamez.trackcache.util.TrackDecomposer;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

@Service
public class TrackCache implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(TrackCache.class);

    private final TrackCacheConfig config;
    private final UrlNormalizer normalizer;
    private final ObjectMapper objectMapper;
    private final LoadResultSanitizer sanitizer;
    private final RedisConnectionManager connectionManager;
    private final TrackCacheStats stats;
    private final TrackDecomposer decomposer;
    private final TrackCacheCleaner cleaner;
    private final AtomicBoolean initialPurgeDone = new AtomicBoolean(false);

    private static final int AVAILABLE_PROCESSORS = Runtime.getRuntime().availableProcessors();
    private static final int CORE_THREADS = Math.max(2, Math.min(4, AVAILABLE_PROCESSORS));
    private static final int MAX_THREADS = Math.max(CORE_THREADS, Math.min(8, Math.max(4, AVAILABLE_PROCESSORS * 2)));

    private final ExecutorService cacheExecutor = new ThreadPoolExecutor(
            CORE_THREADS,
            MAX_THREADS,
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
    public TrackCache(TrackCacheConfig config,
                      UrlNormalizer normalizer,
                      ObjectMapper objectMapper,
                      LoadResultSanitizer sanitizer,
                      RedisConnectionManager connectionManager,
                      TrackCacheStats stats,
                      TrackDecomposer decomposer,
                      TrackCacheCleaner cleaner) {
        this.config = config;
        this.normalizer = normalizer;
        this.objectMapper = objectMapper;
        this.sanitizer = sanitizer;
        this.connectionManager = connectionManager;
        this.stats = stats;
        this.decomposer = decomposer;
        this.cleaner = cleaner;
        this.connectionManager.setOnConnectedCallback(this::schedulePurgeInvalidKeys);
    }

    public TrackCache(TrackCacheConfig config,
                      UrlNormalizer normalizer,
                      ObjectMapper objectMapper,
                      LoadResultSanitizer sanitizer,
                      RedisConnectionManager connectionManager,
                      TrackCacheStats stats) {
        this(
                config,
                normalizer,
                objectMapper,
                sanitizer,
                connectionManager,
                stats,
                new TrackDecomposer(config, normalizer, objectMapper, sanitizer, connectionManager, stats),
                new TrackCacheCleaner(config, normalizer, connectionManager, stats)
        );
    }

    public TrackCache(TrackCacheConfig config,
                      UrlNormalizer normalizer,
                      ObjectMapper objectMapper,
                      LoadResultSanitizer sanitizer,
                      RedisConnectionManager connectionManager) {
        this(config, normalizer, objectMapper, sanitizer, connectionManager, new TrackCacheStats());
    }

    public TrackCache(TrackCacheConfig config,
                      UrlNormalizer normalizer,
                      ObjectMapper objectMapper,
                      @Nullable RedisClient redisClient,
                      @Nullable StatefulRedisConnection<String, String> connection) {
        this(
                config,
                normalizer,
                objectMapper,
                new LoadResultSanitizer(objectMapper, normalizer),
                new RedisConnectionManager(config, redisClient, connection)
        );
    }

    public TrackCache(TrackCacheConfig config,
                      UrlNormalizer normalizer,
                      ObjectMapper objectMapper,
                      @Nullable RedisClient redisClient) {
        this(config, normalizer, objectMapper, redisClient, null);
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
            RedisCommands<String, String> commands = connectionManager.getSyncCommands(cacheExecutor);
            if (commands == null) {
                if (recordStats) {
                    stats.recordMiss();
                }
                return null;
            }

            String cached = commands.get(fullKey);
            connectionManager.markHealthy();

            if (cached != null && !cached.isEmpty()) {
                if (recordStats) {
                    stats.recordHit();
                }
                log.debug("Cache HIT for identifier: '{}' [key: {}]", identifier, fullKey);
                return adjustPlaylistSelectedTrack(identifier, cached);
            } else {
                if (recordStats) {
                    stats.recordMiss();
                }
                log.debug("Cache MISS for identifier: '{}' [key: {}]", identifier, fullKey);
                return null;
            }
        } catch (Exception e) {
            connectionManager.recordError("Error reading from Redis for identifier: " + identifier, e, stats);
            if (recordStats) {
                stats.recordMiss();
            }
            return null;
        }
    }

    public void cacheResult(String identifier, Object responseBody) {
        if (!config.isEnabled() || identifier == null || responseBody == null) {
            return;
        }

        String fullKey = normalizer.toFullCacheKey(identifier, config.getRedis().getKeyPrefix());
        if (fullKey == null || (connectionManager.getAsyncCommands(cacheExecutor) == null && !connectionManager.isHealthy())) {
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

            sanitizer.sanitizeLoadResultNode(objectNode);

            String loadType = objectNode.hasNonNull("loadType") ? objectNode.get("loadType").asText().trim() : "";

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
                if (!sanitizer.isTrackNodeCacheable(dataNode)) {
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
                RedisCommands<String, String> commands = connectionManager.getSyncCommands(cacheExecutor);
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
                connectionManager.markHealthy();
                stats.recordPut();
                stats.invalidateKeyCount();

                String ttlDisplay = ttl != null
                        ? (ttl.toHours() > 0 ? ttl.toHours() + "h" : ttl.toMinutes() + "m")
                        : "persistent";
                log.info("Cached result for: '{}' [loadType: {}, key: {}, ttl: {}]",
                        identifier, loadType, fullKey, ttlDisplay);
            } else {
                log.debug("Skipping caching full {} response for: '{}'", loadType, identifier);
            }

            if (tracksToDecompose != null && !tracksToDecompose.isEmpty()) {
                decomposer.decomposeAndCacheTrackNodes(tracksToDecompose, getTtlDuration("track"), loadType, cacheExecutor);
            }
        } catch (Exception e) {
            connectionManager.recordError("Error writing to Redis for identifier: " + identifier, e, stats);
        }
    }

    public boolean isTrackNodeCacheable(JsonNode trackNode) {
        return sanitizer.isTrackNodeCacheable(trackNode);
    }

    public void sanitizeLoadResultNode(ObjectNode objectNode) {
        sanitizer.sanitizeLoadResultNode(objectNode);
    }

    public void sanitizeTrackNode(JsonNode trackNode) {
        sanitizer.sanitizeTrackNode(trackNode);
    }

    public boolean evict(String identifier) {
        return cleaner.evict(identifier, cacheExecutor);
    }

    public void schedulePurgeInvalidKeys() {
        if (!config.isEnabled() || !initialPurgeDone.compareAndSet(false, true)) {
            return;
        }
        try {
            cacheExecutor.submit(this::purgeInvalidKeys);
        } catch (Exception e) {
            log.debug("Could not schedule invalid keys purge: {}", e.getMessage());
        }
    }

    /**
     * Dynamically adjusts the 'selectedTrack' index in a cached playlist response
     * according to the specific video requested in the input identifier (e.g. YouTube watch?v=...&list=...).
     * If no video ID was specified in the request (e.g. playlist?list=...), selectedTrack is normalized to -1.
     *
     * @param identifier The raw requested identifier or URL
     * @param cachedJson The cached JSON string from Redis
     * @return Adjusted JSON payload or the original cachedJson if no adjustment was needed
     */
    public String adjustPlaylistSelectedTrack(String identifier, String cachedJson) {
        if (identifier == null || cachedJson == null || !cachedJson.contains("\"playlist\"")) {
            return cachedJson;
        }

        try {
            if (!cachedJson.contains("\"selectedTrack\"")) {
                return cachedJson;
            }

            JsonNode rootNode = objectMapper.readTree(cachedJson);
            if (!rootNode.isObject()) {
                return cachedJson;
            }

            JsonNode loadTypeNode = rootNode.get("loadType");
            if (loadTypeNode == null || !"playlist".equalsIgnoreCase(loadTypeNode.asText())) {
                return cachedJson;
            }

            JsonNode dataNode = rootNode.get("data");
            if (dataNode == null || !dataNode.isObject()) {
                return cachedJson;
            }

            JsonNode infoNode = dataNode.get("info");
            if (infoNode == null || !infoNode.isObject()) {
                return cachedJson;
            }

            ObjectNode infoObject = (ObjectNode) infoNode;
            int currentSelectedTrack = infoObject.has("selectedTrack") ? infoObject.get("selectedTrack").asInt(-1) : -1;

            String targetVideoId = YouTubeNormalizer.extractVideoId(identifier);
            int desiredSelectedTrack = -1;

            if (targetVideoId != null) {
                JsonNode tracksNode = dataNode.get("tracks");
                if (tracksNode != null && tracksNode.isArray()) {
                    int idx = 0;
                    for (JsonNode trackNode : tracksNode) {
                        JsonNode trackInfo = trackNode.get("info");
                        if (trackInfo != null && targetVideoId.equals(sanitizer.getNonEmptyText(trackInfo, "identifier"))) {
                            desiredSelectedTrack = idx;
                            break;
                        }
                        idx++;
                    }
                }
            }

            if (currentSelectedTrack == desiredSelectedTrack) {
                return cachedJson;
            }

            infoObject.put("selectedTrack", desiredSelectedTrack);
            return objectMapper.writeValueAsString(rootNode);
        } catch (Exception e) {
            log.debug("Failed to adjust selectedTrack for identifier: {}", identifier, e);
            return cachedJson;
        }
    }

    public long clearKeysMatching(String pattern) {
        return cleaner.clearKeysMatching(pattern, cacheExecutor);
    }

    public long purgeInvalidKeys() {
        return cleaner.purgeInvalidKeys(cacheExecutor);
    }

    public long clearAll() {
        return cleaner.clearAll(cacheExecutor);
    }

    public Map<String, Object> getStats() {
        Map<String, Object> result = stats.createBaseStatsMap(config.isEnabled(), connectionManager.isHealthy());

        try {
            long now = System.currentTimeMillis();
            if (stats.isKeyCountFresh(now)) {
                result.put("totalCachedKeys", stats.getCachedKeyCount());
            } else {
                if (stats.tryAcquireScanLock()) {
                    try {
                        long totalKeys = cleaner.scanKeyCount(config.getRedis().getKeyPrefix() + "*", cacheExecutor);
                        if (totalKeys >= 0) {
                            stats.updateCachedKeyCount(totalKeys);
                            result.put("totalCachedKeys", totalKeys);
                            result.put("redisHealthy", true);
                        } else {
                            result.put("totalCachedKeys", "unavailable");
                        }
                    } finally {
                        stats.releaseScanLock();
                    }
                } else {
                    long cachedCount = stats.getCachedKeyCount();
                    result.put("totalCachedKeys", cachedCount >= 0 ? cachedCount : "scanning");
                }
            }
        } catch (Exception e) {
            connectionManager.recordError("Error scanning key count for stats", e, stats);
            result.put("redisHealthy", false);
            result.put("totalCachedKeys", "error: " + e.getMessage());
        }

        return result;
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
        connectionManager.close();
    }
}
