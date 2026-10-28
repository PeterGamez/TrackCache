package com.github.petergamez.trackcache;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.petergamez.trackcache.config.TrackCacheConfig;
import com.github.petergamez.trackcache.redis.RedisConnectionManager;
import com.github.petergamez.trackcache.util.LoadResultSanitizer;
import com.github.petergamez.trackcache.util.TrackCacheStats;
import com.github.petergamez.trackcache.util.UrlNormalizer;
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
    private final TrackCacheStats stats = new TrackCacheStats();

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
    public TrackCache(TrackCacheConfig config,
                      UrlNormalizer normalizer,
                      ObjectMapper objectMapper,
                      LoadResultSanitizer sanitizer,
                      RedisConnectionManager connectionManager) {
        this.config = config;
        this.normalizer = normalizer;
        this.objectMapper = objectMapper;
        this.sanitizer = sanitizer;
        this.connectionManager = connectionManager;
        this.connectionManager.setOnConnectedCallback(this::schedulePurgeInvalidKeys);
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
                return cached;
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
                decomposeAndCacheTrackNodes(tracksToDecompose, getTtlDuration("track"), loadType);
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

    private void decomposeAndCacheTrackNodes(Iterable<JsonNode> tracksNode, Duration ttl, String source) {
        try {
            RedisAsyncCommands<String, String> async = connectionManager.getAsyncCommands(cacheExecutor);
            if (async == null) {
                return;
            }

            List<RedisFuture<String>> futures = new ArrayList<>();
            Set<String> seenKeys = new HashSet<>();
            String keyPrefix = config.getRedis().getKeyPrefix();

            for (JsonNode trackNode : tracksNode) {
                if (!sanitizer.isTrackNodeCacheable(trackNode)) {
                    continue;
                }

                JsonNode infoNode = trackNode.get("info");
                String uri = sanitizer.getNonEmptyText(infoNode, "uri");
                String trackId = sanitizer.getNonEmptyText(infoNode, "identifier");
                String sourceName = sanitizer.getNonEmptyText(infoNode, "sourceName");

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
                    connectionManager.markHealthy();
                    stats.recordPuts(futures.size());
                    stats.invalidateKeyCount();
                    log.debug("Pipelined and cached {} individual tracks from {}.", futures.size(), source);
                } else {
                    log.warn("Timed out waiting for all decomposed track writes from {}.", source);
                }
            }
        } catch (Exception e) {
            connectionManager.recordError("Failed to decompose tracks from " + source, e, stats);
        }
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
        stats.invalidateKeyCount();
        return deleted != null ? deleted : 0;
    }

    public boolean evict(String identifier) {
        if (identifier == null || identifier.trim().isEmpty()) return false;
        try {
            RedisCommands<String, String> commands = connectionManager.getSyncCommands(cacheExecutor);
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
            connectionManager.markHealthy();
            return deleted > 0;
        } catch (Exception e) {
            connectionManager.recordError("Error evicting key for: " + identifier, e, stats);
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
            RedisCommands<String, String> commands = connectionManager.getSyncCommands(cacheExecutor);
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
            connectionManager.markHealthy();
            return totalDeleted;
        } catch (Exception e) {
            connectionManager.recordError("Error clearing keys matching: " + pattern, e, stats);
            return 0;
        }
    }

    public long purgeInvalidKeys() {
        try {
            RedisCommands<String, String> commands = connectionManager.getSyncCommands(cacheExecutor);
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

            connectionManager.markHealthy();
            if (totalDeleted > 0) {
                log.info("Purged {} invalid/legacy HTTP and uncacheable keys from Redis in a single scan pass.", totalDeleted);
            }
            return totalDeleted;
        } catch (Exception e) {
            connectionManager.recordError("Error purging invalid keys from Redis", e, stats);
            return 0;
        }
    }

    public long clearAll() {
        return clearKeysMatching(config.getRedis().getKeyPrefix() + "*");
    }

    public Map<String, Object> getStats() {
        Map<String, Object> result = stats.createBaseStatsMap(config.isEnabled(), connectionManager.isHealthy());

        try {
            long now = System.currentTimeMillis();
            if (stats.isKeyCountFresh(now)) {
                result.put("totalCachedKeys", stats.getCachedKeyCount());
            } else {
                RedisCommands<String, String> commands = connectionManager.getSyncCommands(cacheExecutor);
                if (commands != null) {
                    if (stats.tryAcquireScanLock()) {
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
                            connectionManager.markHealthy();
                            stats.updateCachedKeyCount(totalKeys);
                            result.put("totalCachedKeys", totalKeys);
                            result.put("redisHealthy", true);
                        } finally {
                            stats.releaseScanLock();
                        }
                    } else {
                        long cachedCount = stats.getCachedKeyCount();
                        result.put("totalCachedKeys", cachedCount >= 0 ? cachedCount : "scanning");
                    }
                } else {
                    result.put("totalCachedKeys", "unavailable");
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

