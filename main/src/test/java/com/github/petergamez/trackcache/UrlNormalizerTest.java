package com.github.petergamez.trackcache;

import com.github.petergamez.trackcache.util.UrlNormalizer;
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
    void testYouTube() {
        // Standard URLs
        assertEquals("youtube:track:dQw4w9WgXcQ", normalizer.normalize("https://www.youtube.com/watch?v=dQw4w9WgXcQ"));
        assertEquals("youtube:track:dQw4w9WgXcQ", normalizer.normalize("https://youtu.be/dQw4w9WgXcQ"));
        assertEquals("youtube:track:dQw4w9WgXcQ", normalizer.normalize("https://www.youtube.com/shorts/dQw4w9WgXcQ"));
        assertEquals("youtube:track:dQw4w9WgXcQ", normalizer.normalize("https://www.youtube.com/live/dQw4w9WgXcQ"));
        assertEquals("youtube:track:dQw4w9WgXcQ", normalizer.normalize("https://www.youtube.com/embed/dQw4w9WgXcQ"));
        assertEquals("youtube:track:dQw4w9WgXcQ", normalizer.normalize("https://www.youtube.com/v/dQw4w9WgXcQ"));
        assertEquals("youtube:track:dQw4w9WgXcQ", normalizer.normalize("https://www.youtube.com/watch?feature=shared&v=dQw4w9WgXcQ"));
        assertEquals("youtube:playlist:PLrAl54-B8Hk5R", normalizer.normalize("https://www.youtube.com/watch?v=dQw4w9WgXcQ&list=PLrAl54-B8Hk5R"));

        // Bare 11-char ID
        assertEquals("youtube:track:dQw4w9WgXcQ", normalizer.normalize("dQw4w9WgXcQ"));

        // Search prefixes (including whitespace collapsing and case normalization)
        assertEquals("youtube:search:rick astley", normalizer.normalize("ytsearch:rick astley"));
        assertEquals("youtube:search:rick astley never gonna give you up",
                normalizer.normalize("ytsearch:   Rick   Astley   Never   Gonna  Give You Up  "));
        assertEquals("youtubemusic:search:rick astley", normalizer.normalize("ytmsearch:rick astley"));
        assertEquals("youtube:search:rick astley", normalizer.normalize("youtube:search:rick astley"));

        // Prefix wrapping
        assertEquals("youtube:track:dQw4w9WgXcQ", normalizer.normalize("youtube:https://www.youtube.com/watch?v=dQw4w9WgXcQ"));
        assertEquals("youtube:track:dQw4w9WgXcQ", normalizer.normalize("youtube:dQw4w9WgXcQ"));

        // Invalid YouTube URLs -> null
        assertNull(normalizer.normalize("https://www.youtube.com/@somechannel"));
        assertNull(normalizer.normalize("https://www.youtube.com/feed/trending"));
        assertNull(normalizer.normalize("youtube:http://example.com/something"));
        assertNull(normalizer.normalize("https://evil.com/watch?v=dQw4w9WgXcQ&ref=youtube"));
        assertNull(normalizer.normalize("ytsearch:   "));
    }

    @Test
    void testSpotify() {
        // URLs
        assertEquals("spotify:track:4cOdK2wGLETKBW3PvgPWqT",
                normalizer.normalize("https://open.spotify.com/track/4cOdK2wGLETKBW3PvgPWqT"));
        assertEquals("spotify:playlist:37i9dQZF1DXcBWIGoYBM5M",
                normalizer.normalize("https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M"));
        assertEquals("spotify:album:1DFixLWuPkv3KT3TnV35m3",
                normalizer.normalize("https://open.spotify.com/album/1DFixLWuPkv3KT3TnV35m3"));
        assertEquals("spotify:artist:0OdUWJ0sBjDrqHygGUWzfr",
                normalizer.normalize("https://open.spotify.com/artist/0OdUWJ0sBjDrqHygGUWzfr"));
        assertEquals("spotify:track:4cOdK2wGLETKBW3PvgPWqT",
                normalizer.normalize("https://open.spotify.com/intl-th/track/4cOdK2wGLETKBW3PvgPWqT"));

        // URI
        assertEquals("spotify:track:4cOdK2wGLETKBW3PvgPWqT",
                normalizer.normalize("spotify:track:4cOdK2wGLETKBW3PvgPWqT"));

        // Search
        assertEquals("spotify:search:yoasobi idol", normalizer.normalize("spsearch:yoasobi idol"));
        assertNull(normalizer.normalize("sprec:mix:track:4cOdK2wGLETKBW3PvgPWqT"));

        // Prefix wrapping
        assertEquals("spotify:track:4cOdK2wGLETKBW3PvgPWqT",
                normalizer.normalize("spotify:https://open.spotify.com/track/4cOdK2wGLETKBW3PvgPWqT"));
    }

    @Test
    void testBilibili() {
        // URLs
        assertEquals("bilibili:video:BV1xx411c7mD", normalizer.normalize("https://www.bilibili.com/video/BV1xx411c7mD"));
        assertEquals("bilibili:video:BV1xx411c7mD", normalizer.normalize("https://b23.tv/BV1xx411c7mD"));
        assertEquals("bilibili:video:av170001", normalizer.normalize("https://www.bilibili.com/video/av170001"));
        assertEquals("bilibili:audio:au123456", normalizer.normalize("https://www.bilibili.com/audio/au123456"));

        // Bare IDs (including 11-char av/au IDs that must not collide with YouTube 11-char IDs)
        assertEquals("bilibili:video:BV1xx411c7mD", normalizer.normalize("BV1xx411c7mD"));
        assertEquals("bilibili:video:av170001", normalizer.normalize("av170001"));
        assertEquals("bilibili:video:av123456789", normalizer.normalize("av123456789"));
        assertEquals("bilibili:audio:au123456", normalizer.normalize("au123456"));
        assertEquals("bilibili:audio:au123456789", normalizer.normalize("au123456789"));

        // Search prefixes
        assertEquals("bilibili:search:yoasobi", normalizer.normalize("bilibilisearch:yoasobi"));
        assertEquals("bilibili:search:yoasobi", normalizer.normalize("bilisearch:yoasobi"));
        assertEquals("bilibili:search:yoasobi", normalizer.normalize("bilibili:search:yoasobi"));
        assertNull(normalizer.normalize("bsearch:yoasobi"));

        // Prefix wrapping
        assertEquals("bilibili:video:BV1xx411c7mD", normalizer.normalize("bilibili:BV1xx411c7mD"));
        assertEquals("bilibili:video:av123456789", normalizer.normalize("bilibili:av123456789"));
        assertEquals("bilibili:video:BV1xx411c7mD", normalizer.normalize("bilibili:https://www.bilibili.com/video/BV1xx411c7mD"));
        assertEquals("bilibili:video:BV1xx411c7mD", normalizer.normalize("bilibili:video:BV1xx411c7mD"));

        // Invalid bilibili -> null
        assertNull(normalizer.normalize("bilibili:http://example.com"));
        assertNull(normalizer.normalize("bilibili:https://example.com"));
    }

    @Test
    void testSoundCloud() {
        assertEquals("soundcloud:track:user/song-title",
                normalizer.normalize("https://soundcloud.com/user/song-title"));
        assertEquals("soundcloud:track:user/song-title",
                normalizer.normalize("https://soundcloud.com/user/song-title/#t=0:15"));
        assertEquals("soundcloud:playlist:user/sets/playlist-name",
                normalizer.normalize("https://soundcloud.com/user/sets/playlist-name"));
        assertEquals("soundcloud:search:faded",
                normalizer.normalize("scsearch:faded"));
        assertEquals("soundcloud:track:user/song-title",
                normalizer.normalize("scsearch:https://soundcloud.com/user/song-title"));

        // Invalid or reserved SoundCloud paths -> null
        assertNull(normalizer.normalize("https://soundcloud.com/artist///"));
        assertNull(normalizer.normalize("https://soundcloud.com/user/sets/"));
        assertNull(normalizer.normalize("https://soundcloud.com/you/likes"));
    }

    @Test
    void testAppleMusic() {
        assertEquals("applemusic:album:album-name/123456",
                normalizer.normalize("https://music.apple.com/us/album/album-name/123456"));
        assertEquals("applemusic:song:987654",
                normalizer.normalize("https://music.apple.com/us/album/album-name/123456?i=987654"));
        assertEquals("applemusic:playlist:pl-name/pl.123",
                normalizer.normalize("https://music.apple.com/us/playlist/pl-name/pl.123"));
        assertEquals("applemusic:search:coldplay",
                normalizer.normalize("amsearch:coldplay"));
    }

    @Test
    void testDeezer() {
        assertEquals("deezer:track:3135556", normalizer.normalize("https://www.deezer.com/track/3135556"));
        assertEquals("deezer:album:302127", normalizer.normalize("https://www.deezer.com/en/album/302127"));
        assertEquals("deezer:playlist:908622995", normalizer.normalize("https://www.deezer.com/playlist/908622995"));
        assertEquals("deezer:search:daft punk", normalizer.normalize("dzsearch:daft punk"));
        assertNull(normalizer.normalize("dzisrc:USRC17607839"));
    }

    @Test
    void testAmazonMusic() {
        assertEquals("amazonmusic:track:B001387G7G",
                normalizer.normalize("https://music.amazon.com/tracks/B001387G7G"));
        assertEquals("amazonmusic:album:B001387G7G",
                normalizer.normalize("https://music.amazon.com/albums/B001387G7G"));
        assertEquals("amazonmusic:search:bohemian rhapsody",
                normalizer.normalize("amzsearch:bohemian rhapsody"));
    }

    @Test
    void testTidal() {
        assertEquals("tidal:track:123456", normalizer.normalize("https://listen.tidal.com/track/123456"));
        assertEquals("tidal:track:123456", normalizer.normalize("tidal:track:123456"));
        assertEquals("tidal:search:taylor swift", normalizer.normalize("tdsearch:taylor swift"));
    }

    @Test
    void testYandexMusic() {
        assertEquals("yandexmusic:track:456", normalizer.normalize("https://music.yandex.ru/album/123/track/456"));
        assertEquals("yandexmusic:album:123", normalizer.normalize("https://music.yandex.ru/album/123"));
        assertEquals("yandexmusic:search:kino", normalizer.normalize("ymsearch:kino"));
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
    void testPrefixUnwrappingAndSafety() {
        // Prefixes wrapping generic HTTP URLs must return null
        assertNull(normalizer.normalize("ytsearch:http://example.com/audio.mp3"));
        assertNull(normalizer.normalize("scsearch:http://example.com/audio.mp3"));
        assertNull(normalizer.normalize("spsearch:http://example.com/audio.mp3"));
        assertNull(normalizer.normalize("bsearch:http://example.com/audio.mp3"));
        assertNull(normalizer.normalize("spotify:http://example.com/audio.mp3"));
        assertNull(normalizer.normalize("youtube:http://example.com/audio.mp3"));
        assertNull(normalizer.normalize("bilibili:http://example.com/audio.mp3"));

        assertFalse(normalizer.isCacheable("ytsearch:http://example.com/audio.mp3"));
        assertFalse(normalizer.isCacheable("spotify:http://example.com/audio.mp3"));
        assertFalse(normalizer.isCacheable("youtube:http://example.com/audio.mp3"));
        assertFalse(normalizer.isCacheable("bilibili:http://example.com/audio.mp3"));

        // Prefixes wrapping valid platform URLs must unwrap cleanly
        assertEquals("youtube:track:dQw4w9WgXcQ",
                normalizer.normalize("ytsearch:https://www.youtube.com/watch?v=dQw4w9WgXcQ"));
        assertEquals("spotify:track:4cOdK2wGLETKBW3PvgPWqT",
                normalizer.normalize("spsearch:https://open.spotify.com/track/4cOdK2wGLETKBW3PvgPWqT"));
        assertEquals("bilibili:video:BV1xx411c7mD",
                normalizer.normalize("bilisearch:https://www.bilibili.com/video/BV1xx411c7mD"));
    }

    @Test
    void testIsCacheableKeyValidation() {
        // Rejects keys with HTTP/HTTPS or raw URLs
        assertFalse(normalizer.isCacheableKey("youtube:http://www.youtube.com/watch?v=123"));
        assertFalse(normalizer.isCacheableKey("youtube:https://www.youtube.com/watch?v=123"));
        assertFalse(normalizer.isCacheableKey("bilibili:http://example.com"));
        assertFalse(normalizer.isCacheableKey("spotify:http://example.com"));
        assertFalse(normalizer.isCacheableKey("youtube://www.youtube.com/watch?v=123"));
        assertFalse(normalizer.isCacheableKey("url:http://example.com/audio.mp3"));
        assertFalse(normalizer.isCacheableKey("http:track:http://example.com"));
        assertFalse(normalizer.isCacheableKey("http://example.com"));
        assertFalse(normalizer.isCacheableKey("https://example.com"));
        assertFalse(normalizer.isCacheableKey(null));
        assertFalse(normalizer.isCacheableKey(""));

        // Rejects empty subkeys across all platforms
        assertFalse(normalizer.isCacheableKey("youtube:track:"));
        assertFalse(normalizer.isCacheableKey("bilibili:video:"));
        assertFalse(normalizer.isCacheableKey("spotify:track:"));
        assertFalse(normalizer.isCacheableKey("jiosaavn:"));
        assertFalse(normalizer.isCacheableKey("jiosaavn:track:"));
        assertFalse(normalizer.isCacheableKey("gaana:search:"));

        // Valid canonical keys across all platforms
        assertTrue(normalizer.isCacheableKey("youtube:track:dQw4w9WgXcQ"));
        assertTrue(normalizer.isCacheableKey("youtube:playlist:PLrAl54-B8Hk5R"));
        assertTrue(normalizer.isCacheableKey("youtube:search:rick astley"));
        assertTrue(normalizer.isCacheableKey("spotify:track:4cOdK2wGLETKBW3PvgPWqT"));
        assertTrue(normalizer.isCacheableKey("spotify:playlist:37i9dQZF1DXcBWIGoYBM5M"));
        assertTrue(normalizer.isCacheableKey("bilibili:video:BV1xx411c7mD"));
        assertTrue(normalizer.isCacheableKey("bilibili:audio:au123456"));
        assertTrue(normalizer.isCacheableKey("bilibili:search:yoasobi"));
        assertTrue(normalizer.isCacheableKey("soundcloud:track:user/title"));
        assertTrue(normalizer.isCacheableKey("applemusic:song:987654"));
        assertTrue(normalizer.isCacheableKey("deezer:track:3135556"));
        assertTrue(normalizer.isCacheableKey("amazonmusic:track:B001387G7G"));
        assertTrue(normalizer.isCacheableKey("tidal:track:123456"));
        assertTrue(normalizer.isCacheableKey("yandexmusic:track:456"));
        assertTrue(normalizer.isCacheableKey("jiosaavn:track:abc123"));
        assertTrue(normalizer.isCacheableKey("jiosaavn:search:arijit singh"));
    }

    @Test
    void testToFullCacheKey() {
        String prefix = "trackcache:";
        assertEquals("trackcache:youtube:track:dQw4w9WgXcQ",
                normalizer.toFullCacheKey("https://www.youtube.com/watch?v=dQw4w9WgXcQ", prefix));
        assertEquals("trackcache:bilibili:video:BV1xx411c7mD",
                normalizer.toFullCacheKey("https://www.bilibili.com/video/BV1xx411c7mD", prefix));
        assertEquals("trackcache:spotify:track:4cOdK2wGLETKBW3PvgPWqT",
                normalizer.toFullCacheKey("https://open.spotify.com/track/4cOdK2wGLETKBW3PvgPWqT", prefix));

        // Generic HTTP -> null
        assertNull(normalizer.toFullCacheKey("http://example.com/song.mp3", prefix));
        assertNull(normalizer.toFullCacheKey("https://cdn.discordapp.com/sound.mp3", prefix));

        // Invalid keys -> null
        assertNull(normalizer.toFullCacheKey("youtube:http://example.com", prefix));
        assertNull(normalizer.toFullCacheKey("bilibili:http://example.com", prefix));
        assertNull(normalizer.toFullCacheKey("trackcache:youtube:http://example.com", prefix));
        assertNull(normalizer.toFullCacheKey("trackcache:url:http://example.com", prefix));
    }
}
