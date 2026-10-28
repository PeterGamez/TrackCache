package com.github.petergamez.trackcache;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class UrlNormalizerTest {

    private UrlNormalizer normalizer;

    @BeforeEach
    void setUp() {
        normalizer = new UrlNormalizer();
    }

    @Test
    void testValidYouTubeUrls() {
        assertEquals("youtube:track:dQw4w9WgXcQ", normalizer.normalize("https://www.youtube.com/watch?v=dQw4w9WgXcQ"));
        assertEquals("youtube:track:dQw4w9WgXcQ", normalizer.normalize("https://youtu.be/dQw4w9WgXcQ"));
        assertEquals("youtube:track:dQw4w9WgXcQ", normalizer.normalize("https://www.youtube.com/shorts/dQw4w9WgXcQ"));
        assertEquals("youtube:track:dQw4w9WgXcQ", normalizer.normalize("https://www.youtube.com/live/dQw4w9WgXcQ"));
        assertEquals("youtube:track:dQw4w9WgXcQ", normalizer.normalize("https://www.youtube.com/embed/dQw4w9WgXcQ"));
        assertEquals("youtube:track:dQw4w9WgXcQ", normalizer.normalize("https://www.youtube.com/v/dQw4w9WgXcQ"));
        assertEquals("youtube:track:dQw4w9WgXcQ", normalizer.normalize("https://www.youtube.com/watch?feature=shared&v=dQw4w9WgXcQ"));
        assertEquals("youtube:playlist:PLrAl54-B8Hk5R", normalizer.normalize("https://www.youtube.com/watch?v=dQw4w9WgXcQ&list=PLrAl54-B8Hk5R"));
        assertEquals("youtube:track:dQw4w9WgXcQ", normalizer.normalize("dQw4w9WgXcQ"));
        assertEquals("youtube:search:rick astley", normalizer.normalize("ytsearch:rick astley"));
    }

    @Test
    void testYouTubePrefixWithUrls() {
        // When client prefixes with youtube:
        assertEquals("youtube:track:dQw4w9WgXcQ", normalizer.normalize("youtube:https://www.youtube.com/watch?v=dQw4w9WgXcQ"));
        assertEquals("youtube:track:dQw4w9WgXcQ", normalizer.normalize("youtube:http://www.youtube.com/watch?v=dQw4w9WgXcQ"));
        assertEquals("youtube:track:dQw4w9WgXcQ", normalizer.normalize("youtube:dQw4w9WgXcQ"));
        assertEquals("youtube:playlist:PLrAl54-B8Hk5R", normalizer.normalize("youtube:playlist:PLrAl54-B8Hk5R"));
        assertEquals("youtube:search:faded", normalizer.normalize("youtube:search:faded"));
    }

    @Test
    void testInvalidYouTubeUrlReturnsNullAndNotCacheable() {
        // Never return youtube:http...
        assertNull(normalizer.normalize("youtube:http://example.com/something"));
        assertNull(normalizer.normalize("youtube:https://www.youtube.com/@somechannel"));
        assertNull(normalizer.normalize("https://www.youtube.com/@somechannel"));
        assertNull(normalizer.normalize("https://www.youtube.com/feed/trending"));
        assertFalse(normalizer.isCacheable("youtube:http://example.com/something"));
        assertFalse(normalizer.isCacheable("https://www.youtube.com/@somechannel"));
    }

    @Test
    void testGenericHttpUrlsNotCacheable() {
        // Direct HTTP/HTTPS audio, radio streams, CDN expiring tokens must NOT be cached
        assertNull(normalizer.normalize("http://example.com/song.mp3"));
        assertNull(normalizer.normalize("https://cdn.discordapp.com/attachments/123/456/audio.mp3"));
        assertNull(normalizer.normalize("http://icecast.radio.org/live"));
        assertNull(normalizer.normalize("https://stream.provider.com/audio?token=expired123"));

        assertFalse(normalizer.isCacheable("http://example.com/song.mp3"));
        assertFalse(normalizer.isCacheable("https://cdn.discordapp.com/attachments/123/456/audio.mp3"));
        assertFalse(normalizer.isCacheable("http://icecast.radio.org/live"));
    }

    @Test
    void testSearchWithHttpUrlReturnsNull() {
        assertNull(normalizer.normalize("ytsearch:http://example.com/audio.mp3"));
        assertNull(normalizer.normalize("scsearch:http://example.com/audio.mp3"));
        assertFalse(normalizer.isCacheable("ytsearch:http://example.com/audio.mp3"));

        // If user accidentally prefixes ytsearch to YouTube URL, unwrap to track
        assertEquals("youtube:track:dQw4w9WgXcQ", normalizer.normalize("ytsearch:https://www.youtube.com/watch?v=dQw4w9WgXcQ"));
    }

    @Test
    void testIsCacheableKeyRejectsInvalidKeys() {
        assertFalse(normalizer.isCacheableKey("youtube:http://www.youtube.com/watch?v=123"));
        assertFalse(normalizer.isCacheableKey("youtube:https://www.youtube.com/watch?v=123"));
        assertFalse(normalizer.isCacheableKey("youtube://www.youtube.com/watch?v=123"));
        assertFalse(normalizer.isCacheableKey("url:http://example.com/audio.mp3"));
        assertFalse(normalizer.isCacheableKey("http:track:http://example.com"));
        assertFalse(normalizer.isCacheableKey("http://example.com"));
        assertFalse(normalizer.isCacheableKey("https://example.com"));
        assertFalse(normalizer.isCacheableKey(null));
        assertFalse(normalizer.isCacheableKey(""));

        // Valid canonical keys
        assertTrue(normalizer.isCacheableKey("youtube:track:dQw4w9WgXcQ"));
        assertTrue(normalizer.isCacheableKey("youtube:playlist:PLrAl54-B8Hk5R"));
        assertTrue(normalizer.isCacheableKey("youtube:search:rick astley"));
        assertTrue(normalizer.isCacheableKey("spotify:track:4cOdK2wGLETKBW3PvgPWqT"));
        assertTrue(normalizer.isCacheableKey("soundcloud:track:user/title"));
    }

    @Test
    void testToFullCacheKeyRejectsInvalidAndGenericHttp() {
        String prefix = "trackcache:";
        assertEquals("trackcache:youtube:track:dQw4w9WgXcQ",
                normalizer.toFullCacheKey("https://www.youtube.com/watch?v=dQw4w9WgXcQ", prefix));

        // Generic HTTP -> null
        assertNull(normalizer.toFullCacheKey("http://example.com/song.mp3", prefix));
        assertNull(normalizer.toFullCacheKey("https://cdn.discordapp.com/sound.mp3", prefix));

        // Invalid YouTube -> null
        assertNull(normalizer.toFullCacheKey("youtube:http://example.com", prefix));
        assertNull(normalizer.toFullCacheKey("trackcache:youtube:http://example.com", prefix));
        assertNull(normalizer.toFullCacheKey("trackcache:url:http://example.com", prefix));
    }
}
