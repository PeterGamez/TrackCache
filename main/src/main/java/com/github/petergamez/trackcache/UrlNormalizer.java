package com.github.petergamez.trackcache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
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

    // Tracking query parameter names to remove
    private static final Set<String> TRACKING_PARAMS = new HashSet<>(Arrays.asList(
            "si", "ref", "feature", "ab_channel", "fbclid", "igshid", "t", "index", "pp"
    ));

    /**
     * Normalizes the identifier into a canonical key using platform names.
     * Returns null if the identifier is not cacheable (e.g. generic HTTP/HTTPS, invalid YouTube URL, etc.).
     *
     * @param identifier The raw identifier/URL/query from /v4/loadtracks
     * @return Canonical key representation, or null if uncacheable
     */
    public String normalize(String identifier) {
        if (identifier == null || identifier.trim().isEmpty()) {
            return null;
        }

        String query = identifier.trim();

        // 0. Handle direct "youtube:" prefix (e.g. youtube:http://..., youtube:dQw4w9WgXcQ, youtube:track:...)
        if (query.toLowerCase().startsWith("youtube:")) {
            return normalizeYouTubePrefix(query);
        }

        // 1. Check Search Queries & Prefixes (Lavalink, PulseLink, LavaSrc)
        Matcher prefixMatcher = GENERIC_SEARCH_PREFIX_PATTERN.matcher(query);
        if (prefixMatcher.find()) {
            String prefix = prefixMatcher.group(1).toLowerCase();
            String rest = prefixMatcher.group(2).trim().replaceAll("\\s+", " ");

            // If the search query is an HTTP/HTTPS URL, do not cache as search
            if (rest.startsWith("http://") || rest.startsWith("https://")) {
                if (isYouTubeUrl(rest)) {
                    return normalizeYouTube(rest);
                }
                // Do not cache search queries of generic HTTP URLs
                return null;
            }

            if ("sprec".equals(prefix)) {
                return normalizeSpotifyRec(rest);
            }

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
        }

        // 2. Check Spotify URI
        Matcher spotifyUriMatcher = SPOTIFY_URI_PATTERN.matcher(query);
        if (spotifyUriMatcher.find()) {
            String type = spotifyUriMatcher.group(1).toLowerCase();
            String id = spotifyUriMatcher.group(2);
            return "spotify:" + type + ":" + id;
        }

        // 3. Check Spotify HTTP URL
        Matcher spotifyUrlMatcher = SPOTIFY_URL_PATTERN.matcher(query);
        if (spotifyUrlMatcher.find()) {
            String type = spotifyUrlMatcher.group(1).toLowerCase();
            String id = spotifyUrlMatcher.group(2);
            return "spotify:" + type + ":" + id;
        }

        // 4. Check YouTube (URLs & domain variations)
        if (isYouTubeUrl(query)) {
            return normalizeYouTube(query);
        }

        // If it's a bare 11-char YouTube ID (e.g. dQw4w9WgXcQ)
        if (YOUTUBE_RAW_ID_PATTERN.matcher(query).matches()) {
            return "youtube:track:" + query;
        }

        // 5. Check SoundCloud
        Matcher scMatcher = SOUNDCLOUD_URL_PATTERN.matcher(query);
        if (scMatcher.find()) {
            String cleanPath = stripTrackingAndTrailingSlash(scMatcher.group(1));
            if (cleanPath.contains("/sets/")) {
                return "soundcloud:playlist:" + cleanPath;
            }
            return "soundcloud:track:" + cleanPath;
        }

        // 6. Check Apple Music
        Matcher appleMatcher = APPLE_MUSIC_URL_PATTERN.matcher(query);
        if (appleMatcher.find()) {
            String type = appleMatcher.group(1).toLowerCase();
            String idOrPath = stripTrackingAndTrailingSlash(appleMatcher.group(2));
            if (query.contains("?i=") || query.contains("&i=")) {
                Matcher iMatcher = Pattern.compile("[?&]i=([0-9]+)").matcher(query);
                if (iMatcher.find()) {
                    return "applemusic:song:" + iMatcher.group(1);
                }
            }
            return "applemusic:" + type + ":" + idOrPath;
        }

        // 7. Check Deezer
        Matcher deezerMatcher = DEEZER_URL_PATTERN.matcher(query);
        if (deezerMatcher.find()) {
            String type = deezerMatcher.group(1).toLowerCase();
            String id = deezerMatcher.group(2);
            return "deezer:" + type + ":" + id;
        }

        // 8. Check Amazon Music
        Matcher amazonMatcher = AMAZON_MUSIC_URL_PATTERN.matcher(query);
        if (amazonMatcher.find()) {
            String type = amazonMatcher.group(1).toLowerCase();
            if (type.endsWith("s")) {
                type = type.substring(0, type.length() - 1);
            }
            String id = amazonMatcher.group(2);
            return "amazonmusic:" + type + ":" + id;
        }

        // 9. Check Tidal
        Matcher tidalUriMatcher = TIDAL_URI_PATTERN.matcher(query);
        if (tidalUriMatcher.find()) {
            return "tidal:" + tidalUriMatcher.group(1).toLowerCase() + ":" + tidalUriMatcher.group(2);
        }
        Matcher tidalUrlMatcher = TIDAL_URL_PATTERN.matcher(query);
        if (tidalUrlMatcher.find()) {
            return "tidal:" + tidalUrlMatcher.group(1).toLowerCase() + ":" + tidalUrlMatcher.group(2);
        }

        // 10. Check Yandex Music
        Matcher yandexTrackMatcher = YANDEX_TRACK_URL_PATTERN.matcher(query);
        if (yandexTrackMatcher.find()) {
            return "yandexmusic:track:" + yandexTrackMatcher.group(1);
        }
        Matcher yandexAlbumMatcher = YANDEX_ALBUM_URL_PATTERN.matcher(query);
        if (yandexAlbumMatcher.find()) {
            return "yandexmusic:album:" + yandexAlbumMatcher.group(1);
        }

        // 11. Generic HTTP/HTTPS URL -> DO NOT CACHE
        // Avoid caching live streams, presigned expiring CDN tokens, web radio, etc.
        if (query.startsWith("http://") || query.startsWith("https://")) {
            return null;
        }

        // 12. Check if query is already an established canonical key
        if (isCacheableKey(query)) {
            return query;
        }

        return null;
    }

    private String normalizeYouTubePrefix(String query) {
        String rest = query.substring("youtube:".length()).trim();
        String lowerRest = rest.toLowerCase();

        if (lowerRest.startsWith("track:")) {
            String id = rest.substring("track:".length()).trim();
            if (YOUTUBE_RAW_ID_PATTERN.matcher(id).matches()) {
                return "youtube:track:" + id;
            }
            return null;
        }

        if (lowerRest.startsWith("playlist:")) {
            String listId = rest.substring("playlist:".length()).trim();
            if (!listId.isEmpty() && !listId.contains("http:") && !listId.contains("https:")) {
                return "youtube:playlist:" + listId;
            }
            return null;
        }

        if (lowerRest.startsWith("search:")) {
            String searchStr = rest.substring("search:".length()).trim();
            if (!searchStr.isEmpty() && !searchStr.startsWith("http://") && !searchStr.startsWith("https://")) {
                return "youtube:search:" + searchStr;
            }
            return null;
        }

        // Bare 11-char ID after youtube: (e.g. youtube:dQw4w9WgXcQ)
        if (YOUTUBE_RAW_ID_PATTERN.matcher(rest).matches()) {
            return "youtube:track:" + rest;
        }

        // URL after youtube: (e.g. youtube:https://www.youtube.com/watch?v=dQw4w9WgXcQ)
        if (rest.startsWith("http://") || rest.startsWith("https://")) {
            return normalizeYouTube(rest);
        }

        return null;
    }

    public boolean isYouTubeUrl(String url) {
        if (url == null || url.trim().isEmpty()) {
            return false;
        }
        String u = url.trim();
        return YT_DOMAIN_PATTERN.matcher(u).find() || YOUTU_BE_PATTERN.matcher(u).find();
    }

    public boolean isRecognizedPlatformUrl(String url) {
        if (url == null || url.trim().isEmpty()) {
            return false;
        }
        String u = url.trim();
        return isYouTubeUrl(u)
                || SPOTIFY_URL_PATTERN.matcher(u).find()
                || SOUNDCLOUD_URL_PATTERN.matcher(u).find()
                || APPLE_MUSIC_URL_PATTERN.matcher(u).find()
                || DEEZER_URL_PATTERN.matcher(u).find()
                || AMAZON_MUSIC_URL_PATTERN.matcher(u).find()
                || TIDAL_URL_PATTERN.matcher(u).find()
                || YANDEX_TRACK_URL_PATTERN.matcher(u).find()
                || YANDEX_ALBUM_URL_PATTERN.matcher(u).find();
    }

    /**
     * Determines if an identifier/URL is eligible for caching in Redis.
     * Generic HTTP/HTTPS streams, invalid YouTube URLs, and malformed inputs return false.
     *
     * @param identifier The raw input query or URL
     * @return true if cacheable, false otherwise
     */
    public boolean isCacheable(String identifier) {
        if (identifier == null || identifier.trim().isEmpty()) {
            return false;
        }
        String query = identifier.trim();

        // Reject direct generic HTTP/HTTPS URLs (must not be cached)
        if (query.startsWith("http://") || query.startsWith("https://")) {
            if (!isRecognizedPlatformUrl(query)) {
                return false;
            }
        }

        String canonical = normalize(query);
        return canonical != null && !canonical.isEmpty() && isCacheableKey(canonical);
    }

    /**
     * Validates whether a normalized canonical key meets strict criteria for caching.
     * Keys containing "youtube:http", "url:", or starting with "http:" are strictly forbidden.
     *
     * @param canonicalKey The normalized canonical key
     * @return true if valid and cacheable, false otherwise
     */
    public boolean isCacheableKey(String canonicalKey) {
        if (canonicalKey == null || canonicalKey.trim().isEmpty()) {
            return false;
        }
        String lower = canonicalKey.toLowerCase();

        // Strictly reject any HTTP/HTTPS URL based keys or legacy generic url: keys
        if (lower.startsWith("url:")
                || lower.startsWith("http:")
                || lower.startsWith("https:")
                || lower.startsWith("http://")
                || lower.startsWith("https://")) {
            return false;
        }

        // Strictly reject any key containing youtube:http
        if (lower.startsWith("youtube:http:")
                || lower.startsWith("youtube:https:")
                || lower.startsWith("youtube://")
                || lower.contains("youtube:http")) {
            return false;
        }

        // YouTube canonical keys MUST be track, playlist, or search
        if (lower.startsWith("youtube:")) {
            return lower.startsWith("youtube:track:")
                    || lower.startsWith("youtube:playlist:")
                    || lower.startsWith("youtube:search:");
        }

        if (lower.startsWith("youtubemusic:")) {
            return lower.startsWith("youtubemusic:track:")
                    || lower.startsWith("youtubemusic:playlist:")
                    || lower.startsWith("youtubemusic:search:");
        }

        if (lower.startsWith("spotify:")) {
            return lower.startsWith("spotify:track:")
                    || lower.startsWith("spotify:playlist:")
                    || lower.startsWith("spotify:album:")
                    || lower.startsWith("spotify:artist:")
                    || lower.startsWith("spotify:search:")
                    || lower.startsWith("spotify:rec:");
        }

        if (lower.startsWith("soundcloud:")) {
            return lower.startsWith("soundcloud:track:")
                    || lower.startsWith("soundcloud:playlist:")
                    || lower.startsWith("soundcloud:search:");
        }

        if (lower.startsWith("applemusic:")) {
            return lower.startsWith("applemusic:song:")
                    || lower.startsWith("applemusic:track:")
                    || lower.startsWith("applemusic:album:")
                    || lower.startsWith("applemusic:playlist:")
                    || lower.startsWith("applemusic:search:");
        }

        if (lower.startsWith("deezer:")) {
            return lower.startsWith("deezer:track:")
                    || lower.startsWith("deezer:album:")
                    || lower.startsWith("deezer:playlist:")
                    || lower.startsWith("deezer:search:")
                    || lower.startsWith("deezer:isrc:");
        }

        if (lower.startsWith("amazonmusic:")) {
            return lower.startsWith("amazonmusic:track:")
                    || lower.startsWith("amazonmusic:album:")
                    || lower.startsWith("amazonmusic:playlist:")
                    || lower.startsWith("amazonmusic:search:");
        }

        if (lower.startsWith("tidal:")) {
            return lower.startsWith("tidal:track:")
                    || lower.startsWith("tidal:album:")
                    || lower.startsWith("tidal:playlist:")
                    || lower.startsWith("tidal:search:");
        }

        if (lower.startsWith("yandexmusic:")) {
            return lower.startsWith("yandexmusic:track:")
                    || lower.startsWith("yandexmusic:album:")
                    || lower.startsWith("yandexmusic:playlist:")
                    || lower.startsWith("yandexmusic:search:");
        }

        for (String platform : SEARCH_PREFIX_MAP.values()) {
            if (lower.startsWith(platform + ":")) {
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

    private String normalizeYouTube(String url) {
        if (url == null || url.trim().isEmpty()) {
            return null;
        }

        // 1. Detect playlist parameter 'list='
        Matcher listMatcher = YOUTUBE_LIST_PATTERN.matcher(url);
        if (listMatcher.find()) {
            String listId = listMatcher.group(1);
            return "youtube:playlist:" + listId;
        }

        // 2. youtu.be shortlinks
        Matcher youtuBeMatcher = YOUTU_BE_PATTERN.matcher(url);
        if (youtuBeMatcher.find()) {
            return "youtube:track:" + youtuBeMatcher.group(1);
        }

        // 3. YouTube Shorts
        Matcher shortsMatcher = YOUTUBE_SHORTS_PATTERN.matcher(url);
        if (shortsMatcher.find()) {
            return "youtube:track:" + shortsMatcher.group(1);
        }

        // 4. YouTube Live
        Matcher liveMatcher = YOUTUBE_LIVE_PATTERN.matcher(url);
        if (liveMatcher.find()) {
            return "youtube:track:" + liveMatcher.group(1);
        }

        // 5. YouTube Embed
        Matcher embedMatcher = YOUTUBE_EMBED_PATTERN.matcher(url);
        if (embedMatcher.find()) {
            return "youtube:track:" + embedMatcher.group(1);
        }

        // 6. YouTube /v/
        Matcher vMatcher = YOUTUBE_V_PATTERN.matcher(url);
        if (vMatcher.find()) {
            return "youtube:track:" + vMatcher.group(1);
        }

        // 7. YouTube /watch/ID
        Matcher watchPathMatcher = YOUTUBE_WATCH_PATH_PATTERN.matcher(url);
        if (watchPathMatcher.find()) {
            return "youtube:track:" + watchPathMatcher.group(1);
        }

        // 8. Standard ?v=ID parameter
        Matcher videoIdMatcher = YOUTUBE_VIDEO_ID_PARAM.matcher(url);
        if (videoIdMatcher.find()) {
            return "youtube:track:" + videoIdMatcher.group(1);
        }

        // If not a recognized YouTube video ID or playlist ID, DO NOT CACHE
        // Never return "youtube:" + url
        return null;
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
