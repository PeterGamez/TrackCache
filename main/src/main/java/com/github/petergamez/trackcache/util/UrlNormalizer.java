package com.github.petergamez.trackcache.util;

import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class UrlNormalizer {

    // Search prefixes mapping from Lavalink platform names
    private static final Map<String, String> SEARCH_PREFIX_MAP = new HashMap<>();

    // Allowed canonical subkeys per platform
    private static final Map<String, String[]> PLATFORM_SUBKEYS = new HashMap<>();

    private static final Set<String> SOUNDCLOUD_RESERVED_SEGMENTS = new HashSet<>(Arrays.asList(
            "search", "discover", "stream", "you", "upload", "settings",
            "messages", "notifications", "charts", "pages", "terms-of-use", "people"
    ));

    private static final String[] DEFAULT_SUBKEYS = {
            "track:", "album:", "playlist:", "artist:", "song:", "video:", "audio:", "search:"
    };

    static {
        SEARCH_PREFIX_MAP.put("ytsearch", "youtube");
        SEARCH_PREFIX_MAP.put("ytmsearch", "youtubemusic");
        SEARCH_PREFIX_MAP.put("scsearch", "soundcloud");
        SEARCH_PREFIX_MAP.put("spsearch", "spotify");
        SEARCH_PREFIX_MAP.put("amzsearch", "amazonmusic");
        SEARCH_PREFIX_MAP.put("amz", "amazonmusic");
        SEARCH_PREFIX_MAP.put("amsearch", "applemusic");
        SEARCH_PREFIX_MAP.put("dzsearch", "deezer");
        SEARCH_PREFIX_MAP.put("jssearch", "jiosaavn");
        SEARCH_PREFIX_MAP.put("gnsearch", "gaana");
        SEARCH_PREFIX_MAP.put("admsearch", "audiomack");
        SEARCH_PREFIX_MAP.put("tdsearch", "tidal");
        SEARCH_PREFIX_MAP.put("ymsearch", "yandexmusic");
        SEARCH_PREFIX_MAP.put("vksearch", "vkmusic");
        SEARCH_PREFIX_MAP.put("qbsearch", "qobuz");
        SEARCH_PREFIX_MAP.put("pdsearch", "pandora");
        SEARCH_PREFIX_MAP.put("bilisearch", "bilibili");

        for (String platform : SEARCH_PREFIX_MAP.values()) {
            int colon = platform.indexOf(':');
            String rootPlatform = colon > 0 ? platform.substring(0, colon) : platform;
            PLATFORM_SUBKEYS.put(rootPlatform, DEFAULT_SUBKEYS);
        }
        PLATFORM_SUBKEYS.put("youtube", new String[]{"track:", "playlist:", "search:"});
        PLATFORM_SUBKEYS.put("youtubemusic", new String[]{"track:", "playlist:", "search:"});
        PLATFORM_SUBKEYS.put("spotify", new String[]{"track:", "playlist:", "album:", "artist:", "search:"});
        PLATFORM_SUBKEYS.put("soundcloud", new String[]{"track:", "playlist:", "search:"});
        PLATFORM_SUBKEYS.put("applemusic", new String[]{"song:", "track:", "album:", "playlist:", "search:"});
        PLATFORM_SUBKEYS.put("deezer", new String[]{"track:", "album:", "playlist:", "search:"});
        PLATFORM_SUBKEYS.put("amazonmusic", new String[]{"track:", "album:", "playlist:", "search:"});
        PLATFORM_SUBKEYS.put("tidal", new String[]{"track:", "album:", "playlist:", "search:"});
        PLATFORM_SUBKEYS.put("yandexmusic", new String[]{"track:", "album:", "playlist:", "search:"});
        PLATFORM_SUBKEYS.put("bilibili", new String[]{"video:", "audio:", "search:"});
    }

    private static final Pattern GENERIC_SEARCH_PREFIX_PATTERN = Pattern.compile("^([a-zA-Z0-9_]+):\\s*(.*)$");
    private static final Pattern WHITESPACE_PATTERN = Pattern.compile("\\s+");

    // YouTube Patterns
    private static final Pattern YT_DOMAIN_PATTERN = Pattern.compile(
            "^(?:https?://)?(?:www\\.|m\\.|music\\.)?(?:youtube(?:-nocookie)?\\.com|youtu\\.be)(?:[:/?#]|$)",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern YOUTUBE_LIST_PATTERN = Pattern.compile("[?&]list=([a-zA-Z0-9_-]+)");
    private static final Pattern YOUTUBE_PATH_ID_PATTERN = Pattern.compile(
            "(?:youtu\\.be/|/(?:shorts|live|embed|v|watch)/)([a-zA-Z0-9_-]{11})",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern YOUTUBE_VIDEO_ID_PARAM = Pattern.compile("[?&]v=([a-zA-Z0-9_-]{11})");
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

    // SoundCloud Pattern
    private static final Pattern SOUNDCLOUD_URL_PATTERN = Pattern.compile(
            "^(?:https?://)?(?:www\\.|m\\.)?soundcloud\\.com/([a-zA-Z0-9-_]+/[a-zA-Z0-9-_/]+)",
            Pattern.CASE_INSENSITIVE
    );

    // Apple Music Patterns
    private static final Pattern APPLE_MUSIC_URL_PATTERN = Pattern.compile(
            "^(?:https?://)?music\\.apple\\.com/[a-z]{2}/(album|playlist|song)/([^?#]+)",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern APPLE_MUSIC_I_PARAM = Pattern.compile("[?&]i=([0-9]+)");

    // Deezer Pattern
    private static final Pattern DEEZER_URL_PATTERN = Pattern.compile(
            "^(?:https?://)?(?:www\\.)?deezer\\.com/(?:[a-z]{2}/)?(track|playlist|album)/([0-9]+)",
            Pattern.CASE_INSENSITIVE
    );

    // Amazon Music Pattern
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

    // Bilibili Patterns
    private static final Pattern BILIBILI_VIDEO_URL_PATTERN = Pattern.compile(
            "^(?:https?://)?(?:(?:www\\.|m\\.)?bilibili\\.com/video|b23\\.tv)/((?:BV[a-zA-Z0-9]+)|(?:av[0-9]+))",
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

        // 1. If query is already an established canonical key, normalize search whitespace/case and return
        if (isCacheableKey(query)) {
            return normalizeCanonicalKey(query);
        }

        // 2. Handle prefix patterns (<prefix>:<rest>)
        if (!query.startsWith("http://") && !query.startsWith("https://")) {
            Matcher prefixMatcher = GENERIC_SEARCH_PREFIX_PATTERN.matcher(query);
            if (prefixMatcher.find()) {
                String prefix = prefixMatcher.group(1).toLowerCase(Locale.ROOT);
                String rest = prefixMatcher.group(2).trim();
                if (rest.isEmpty()) {
                    return null;
                }

                // If rest is an HTTP/HTTPS URL, unwrap and normalize
                if (rest.startsWith("http://") || rest.startsWith("https://")) {
                    return normalize(rest);
                }

                // Standard search prefix mapping
                String mappedPlatform = SEARCH_PREFIX_MAP.get(prefix);
                if (mappedPlatform != null) {
                    if (mappedPlatform.contains(":")) {
                        return mappedPlatform + ":" + rest;
                    }
                    return buildSearchKey(mappedPlatform, rest);
                } else if (prefix.endsWith("search") && prefix.length() > 6) {
                    String candidatePlatform = prefix.substring(0, prefix.length() - 6);
                    if (PLATFORM_SUBKEYS.containsKey(candidatePlatform)) {
                        return buildSearchKey(candidatePlatform, rest);
                    }
                }

                // Client prefix fallback (e.g. youtube:dQw4w9WgXcQ, bilibili:BV1xx411c7mD)
                String normalizedRest = normalize(rest);
                if (normalizedRest != null) {
                    return normalizedRest;
                }
            }
        }

        // 3. Platform Normalizers (Bilibili checked before YouTube bare 11-char ID to avoid 'av123456789' collision)
        String result;
        if ((result = normalizeBilibili(query)) != null) return result;
        if ((result = normalizeYouTube(query)) != null) return result;
        if ((result = normalizeSpotify(query)) != null) return result;
        if ((result = normalizeSoundCloud(query)) != null) return result;
        if ((result = normalizeAppleMusic(query)) != null) return result;
        if ((result = normalizeDeezer(query)) != null) return result;
        if ((result = normalizeAmazonMusic(query)) != null) return result;
        if ((result = normalizeTidal(query)) != null) return result;
        if ((result = normalizeYandexMusic(query)) != null) return result;

        return null;
    }

    private String buildSearchKey(String platform, String rawQuery) {
        if (rawQuery == null) {
            return null;
        }
        String normalizedSearch = WHITESPACE_PATTERN.matcher(rawQuery.trim()).replaceAll(" ").toLowerCase(Locale.ROOT);
        return normalizedSearch.isEmpty() ? null : platform + ":search:" + normalizedSearch;
    }

    private String normalizeCanonicalKey(String canonicalKey) {
        String lower = canonicalKey.toLowerCase(Locale.ROOT);
        int searchIdx = lower.indexOf(":search:");
        if (searchIdx > 0) {
            return buildSearchKey(lower.substring(0, searchIdx), canonicalKey.substring(searchIdx + 8));
        }
        return canonicalKey;
    }

    private String normalizeYouTube(String query) {
        if (YOUTUBE_RAW_ID_PATTERN.matcher(query).matches()) {
            return "youtube:track:" + query;
        }

        if (!YT_DOMAIN_PATTERN.matcher(query).find()) {
            return null;
        }

        Matcher listMatcher = YOUTUBE_LIST_PATTERN.matcher(query);
        if (listMatcher.find()) {
            return "youtube:playlist:" + listMatcher.group(1);
        }

        Matcher pathMatcher = YOUTUBE_PATH_ID_PATTERN.matcher(query);
        if (pathMatcher.find()) {
            return "youtube:track:" + pathMatcher.group(1);
        }

        Matcher videoIdMatcher = YOUTUBE_VIDEO_ID_PARAM.matcher(query);
        if (videoIdMatcher.find()) {
            return "youtube:track:" + videoIdMatcher.group(1);
        }

        return null;
    }

    private String normalizeSpotify(String query) {
        Matcher matcher = SPOTIFY_URI_PATTERN.matcher(query);
        if (!matcher.find()) {
            matcher = SPOTIFY_URL_PATTERN.matcher(query);
            if (!matcher.find()) {
                return null;
            }
        }
        return "spotify:" + matcher.group(1).toLowerCase(Locale.ROOT) + ":" + matcher.group(2);
    }

    private String normalizeBilibili(String query) {
        Matcher biliVideoMatcher = BILIBILI_VIDEO_URL_PATTERN.matcher(query);
        if (biliVideoMatcher.find()) {
            return "bilibili:video:" + biliVideoMatcher.group(1);
        }
        Matcher biliAudioMatcher = BILIBILI_AUDIO_URL_PATTERN.matcher(query);
        if (biliAudioMatcher.find()) {
            return "bilibili:audio:" + biliAudioMatcher.group(1);
        }
        if (BILIBILI_RAW_ID_PATTERN.matcher(query).matches()) {
            return query.toLowerCase(Locale.ROOT).startsWith("au") ? "bilibili:audio:" + query : "bilibili:video:" + query;
        }
        return null;
    }

    private String normalizeSoundCloud(String query) {
        Matcher scMatcher = SOUNDCLOUD_URL_PATTERN.matcher(query);
        if (scMatcher.find()) {
            String cleanPath = stripTrackingAndTrailingSlash(scMatcher.group(1));
            String[] segments = cleanPath.split("/");
            if (segments.length < 2 || segments[0].isEmpty() || segments[1].isEmpty()
                    || SOUNDCLOUD_RESERVED_SEGMENTS.contains(segments[0].toLowerCase(Locale.ROOT))) {
                return null;
            }
            if ("sets".equalsIgnoreCase(segments[1])) {
                return (segments.length >= 3 && !segments[2].isEmpty()) ? "soundcloud:playlist:" + cleanPath : null;
            }
            return "soundcloud:track:" + cleanPath;
        }
        return null;
    }

    private String normalizeAppleMusic(String query) {
        Matcher appleMatcher = APPLE_MUSIC_URL_PATTERN.matcher(query);
        if (appleMatcher.find()) {
            if (query.contains("?i=") || query.contains("&i=")) {
                Matcher iMatcher = APPLE_MUSIC_I_PARAM.matcher(query);
                if (iMatcher.find()) {
                    return "applemusic:song:" + iMatcher.group(1);
                }
            }
            String idOrPath = stripTrackingAndTrailingSlash(appleMatcher.group(2));
            return idOrPath.isEmpty() ? null : "applemusic:" + appleMatcher.group(1).toLowerCase(Locale.ROOT) + ":" + idOrPath;
        }
        return null;
    }

    private String normalizeDeezer(String query) {
        Matcher deezerMatcher = DEEZER_URL_PATTERN.matcher(query);
        if (deezerMatcher.find()) {
            return "deezer:" + deezerMatcher.group(1).toLowerCase(Locale.ROOT) + ":" + deezerMatcher.group(2);
        }
        return null;
    }

    private String normalizeAmazonMusic(String query) {
        Matcher amazonMatcher = AMAZON_MUSIC_URL_PATTERN.matcher(query);
        if (amazonMatcher.find()) {
            String type = amazonMatcher.group(1).toLowerCase(Locale.ROOT);
            if (type.endsWith("s")) {
                type = type.substring(0, type.length() - 1);
            }
            return "amazonmusic:" + type + ":" + amazonMatcher.group(2);
        }
        return null;
    }

    private String normalizeTidal(String query) {
        Matcher matcher = TIDAL_URI_PATTERN.matcher(query);
        if (!matcher.find()) {
            matcher = TIDAL_URL_PATTERN.matcher(query);
            if (!matcher.find()) {
                return null;
            }
        }
        return "tidal:" + matcher.group(1).toLowerCase(Locale.ROOT) + ":" + matcher.group(2);
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
        String lower = canonicalKey.trim().toLowerCase(Locale.ROOT);

        if (lower.contains("http:") || lower.contains("https:") || lower.contains("://") || lower.startsWith("url:")) {
            return false;
        }

        int colonIdx = lower.indexOf(':');
        if (colonIdx <= 0) {
            return false;
        }

        String[] allowedTypes = PLATFORM_SUBKEYS.get(lower.substring(0, colonIdx));
        if (allowedTypes == null) {
            return false;
        }

        String sub = lower.substring(colonIdx + 1);
        for (String type : allowedTypes) {
            if (sub.startsWith(type) && !sub.substring(type.length()).trim().isEmpty()) {
                return true;
            }
        }
        return false;
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
        if (identifier == null || identifier.trim().isEmpty()) {
            return null;
        }
        String p = prefix != null ? prefix : "";
        String trimmed = identifier.trim();
        if (!p.isEmpty() && trimmed.startsWith(p)) {
            String stripped = trimmed.substring(p.length());
            if (isCacheableKey(stripped)) {
                String normalized = normalizeCanonicalKey(stripped);
                return normalized != null ? p + normalized : null;
            }
            return null;
        }
        String canonical = normalize(trimmed);
        return (canonical != null && isCacheableKey(canonical)) ? p + canonical : null;
    }

    private String stripTrackingAndTrailingSlash(String url) {
        String clean = url;
        int q = clean.indexOf('?');
        if (q != -1) {
            clean = clean.substring(0, q);
        }
        int hash = clean.indexOf('#');
        if (hash != -1) {
            clean = clean.substring(0, hash);
        }
        while (clean.endsWith("/")) {
            clean = clean.substring(0, clean.length() - 1);
        }
        return clean;
    }
}

