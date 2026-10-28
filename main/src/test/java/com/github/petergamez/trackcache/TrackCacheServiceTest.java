package com.github.petergamez.trackcache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TrackCacheServiceTest {

    private TrackCacheService trackCacheService;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        TrackCacheConfig config = new TrackCacheConfig();
        UrlNormalizer normalizer = new UrlNormalizer();
        objectMapper = new ObjectMapper();
        trackCacheService = new TrackCacheService(config, normalizer, objectMapper, null);
    }

    @Test
    void testIsTrackNodeCacheable() {
        // 1. Regular YouTube track -> cacheable
        ObjectNode ytTrack = objectMapper.createObjectNode();
        ObjectNode ytInfo = ytTrack.putObject("info");
        ytInfo.put("identifier", "dQw4w9WgXcQ");
        ytInfo.put("author", "Rick Astley");
        ytInfo.put("length", 212000);
        ytInfo.put("isStream", false);
        ytInfo.put("title", "Never Gonna Give You Up");
        ytInfo.put("uri", "https://www.youtube.com/watch?v=dQw4w9WgXcQ");
        ytInfo.put("sourceName", "youtube");
        assertTrue(trackCacheService.isTrackNodeCacheable(ytTrack));

        // 2. HTTP source track -> NOT cacheable
        ObjectNode httpTrack = objectMapper.createObjectNode();
        ObjectNode httpInfo = httpTrack.putObject("info");
        httpInfo.put("identifier", "https://example.com/audio.mp3");
        httpInfo.put("isStream", false);
        httpInfo.put("uri", "https://example.com/audio.mp3");
        httpInfo.put("sourceName", "http");
        assertFalse(trackCacheService.isTrackNodeCacheable(httpTrack));

        // 3. Local source track -> NOT cacheable
        ObjectNode localTrack = objectMapper.createObjectNode();
        ObjectNode localInfo = localTrack.putObject("info");
        localInfo.put("sourceName", "local");
        localInfo.put("isStream", false);
        assertFalse(trackCacheService.isTrackNodeCacheable(localTrack));

        // 4. Stream track (radio, live stream) -> NOT cacheable
        ObjectNode streamTrack = objectMapper.createObjectNode();
        ObjectNode streamInfo = streamTrack.putObject("info");
        streamInfo.put("sourceName", "youtube");
        streamInfo.put("isStream", true);
        streamInfo.put("uri", "https://www.youtube.com/watch?v=dQw4w9WgXcQ");
        assertFalse(trackCacheService.isTrackNodeCacheable(streamTrack));

        // 5. Generic HTTP URI -> NOT cacheable
        ObjectNode customTrack = objectMapper.createObjectNode();
        ObjectNode customInfo = customTrack.putObject("info");
        customInfo.put("sourceName", "custom");
        customInfo.put("isStream", false);
        customInfo.put("uri", "https://cdn.discordapp.com/attachments/123/456/audio.mp3");
        assertFalse(trackCacheService.isTrackNodeCacheable(customTrack));
    }

    @Test
    void testIsCacheableIdentifier() {
        assertFalse(trackCacheService.isCacheable("http://example.com/stream.mp3"));
        assertFalse(trackCacheService.isCacheable("https://radio.example.org/live"));
        assertFalse(trackCacheService.isCacheable("youtube:http://example.com"));
        assertFalse(trackCacheService.isCacheable("https://www.youtube.com/@channel"));

        assertTrue(trackCacheService.isCacheable("https://www.youtube.com/watch?v=dQw4w9WgXcQ"));
        assertTrue(trackCacheService.isCacheable("ytsearch:rick astley"));
        assertTrue(trackCacheService.isCacheable("youtube:track:dQw4w9WgXcQ"));
    }

    @Test
    void testGetCachedResultUncacheable() {
        assertNull(trackCacheService.getCachedResult("http://example.com/stream.mp3"));
        assertNull(trackCacheService.getCachedResult("youtube:http://example.com"));
        assertNull(trackCacheService.getCachedResult("https://www.youtube.com/@channel"));
        assertNull(trackCacheService.getCachedResult(null));
        assertNull(trackCacheService.getCachedResult("   "));

        // Uncacheable URLs must not pollute cache miss metrics
        assertEquals(0L, trackCacheService.getStats().get("misses"));
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

        trackCacheService.sanitizeLoadResultNode(loadResult);

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
        var stats = trackCacheService.getStats();
        assertNotNull(stats);
        assertEquals(true, stats.get("enabled"));
        assertEquals(false, stats.get("redisHealthy"));
        assertEquals(0L, stats.get("hits"));
        assertEquals(0L, stats.get("misses"));
        assertEquals("unavailable", stats.get("totalCachedKeys"));
    }
}
