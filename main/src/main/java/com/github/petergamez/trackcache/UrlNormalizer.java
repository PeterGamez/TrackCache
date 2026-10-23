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
     *
     * @param identifier The raw identifier/URL/query from /v4/loadtracks
     * @return Canonical key representation
     */
    public String normalize(String identifier) {
        if (identifier == null || identifier.trim().isEmpty()) {
            return "";
        }

        String query = identifier.trim();

        // 1. Check Search Queries & Prefixes (Lavalink, PulseLink, LavaSrc)
        Matcher prefixMatcher = GENERIC_SEARCH_PREFIX_PATTERN.matcher(query);
        if (prefixMatcher.find()) {
            String prefix = prefixMatcher.group(1).toLowerCase();
            String rest = prefixMatcher.group(2).trim().replaceAll("\\s+", " ");

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
        if (YT_DOMAIN_PATTERN.matcher(query).find() || YOUTU_BE_PATTERN.matcher(query).find()) {
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

        // 9. Generic HTTP/HTTPS URL Normalization
        if (query.startsWith("http://") || query.startsWith("https://")) {
            return normalizeGenericUrl(query);
        }

        // 10. Fallback for any other identifier
        return query.replaceAll("\\s+", " ");
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
     *
     * @param identifier The raw input query/URL or existing key
     * @param prefix The key prefix (e.g. "trackcache:")
     * @return Full Redis key
     */
    public String toFullCacheKey(String identifier, String prefix) {
        String p = prefix != null ? prefix : "";
        if (identifier == null) {
            return p;
        }
        if (!p.isEmpty() && identifier.startsWith(p)) {
            return identifier;
        }
        String canonical = normalize(identifier);
        return p + canonical;
    }

    private String normalizeYouTube(String url) {
        // Detect playlist parameter 'list='
        Matcher listMatcher = YOUTUBE_LIST_PATTERN.matcher(url);
        if (listMatcher.find()) {
            String listId = listMatcher.group(1);
            return "youtube:playlist:" + listId;
        }

        // Single video URLs
        Matcher youtuBeMatcher = YOUTU_BE_PATTERN.matcher(url);
        if (youtuBeMatcher.find()) {
            return "youtube:track:" + youtuBeMatcher.group(1);
        }

        Matcher shortsMatcher = YOUTUBE_SHORTS_PATTERN.matcher(url);
        if (shortsMatcher.find()) {
            return "youtube:track:" + shortsMatcher.group(1);
        }

        Matcher embedMatcher = YOUTUBE_EMBED_PATTERN.matcher(url);
        if (embedMatcher.find()) {
            return "youtube:track:" + embedMatcher.group(1);
        }

        Matcher vMatcher = YOUTUBE_V_PATTERN.matcher(url);
        if (vMatcher.find()) {
            return "youtube:track:" + vMatcher.group(1);
        }

        Matcher videoIdMatcher = YOUTUBE_VIDEO_ID_PARAM.matcher(url);
        if (videoIdMatcher.find()) {
            return "youtube:track:" + videoIdMatcher.group(1);
        }

        return "youtube:" + stripTrackingAndTrailingSlash(url);
    }

    private String normalizeGenericUrl(String urlString) {
        try {
            URI uri = URI.create(urlString);
            String scheme = uri.getScheme() != null ? uri.getScheme().toLowerCase() : "https";
            String host = uri.getHost() != null ? uri.getHost().toLowerCase() : "";
            String path = uri.getPath() != null ? uri.getPath() : "";

            while (path.endsWith("/") && path.length() > 1) {
                path = path.substring(0, path.length() - 1);
            }

            StringBuilder cleanQuery = new StringBuilder();
            if (uri.getQuery() != null && !uri.getQuery().isEmpty()) {
                String[] pairs = uri.getQuery().split("&");
                for (String pair : pairs) {
                    int eqIndex = pair.indexOf('=');
                    String key = eqIndex != -1 ? pair.substring(0, eqIndex) : pair;
                    String lowerKey = key.toLowerCase();
                    if (!TRACKING_PARAMS.contains(lowerKey) && !lowerKey.startsWith("utm_")) {
                        if (cleanQuery.length() > 0) {
                            cleanQuery.append("&");
                        }
                        cleanQuery.append(pair);
                    }
                }
            }

            StringBuilder cleanUrl = new StringBuilder();
            cleanUrl.append(scheme).append("://").append(host).append(path);
            if (cleanQuery.length() > 0) {
                cleanUrl.append("?").append(cleanQuery);
            }

            return "url:" + cleanUrl.toString();
        } catch (Exception e) {
            log.debug("Failed to parse URI: {}, falling back to basic cleanup", urlString, e);
            return "url:" + stripTrackingAndTrailingSlash(urlString);
        }
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
