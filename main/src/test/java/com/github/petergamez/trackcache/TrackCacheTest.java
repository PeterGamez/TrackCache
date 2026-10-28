package com.github.petergamez.trackcache;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.petergamez.trackcache.config.TrackCacheConfig;
import com.github.petergamez.trackcache.config.TrackCacheConfigUpdater;
import com.github.petergamez.trackcache.normalizer.UrlNormalizer;
import com.github.petergamez.trackcache.redis.RedisConnectionManager;
import com.github.petergamez.trackcache.util.LoadResultSanitizer;
import com.github.petergamez.trackcache.util.TrackCacheStats;
import com.github.petergamez.trackcache.util.TrackDecomposer;
import io.lettuce.core.RedisFuture;
import io.lettuce.core.api.async.RedisAsyncCommands;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class TrackCacheTest {

    private TrackCache trackCache;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        TrackCacheConfig config = new TrackCacheConfig();
        UrlNormalizer normalizer = new UrlNormalizer();
        objectMapper = new ObjectMapper();
        trackCache = new TrackCache(config, normalizer, objectMapper, null);
    }

    @Test
    void testIsTrackNodeCacheable() {
        // 1. Regular YouTube track -> cacheable
        ObjectNode ytTrack = objectMapper.createObjectNode();
        ytTrack.put("encoded", "QAAAbAIACk5ldmVy...");
        ObjectNode ytInfo = ytTrack.putObject("info");
        ytInfo.put("identifier", "dQw4w9WgXcQ");
        ytInfo.put("author", "Rick Astley");
        ytInfo.put("length", 212000);
        ytInfo.put("isStream", false);
        ytInfo.put("title", "Never Gonna Give You Up");
        ytInfo.put("uri", "https://www.youtube.com/watch?v=dQw4w9WgXcQ");
        ytInfo.put("sourceName", "youtube");
        assertTrue(trackCache.isTrackNodeCacheable(ytTrack));

        // 2. HTTP source track -> NOT cacheable
        ObjectNode httpTrack = objectMapper.createObjectNode();
        httpTrack.put("encoded", "QAAAbAIACk5ldmVy...");
        ObjectNode httpInfo = httpTrack.putObject("info");
        httpInfo.put("identifier", "https://example.com/audio.mp3");
        httpInfo.put("isStream", false);
        httpInfo.put("uri", "https://example.com/audio.mp3");
        httpInfo.put("sourceName", "http");
        assertFalse(trackCache.isTrackNodeCacheable(httpTrack));

        // 3. Local source track -> NOT cacheable
        ObjectNode localTrack = objectMapper.createObjectNode();
        localTrack.put("encoded", "QAAAbAIACk5ldmVy...");
        ObjectNode localInfo = localTrack.putObject("info");
        localInfo.put("sourceName", "local");
        localInfo.put("isStream", false);
        assertFalse(trackCache.isTrackNodeCacheable(localTrack));

        // 4. Stream track (radio, live stream) -> NOT cacheable
        ObjectNode streamTrack = objectMapper.createObjectNode();
        streamTrack.put("encoded", "QAAAbAIACk5ldmVy...");
        ObjectNode streamInfo = streamTrack.putObject("info");
        streamInfo.put("sourceName", "youtube");
        streamInfo.put("isStream", true);
        streamInfo.put("uri", "https://www.youtube.com/watch?v=dQw4w9WgXcQ");
        assertFalse(trackCache.isTrackNodeCacheable(streamTrack));

        // 5. Generic HTTP URI -> NOT cacheable
        ObjectNode customTrack = objectMapper.createObjectNode();
        customTrack.put("encoded", "QAAAbAIACk5ldmVy...");
        ObjectNode customInfo = customTrack.putObject("info");
        customInfo.put("sourceName", "custom");
        customInfo.put("isStream", false);
        customInfo.put("uri", "https://cdn.discordapp.com/attachments/123/456/audio.mp3");
        assertFalse(trackCache.isTrackNodeCacheable(customTrack));

        // 6. Missing or empty encoded string -> NOT cacheable
        ObjectNode missingEncodedTrack = ytTrack.deepCopy();
        missingEncodedTrack.remove("encoded");
        assertFalse(trackCache.isTrackNodeCacheable(missingEncodedTrack));

        ObjectNode emptyEncodedTrack = ytTrack.deepCopy();
        emptyEncodedTrack.put("encoded", "   ");
        assertFalse(trackCache.isTrackNodeCacheable(emptyEncodedTrack));
    }

    @Test
    void testIsCacheableIdentifier() {
        assertFalse(trackCache.isCacheable("http://example.com/stream.mp3"));
        assertFalse(trackCache.isCacheable("https://radio.example.org/live"));
        assertFalse(trackCache.isCacheable("youtube:http://example.com"));
        assertFalse(trackCache.isCacheable("https://www.youtube.com/@channel"));

        assertTrue(trackCache.isCacheable("https://www.youtube.com/watch?v=dQw4w9WgXcQ"));
        assertTrue(trackCache.isCacheable("ytsearch:rick astley"));
        assertTrue(trackCache.isCacheable("youtube:track:dQw4w9WgXcQ"));
    }

    @Test
    void testGetCachedResultUncacheable() {
        assertNull(trackCache.getCachedResult("http://example.com/stream.mp3"));
        assertNull(trackCache.getCachedResult("youtube:http://example.com"));
        assertNull(trackCache.getCachedResult("https://www.youtube.com/@channel"));
        assertNull(trackCache.getCachedResult(null));
        assertNull(trackCache.getCachedResult("   "));

        // Uncacheable URLs must not pollute cache miss metrics
        assertEquals(0L, trackCache.getStats().get("misses"));
    }

    @Test
    void testSanitizeKotlinJsonElementInPluginInfo() {
        ObjectNode loadResult = objectMapper.createObjectNode();
        loadResult.put("loadType", "TRACK");
        ObjectNode track = loadResult.putObject("data");
        track.put("encoded", "QAAA...");
        ObjectNode info = track.putObject("info");
        info.put("identifier", "dQw4w9WgXcQ");
        info.put("seekable", true);
        info.put("stream", false);

        // Simulate Jackson reflection output of kotlinx.serialization.json.JsonPrimitive
        ObjectNode pluginInfo = track.putObject("pluginInfo");
        ObjectNode albumName = pluginInfo.putObject("albumName");
        albumName.put("string", true);
        albumName.put("content", "Whenever You Need Somebody");

        ObjectNode isPreview = pluginInfo.putObject("isPreview");
        isPreview.put("string", false);
        isPreview.put("content", "false");

        ObjectNode totalTracks = pluginInfo.putObject("totalTracks");
        totalTracks.put("string", false);
        totalTracks.put("content", "10");

        trackCache.sanitizeLoadResultNode(loadResult);

        assertEquals("track", loadResult.get("loadType").asText());
        assertTrue(info.get("isSeekable").asBoolean());
        assertFalse(info.get("isStream").asBoolean());
        assertFalse(info.has("seekable"));
        assertFalse(info.has("stream"));

        ObjectNode sanitizedPluginInfo = (ObjectNode) track.get("pluginInfo");
        assertEquals("Whenever You Need Somebody", sanitizedPluginInfo.get("albumName").asText());
        assertTrue(sanitizedPluginInfo.get("albumName").isTextual());
        assertFalse(sanitizedPluginInfo.get("isPreview").asBoolean());
        assertTrue(sanitizedPluginInfo.get("isPreview").isBoolean());
        assertEquals(10L, sanitizedPluginInfo.get("totalTracks").asLong());
        assertTrue(sanitizedPluginInfo.get("totalTracks").isNumber());
    }

    @Test
    void testGetStatsWithoutRedis() {
        var stats = trackCache.getStats();
        assertNotNull(stats);
        assertEquals(true, stats.get("enabled"));
        assertEquals(false, stats.get("redisHealthy"));
        assertEquals(0L, stats.get("hits"));
        assertEquals(0L, stats.get("misses"));
        assertEquals("unavailable", stats.get("totalCachedKeys"));
    }

    @Test
    void testTrackCacheConfigUpdater() {
        TrackCacheConfig config = new TrackCacheConfig();

        java.util.Map<String, Object> updates = new java.util.HashMap<>();
        updates.put("enabled", "false");
        updates.put("trackHours", "48");
        updates.put("playlists", false);
        updates.put("searchHours", 6);

        TrackCacheConfigUpdater.applyUpdates(config, updates);
        assertFalse(config.isEnabled());
        assertEquals(48L, config.getRedis().getTtl().getTrackHours());
        assertFalse(config.getCache().isPlaylists());
        assertEquals(6L, config.getRedis().getTtl().getSearchHours());

        assertThrows(IllegalArgumentException.class, () ->
                TrackCacheConfigUpdater.applyUpdates(config, java.util.Collections.singletonMap("trackHours", -5)));
        assertThrows(IllegalArgumentException.class, () ->
                TrackCacheConfigUpdater.applyUpdates(config, java.util.Collections.singletonMap("enabled", "invalid_bool")));
    }

    @Test
    void testTrackCacheStats() {
        TrackCacheStats stats = new TrackCacheStats();
        stats.recordHit();
        stats.recordHit();
        stats.recordMiss();
        stats.recordPut();

        var map = stats.createBaseStatsMap(true, true);
        assertEquals(2L, map.get("hits"));
        assertEquals(1L, map.get("misses"));
        assertEquals(1L, map.get("puts"));
        assertEquals(66.67, map.get("hitRatioPercent"));
    }

    @Test
    void testAdjustPlaylistSelectedTrack() throws Exception {
        // Construct a sample playlist JSON with 2 tracks
        ObjectNode playlist = objectMapper.createObjectNode();
        playlist.put("loadType", "playlist");
        ObjectNode data = playlist.putObject("data");
        ObjectNode info = data.putObject("info");
        info.put("name", "Test Playlist");
        info.put("selectedTrack", 0);

        var tracks = data.putArray("tracks");
        ObjectNode t1 = tracks.addObject();
        t1.put("encoded", "ENC1");
        ObjectNode t1Info = t1.putObject("info");
        t1Info.put("identifier", "dQw4w9WgXcQ");
        t1Info.put("title", "Track 1");

        ObjectNode t2 = tracks.addObject();
        t2.put("encoded", "ENC2");
        ObjectNode t2Info = t2.putObject("info");
        t2Info.put("identifier", "abcdefghijk");
        t2Info.put("title", "Track 2");

        String originalJson = objectMapper.writeValueAsString(playlist);

        // 1. Requested identifier has v= matching track 2 -> selectedTrack should become 1
        String res1 = trackCache.adjustPlaylistSelectedTrack(
                "https://www.youtube.com/watch?v=abcdefghijk&list=PL12345", originalJson);
        var res1Node = objectMapper.readTree(res1);
        assertEquals(1, res1Node.get("data").get("info").get("selectedTrack").asInt());

        // 2. Requested identifier has v= matching track 1 -> selectedTrack should be 0
        String res2 = trackCache.adjustPlaylistSelectedTrack(
                "https://www.youtube.com/watch?v=dQw4w9WgXcQ&list=PL12345", originalJson);
        var res2Node = objectMapper.readTree(res2);
        assertEquals(0, res2Node.get("data").get("info").get("selectedTrack").asInt());

        // 3. Requested identifier is pure playlist URL without v= -> selectedTrack should become -1
        String res3 = trackCache.adjustPlaylistSelectedTrack(
                "https://www.youtube.com/playlist?list=PL12345", originalJson);
        var res3Node = objectMapper.readTree(res3);
        assertEquals(-1, res3Node.get("data").get("info").get("selectedTrack").asInt());

        // 4. Requested identifier has v= not in playlist -> selectedTrack should become -1
        String res4 = trackCache.adjustPlaylistSelectedTrack(
                "https://www.youtube.com/watch?v=notinlist12&list=PL12345", originalJson);
        var res4Node = objectMapper.readTree(res4);
        assertEquals(-1, res4Node.get("data").get("info").get("selectedTrack").asInt());

        // 5. Non-playlist payload -> returned unchanged
        String trackJson = "{\"loadType\":\"track\",\"data\":{}}";
        assertSame(trackJson, trackCache.adjustPlaylistSelectedTrack(
                "https://www.youtube.com/watch?v=abcdefghijk", trackJson));
    }

    @Test
    void testSchedulePurgeInvalidKeysOnlyRunsOnce() {
        // Calling schedulePurgeInvalidKeys multiple times should not throw
        assertDoesNotThrow(() -> {
            trackCache.schedulePurgeInvalidKeys();
            trackCache.schedulePurgeInvalidKeys();
            trackCache.schedulePurgeInvalidKeys();
        });
    }

    @Test
    @SuppressWarnings("unchecked")
    void testMaxDecomposedTracksLimit() {
        TrackCacheConfig config = new TrackCacheConfig();
        UrlNormalizer normalizer = new UrlNormalizer();
        AtomicInteger setCalls = new AtomicInteger(0);

        RedisFuture<String> dummyFuture = (RedisFuture<String>) Proxy.newProxyInstance(
                RedisFuture.class.getClassLoader(),
                new Class<?>[]{RedisFuture.class},
                (proxy, method, args) -> {
                    if ("get".equals(method.getName())) return "OK";
                    if ("isDone".equals(method.getName())) return true;
                    if ("isCancelled".equals(method.getName())) return false;
                    return null;
                }
        );

        RedisAsyncCommands<String, String> mockAsync = (RedisAsyncCommands<String, String>) Proxy.newProxyInstance(
                RedisAsyncCommands.class.getClassLoader(),
                new Class<?>[]{RedisAsyncCommands.class},
                (proxy, method, args) -> {
                    if ("setex".equals(method.getName()) || "set".equals(method.getName())) {
                        setCalls.incrementAndGet();
                        return dummyFuture;
                    }
                    return null;
                }
        );

        RedisConnectionManager mockConnManager = new RedisConnectionManager(config, null, null) {
            @Override
            public RedisAsyncCommands<String, String> getAsyncCommands(ExecutorService reconnectExecutor) {
                return mockAsync;
            }
        };

        TrackDecomposer decomposer = new TrackDecomposer(
                config, normalizer, objectMapper,
                new LoadResultSanitizer(objectMapper, normalizer),
                mockConnManager, new TrackCacheStats()
        );

        // Create 600 unique, valid tracks
        List<JsonNode> tracks = new ArrayList<>();
        for (int i = 0; i < 600; i++) {
            ObjectNode t = objectMapper.createObjectNode();
            t.put("encoded", "ENC_" + i);
            ObjectNode info = t.putObject("info");
            info.put("identifier", "track_" + i);
            info.put("uri", "https://open.spotify.com/track/" + String.format("%022d", i));
            info.put("sourceName", "spotify");
            info.put("isStream", false);
            tracks.add(t);
        }

        // Decompose 600 tracks
        ExecutorService directExecutor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            decomposer.decomposeAndCacheTrackNodes(tracks, Duration.ofHours(1), "test-playlist", directExecutor);
        } finally {
            directExecutor.shutdown();
        }

        assertEquals(TrackDecomposer.MAX_DECOMPOSED_TRACKS_PER_CONTAINER, setCalls.get(),
                "Must not decompose more than the maximum cap of tracks per container");
    }
}
