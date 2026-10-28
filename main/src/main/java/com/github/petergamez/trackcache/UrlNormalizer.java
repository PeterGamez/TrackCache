package com.github.petergamez.trackcache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class UrlNormalizer {

    private static final Logger log = LoggerFactory.getLogger(UrlNormalizer.class);

    // Search prefixes mapping from Lavalink & PulseLink to platform names
    private static final Map<String, String> SEARCH_PREFIX_MAP = new HashMap<>();

    static {
        // Standard Lavalink / YouTube
        SEARCH_PREFIX_MAP.put("ytsearch", "youtube");
        SEARCH_PREFIX_MAP.put("ytmsearch", "youtubemusic");

        // SoundCloud
        SEARCH_PREFIX_MAP.put("scsearch", "soundcloud");

        // Spotify & Spotify Mix Recommendations
        SEARCH_PREFIX_MAP.put("spsearch", "spotify");
        SEARCH_PREFIX_MAP.put("sprec", "spotify:rec");

        // Amazon Music
        SEARCH_PREFIX_MAP.put("amzsearch", "amazonmusic");
        SEARCH_PREFIX_MAP.put("amz", "amazonmusic");

        // Apple Music
        SEARCH_PREFIX_MAP.put("amsearch", "applemusic");

        // Deezer
        SEARCH_PREFIX_MAP.put("dzsearch", "deezer");
        SEARCH_PREFIX_MAP.put("dzisrc", "deezer:isrc");

        // JioSaavn
        SEARCH_PREFIX_MAP.put("jssearch", "jiosaavn");

        // Gaana
        SEARCH_PREFIX_MAP.put("gnsearch", "gaana");

        // Audiomack
        SEARCH_PREFIX_MAP.put("admsearch", "audiomack");

        // Tidal
        SEARCH_PREFIX_MAP.put("tdsearch", "tidal");

        // Yandex Music
        SEARCH_PREFIX_MAP.put("ymsearch", "yandexmusic");

        // VK Music
        SEARCH_PREFIX_MAP.put("vksearch", "vkmusic");

        // Qobuz
        SEARCH_PREFIX_MAP.put("qbsearch", "qobuz");

        // Pandora
        SEARCH_PREFIX_MAP.put("pdsearch", "pandora");

        // Bilibili
        SEARCH_PREFIX_MAP.put("bilibilisearch", "bilibili");
        SEARCH_PREFIX_MAP.put("bilisearch", "bilibili");
        SEARCH_PREFIX_MAP.put("bsearch", "bilibili");
    }

    private static final Pattern GENERIC_SEARCH_PREFIX_PATTERN = Pattern.compile("^([a-zA-Z0-9_]+):\\s*(.*)$");

    // YouTube Patterns
    private static final Pattern YT_DOMAIN_PATTERN = Pattern.compile("^(?:https?://)?(?:www\\.|m\\.|music\\.)?(?:youtube\\.com|youtu\\.be)/?", Pattern.CASE_INSENSITIVE);
    private static final Pattern YOUTUBE_LIST_PATTERN = Pattern.compile("[?&]list=([a-zA-Z0-9_-]+)");
    private static final Pattern YOUTUBE_VIDEO_ID_PARAM = Pattern.compile("[?&]v=([a-zA-Z0-9_-]{11})");
    private static final Pattern YOUTUBE_SHORTS_PATTERN = Pattern.compile("/shorts/([a-zA-Z0-9_-]{11})");
    private static final Pattern YOUTUBE_LIVE_PATTERN = Pattern.compile("/live/([a-zA-Z0-9_-]{11})");
    private static final Pattern YOUTUBE_WATCH_PATH_PATTERN = Pattern.compile("/watch/([a-zA-Z0-9_-]{11})");
    private static final Pattern YOUTUBE_EMBED_PATTERN = Pattern.compile("/embed/([a-zA-Z0-9_-]{11})");
    private static final Pattern YOUTUBE_V_PATTERN = Pattern.compile("/v/([a-zA-Z0-9_-]{11})");
    private static final Pattern YOUTU_BE_PATTERN = Pattern.compile("youtu\\.be/([a-zA-Z0-9_-]{11})");
    private static final Pattern YOUTUBE_RAW_ID_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]{11}$");

    // Spotify Patterns
    private static final Pattern SPOTIFY_URL_PATTERN = Pattern.compile(
            "^(?:https?://)?(?:open|play)\\.spotify\\.com/(?:intl-[a-z]{2}/)?(track|playlist|album|artist)/([a-zA-Z0-9]+)",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern SPOTIFY_URI_PATTERN = Pattern.compile(
            "^spotify:(track|playlist|album|artist):([a-zA-Z0-9]+)$",
            Pattern.CASE_INSENSITIVE
    );

    // SoundCloud Patterns
    private static final Pattern SOUNDCLOUD_URL_PATTERN = Pattern.compile(
            "^(?:https?://)?(?:www\\.|m\\.)?soundcloud\\.com/([a-zA-Z0-9-_]+/[a-zA-Z0-9-_/]+)",
            Pattern.CASE_INSENSITIVE
    );

    // Apple Music Patterns
    private static final Pattern APPLE_MUSIC_URL_PATTERN = Pattern.compile(
            "^(?:https?://)?music\\.apple\\.com/[a-z]{2}/(album|playlist|song)/([^?]+)",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern APPLE_MUSIC_I_PARAM = Pattern.compile("[?&]i=([0-9]+)");

    // Deezer Patterns
    private static final Pattern DEEZER_URL_PATTERN = Pattern.compile(
            "^(?:https?://)?(?:www\\.)?deezer\\.com/(?:[a-z]{2}/)?(track|playlist|album)/([0-9]+)",
            Pattern.CASE_INSENSITIVE
    );

    // Amazon Music Patterns
    private static final Pattern AMAZON_MUSIC_URL_PATTERN = Pattern.compile(
            "^(?:https?://)?(?:music|www)\\.amazon\\.[a-z.]+(?:/[a-z]{2})?/(albums|tracks|playlists)/([a-zA-Z0-9]+)",
            Pattern.CASE_INSENSITIVE
    );

    // Tidal Patterns
    private static final Pattern TIDAL_URL_PATTERN = Pattern.compile(
            "^(?:https?://)?(?:listen\\.|browse\\.)?tidal\\.com/(?:browse/)?(track|playlist|album)/([0-9]+)",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern TIDAL_URI_PATTERN = Pattern.compile(
            "^tidal:(track|playlist|album):([0-9]+)$",
            Pattern.CASE_INSENSITIVE
    );

    // Yandex Music Patterns
    private static final Pattern YANDEX_TRACK_URL_PATTERN = Pattern.compile(
            "^(?:https?://)?music\\.yandex\\.[a-z]+/(?:album/[0-9]+/)?track/([0-9]+)",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern YANDEX_ALBUM_URL_PATTERN = Pattern.compile(
            "^(?:https?://)?music\\.yandex\\.[a-z]+/album/([0-9]+)",
            Pattern.CASE_INSENSITIVE
    );

    // Spotify LavaSrc Recommendations & Mix Pattern
    private static final Pattern SPREC_MIX_PATTERN = Pattern.compile(
            "^mix:(track|album|artist|isrc):\\s*(.+)$",
            Pattern.CASE_INSENSITIVE
    );

    // Bilibili Patterns
    private static final Pattern BILIBILI_VIDEO_URL_PATTERN = Pattern.compile(
            "^(?:https?://)?(?:www\\.|m\\.)?bilibili\\.com/video/((?:BV[a-zA-Z0-9]+)|(?:av[0-9]+))",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern BILIBILI_B23_URL_PATTERN = Pattern.compile(
            "^(?:https?://)?b23\\.tv/((?:BV[a-zA-Z0-9]+)|(?:av[0-9]+))",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern BILIBILI_AUDIO_URL_PATTERN = Pattern.compile(
            "^(?:https?://)?(?:www\\.|m\\.)?bilibili\\.com/audio/(au[0-9]+)",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern BILIBILI_RAW_ID_PATTERN = Pattern.compile(
            "^(?:BV[a-zA-Z0-9]{10}|av[0-9]+|au[0-9]+)$",
            Pattern.CASE_INSENSITIVE
    );

    /**
     * Normalizes the identifier into a canonical key using platform names.
     * Returns null if the identifier is not cacheable (e.g. generic HTTP/HTTPS, invalid platform URL, etc.).
     *
     * @param identifier The raw identifier/URL/query from /v4/loadtracks
     * @return Canonical key representation, or null if uncacheable
     */
    public String normalize(String identifier) {
        if (identifier == null || identifier.trim().isEmpty()) {
            return null;
        }

        String query = identifier.trim();

        // 1. If query is already an established canonical key, return immediately
        if (isCacheableKey(query)) {
            return query;
        }

        // 2. Handle prefix patterns (<prefix>:<rest>)
        if (!query.startsWith("http://") && !query.startsWith("https://")) {
            Matcher prefixMatcher = GENERIC_SEARCH_PREFIX_PATTERN.matcher(query);
            if (prefixMatcher.find()) {
                String prefix = prefixMatcher.group(1).toLowerCase();
                String rest = prefixMatcher.group(2).trim();

                // If rest is an HTTP/HTTPS URL, unwrap and normalize
                if (rest.startsWith("http://") || rest.startsWith("https://")) {
                    return normalize(rest);
                }

                // LavaSrc Spotify recommendations
                if ("sprec".equals(prefix)) {
                    return normalizeSpotifyRec(rest);
                }

                // Standard search prefix mapping
                if (SEARCH_PREFIX_MAP.containsKey(prefix)) {
                    String platform = SEARCH_PREFIX_MAP.get(prefix);
                    if (platform.contains(":")) {
                        return platform + ":" + rest;
                    }
                    return platform + ":search:" + rest;
                } else if (prefix.endsWith("search")) {
                    String platform = prefix.substring(0, prefix.length() - 6);
                    return platform + ":search:" + rest;
                }

                // Client prefix fallback (e.g. youtube:dQw4w9WgXcQ, bilibili:BV1xx411c7mD)
                String normalizedRest = normalize(rest);
                if (normalizedRest != null) {
                    return normalizedRest;
                }
            }
        }

        // 3. Platform Normalizers (Symmetric & Uniform)
        String result;
        if ((result = normalizeYouTube(query)) != null) return result;
        if ((result = normalizeSpotify(query)) != null) return result;
        if ((result = normalizeBilibili(query)) != null) return result;
        if ((result = normalizeSoundCloud(query)) != null) return result;
        if ((result = normalizeAppleMusic(query)) != null) return result;
        if ((result = normalizeDeezer(query)) != null) return result;
        if ((result = normalizeAmazonMusic(query)) != null) return result;
        if ((result = normalizeTidal(query)) != null) return result;
        if ((result = normalizeYandexMusic(query)) != null) return result;

        // 4. Any unmatched HTTP/HTTPS URL is generic/unsupported -> DO NOT CACHE
        if (query.startsWith("http://") || query.startsWith("https://")) {
            return null;
        }

        return null;
    }

    private String normalizeYouTube(String query) {
        if (query == null || query.isEmpty()) {
            return null;
        }

        // Bare 11-char ID
        if (YOUTUBE_RAW_ID_PATTERN.matcher(query).matches()) {
            return "youtube:track:" + query;
        }

        String lower = query.toLowerCase();
        if (!lower.contains("youtube") && !lower.contains("youtu.be")) {
            return null;
        }

        // 1. Detect playlist parameter 'list='
        Matcher listMatcher = YOUTUBE_LIST_PATTERN.matcher(query);
        if (listMatcher.find()) {
            String listId = listMatcher.group(1);
            return "youtube:playlist:" + listId;
        }

        // 2. youtu.be shortlinks
        Matcher youtuBeMatcher = YOUTU_BE_PATTERN.matcher(query);
        if (youtuBeMatcher.find()) {
            return "youtube:track:" + youtuBeMatcher.group(1);
        }

        // 3. YouTube Shorts
        Matcher shortsMatcher = YOUTUBE_SHORTS_PATTERN.matcher(query);
        if (shortsMatcher.find()) {
            return "youtube:track:" + shortsMatcher.group(1);
        }

        // 4. YouTube Live
        Matcher liveMatcher = YOUTUBE_LIVE_PATTERN.matcher(query);
        if (liveMatcher.find()) {
            return "youtube:track:" + liveMatcher.group(1);
        }

        // 5. YouTube Embed
        Matcher embedMatcher = YOUTUBE_EMBED_PATTERN.matcher(query);
        if (embedMatcher.find()) {
            return "youtube:track:" + embedMatcher.group(1);
        }

        // 6. YouTube /v/
        Matcher vMatcher = YOUTUBE_V_PATTERN.matcher(query);
        if (vMatcher.find()) {
            return "youtube:track:" + vMatcher.group(1);
        }

        // 7. YouTube /watch/ID
        Matcher watchPathMatcher = YOUTUBE_WATCH_PATH_PATTERN.matcher(query);
        if (watchPathMatcher.find()) {
            return "youtube:track:" + watchPathMatcher.group(1);
        }

        // 8. Standard ?v=ID parameter
        Matcher videoIdMatcher = YOUTUBE_VIDEO_ID_PARAM.matcher(query);
        if (videoIdMatcher.find()) {
            return "youtube:track:" + videoIdMatcher.group(1);
        }

        return null;
    }

    private String normalizeSpotify(String query) {
        Matcher spotifyUriMatcher = SPOTIFY_URI_PATTERN.matcher(query);
        if (spotifyUriMatcher.find()) {
            return "spotify:" + spotifyUriMatcher.group(1).toLowerCase() + ":" + spotifyUriMatcher.group(2);
        }
        Matcher spotifyUrlMatcher = SPOTIFY_URL_PATTERN.matcher(query);
        if (spotifyUrlMatcher.find()) {
            return "spotify:" + spotifyUrlMatcher.group(1).toLowerCase() + ":" + spotifyUrlMatcher.group(2);
        }
        return null;
    }

    private String normalizeBilibili(String query) {
        Matcher biliVideoMatcher = BILIBILI_VIDEO_URL_PATTERN.matcher(query);
        if (biliVideoMatcher.find()) {
            return "bilibili:video:" + biliVideoMatcher.group(1);
        }
        Matcher biliB23Matcher = BILIBILI_B23_URL_PATTERN.matcher(query);
        if (biliB23Matcher.find()) {
            return "bilibili:video:" + biliB23Matcher.group(1);
        }
        Matcher biliAudioMatcher = BILIBILI_AUDIO_URL_PATTERN.matcher(query);
        if (biliAudioMatcher.find()) {
            return "bilibili:audio:" + biliAudioMatcher.group(1);
        }
        if (BILIBILI_RAW_ID_PATTERN.matcher(query).matches()) {
            return query.toLowerCase().startsWith("au") ? "bilibili:audio:" + query : "bilibili:video:" + query;
        }
        return null;
    }

    private String normalizeSoundCloud(String query) {
        Matcher scMatcher = SOUNDCLOUD_URL_PATTERN.matcher(query);
        if (scMatcher.find()) {
            String cleanPath = stripTrackingAndTrailingSlash(scMatcher.group(1));
            if (cleanPath.contains("/sets/")) {
                return "soundcloud:playlist:" + cleanPath;
            }
            return "soundcloud:track:" + cleanPath;
        }
        return null;
    }

    private String normalizeAppleMusic(String query) {
        Matcher appleMatcher = APPLE_MUSIC_URL_PATTERN.matcher(query);
        if (appleMatcher.find()) {
            String type = appleMatcher.group(1).toLowerCase();
            String idOrPath = stripTrackingAndTrailingSlash(appleMatcher.group(2));
            if (query.contains("?i=") || query.contains("&i=")) {
                Matcher iMatcher = APPLE_MUSIC_I_PARAM.matcher(query);
                if (iMatcher.find()) {
                    return "applemusic:song:" + iMatcher.group(1);
                }
            }
            return "applemusic:" + type + ":" + idOrPath;
        }
        return null;
    }

    private String normalizeDeezer(String query) {
        Matcher deezerMatcher = DEEZER_URL_PATTERN.matcher(query);
        if (deezerMatcher.find()) {
            String type = deezerMatcher.group(1).toLowerCase();
            String id = deezerMatcher.group(2);
            return "deezer:" + type + ":" + id;
        }
        return null;
    }

    private String normalizeAmazonMusic(String query) {
        Matcher amazonMatcher = AMAZON_MUSIC_URL_PATTERN.matcher(query);
        if (amazonMatcher.find()) {
            String type = amazonMatcher.group(1).toLowerCase();
            if (type.endsWith("s")) {
                type = type.substring(0, type.length() - 1);
            }
            String id = amazonMatcher.group(2);
            return "amazonmusic:" + type + ":" + id;
        }
        return null;
    }

    private String normalizeTidal(String query) {
        Matcher tidalUriMatcher = TIDAL_URI_PATTERN.matcher(query);
        if (tidalUriMatcher.find()) {
            return "tidal:" + tidalUriMatcher.group(1).toLowerCase() + ":" + tidalUriMatcher.group(2);
        }
        Matcher tidalUrlMatcher = TIDAL_URL_PATTERN.matcher(query);
        if (tidalUrlMatcher.find()) {
            return "tidal:" + tidalUrlMatcher.group(1).toLowerCase() + ":" + tidalUrlMatcher.group(2);
        }
        return null;
    }

    private String normalizeYandexMusic(String query) {
        Matcher yandexTrackMatcher = YANDEX_TRACK_URL_PATTERN.matcher(query);
        if (yandexTrackMatcher.find()) {
            return "yandexmusic:track:" + yandexTrackMatcher.group(1);
        }
        Matcher yandexAlbumMatcher = YANDEX_ALBUM_URL_PATTERN.matcher(query);
        if (yandexAlbumMatcher.find()) {
            return "yandexmusic:album:" + yandexAlbumMatcher.group(1);
        }
        return null;
    }

    /**
     * Determines if an identifier/URL is eligible for caching in Redis.
     * Generic HTTP/HTTPS streams, invalid platform URLs, and malformed inputs return false.
     *
     * @param identifier The raw input query or URL
     * @return true if cacheable, false otherwise
     */
    public boolean isCacheable(String identifier) {
        return toFullCacheKey(identifier, "") != null;
    }

    /**
     * Validates whether a normalized canonical key meets strict criteria for caching.
     * Keys containing "http:", "https:", "url:", or "://" are strictly forbidden.
     *
     * @param canonicalKey The normalized canonical key
     * @return true if valid and cacheable, false otherwise
     */
    public boolean isCacheableKey(String canonicalKey) {
        if (canonicalKey == null || canonicalKey.trim().isEmpty()) {
            return false;
        }
        String lower = canonicalKey.toLowerCase();

        // Reject any key containing HTTP/HTTPS or raw URLs
        if (lower.contains("http:") || lower.contains("https:") || lower.contains("://") || lower.startsWith("url:")) {
            return false;
        }

        if (lower.startsWith("youtube:")) {
            return isValidSubKey(lower, "youtube:", "track:", "playlist:", "search:");
        }

        if (lower.startsWith("youtubemusic:")) {
            return isValidSubKey(lower, "youtubemusic:", "track:", "playlist:", "search:");
        }

        if (lower.startsWith("spotify:")) {
            return isValidSubKey(lower, "spotify:", "track:", "playlist:", "album:", "artist:", "search:", "rec:");
        }

        if (lower.startsWith("soundcloud:")) {
            return isValidSubKey(lower, "soundcloud:", "track:", "playlist:", "search:");
        }

        if (lower.startsWith("applemusic:")) {
            return isValidSubKey(lower, "applemusic:", "song:", "track:", "album:", "playlist:", "search:");
        }

        if (lower.startsWith("deezer:")) {
            return isValidSubKey(lower, "deezer:", "track:", "album:", "playlist:", "search:", "isrc:");
        }

        if (lower.startsWith("amazonmusic:")) {
            return isValidSubKey(lower, "amazonmusic:", "track:", "album:", "playlist:", "search:");
        }

        if (lower.startsWith("tidal:")) {
            return isValidSubKey(lower, "tidal:", "track:", "album:", "playlist:", "search:");
        }

        if (lower.startsWith("yandexmusic:")) {
            return isValidSubKey(lower, "yandexmusic:", "track:", "album:", "playlist:", "search:");
        }

        if (lower.startsWith("bilibili:")) {
            return isValidSubKey(lower, "bilibili:", "video:", "audio:", "search:");
        }

        for (String platform : SEARCH_PREFIX_MAP.values()) {
            if (lower.startsWith(platform + ":")) {
                return true;
            }
        }

        return false;
    }

    private boolean isValidSubKey(String lower, String platformPrefix, String... allowedTypes) {
        String sub = lower.substring(platformPrefix.length());
        for (String type : allowedTypes) {
            if (sub.startsWith(type) && sub.length() > type.length()) {
                return true;
            }
        }
        return false;
    }

    private String normalizeSpotifyRec(String rest) {
        // Handle LavaSrc inspired-by mix flow: sprec:mix:(track|album|artist|isrc):<id|url|uri>
        Matcher mixMatcher = SPREC_MIX_PATTERN.matcher(rest);
        if (mixMatcher.find()) {
            String mixType = mixMatcher.group(1).toLowerCase();
            String target = mixMatcher.group(2).trim();

            Matcher uriMatcher = SPOTIFY_URI_PATTERN.matcher(target);
            if (uriMatcher.find()) {
                target = uriMatcher.group(2);
            } else {
                Matcher urlMatcher = SPOTIFY_URL_PATTERN.matcher(target);
                if (urlMatcher.find()) {
                    target = urlMatcher.group(2);
                }
            }

            return "spotify:rec:mix:" + mixType + ":" + target;
        }

        return "spotify:rec:" + rest;
    }

    /**
     * Resolves the full Redis key including the configured prefix.
     * Prevents double prefixing if the identifier is already prefixed.
     * Returns null if the identifier or key is uncacheable.
     *
     * @param identifier The raw input query/URL or existing key
     * @param prefix The key prefix (e.g. "trackcache:")
     * @return Full Redis key, or null if uncacheable
     */
    public String toFullCacheKey(String identifier, String prefix) {
        String p = prefix != null ? prefix : "";
        if (identifier == null || identifier.trim().isEmpty()) {
            return null;
        }
        if (!p.isEmpty() && identifier.startsWith(p)) {
            String stripped = identifier.substring(p.length());
            if (isCacheableKey(stripped)) {
                return identifier;
            }
            return null;
        }
        String canonical = normalize(identifier);
        if (canonical == null || canonical.isEmpty() || !isCacheableKey(canonical)) {
            return null;
        }
        return p + canonical;
    }

    private String stripTrackingAndTrailingSlash(String url) {
        String clean = url;
        int q = clean.indexOf('?');
        if (q != -1) {
            clean = clean.substring(0, q);
        }
        while (clean.endsWith("/")) {
            clean = clean.substring(0, clean.length() - 1);
        }
        return clean;
    }
}
