package com.github.petergamez.trackcache.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.petergamez.trackcache.config.TrackCacheConfig;
import com.github.petergamez.trackcache.redis.RedisConnectionManager;
import io.lettuce.core.LettuceFutures;
import io.lettuce.core.RedisFuture;
import io.lettuce.core.api.async.RedisAsyncCommands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;

/**
 * Handles decomposing playlist and search result payloads into individual cached track items (cache pre-warming).
 */
@Component
public class TrackDecomposer {

    private static final Logger log = LoggerFactory.getLogger(TrackDecomposer.class);

    private final TrackCacheConfig config;
    private final UrlNormalizer normalizer;
    private final ObjectMapper objectMapper;
    private final LoadResultSanitizer sanitizer;
    private final RedisConnectionManager connectionManager;
    private final TrackCacheStats stats;

    public TrackDecomposer(TrackCacheConfig config,
                           UrlNormalizer normalizer,
                           ObjectMapper objectMapper,
                           LoadResultSanitizer sanitizer,
                           RedisConnectionManager connectionManager,
                           TrackCacheStats stats) {
        this.config = config;
        this.normalizer = normalizer;
        this.objectMapper = objectMapper;
        this.sanitizer = sanitizer;
        this.connectionManager = connectionManager;
        this.stats = stats;
    }

    public void decomposeAndCacheTrackNodes(Iterable<JsonNode> tracksNode,
                                           Duration ttl,
                                           String source,
                                           ExecutorService executor) {
        try {
            RedisAsyncCommands<String, String> async = connectionManager.getAsyncCommands(executor);
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
}

