package com.github.petergamez.trackcache.normalizer;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class UrlNormalizer {

    // Search prefixes mapping from Lavalink platform names
    private static final Map<String, String> SEARCH_PREFIX_MAP = new HashMap<>();

    // Allowed canonical subkeys per platform
    private static final Map<String, String[]> PLATFORM_SUBKEYS = new HashMap<>();

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

    private final List<PlatformNormalizer> platformNormalizers;

    public static List<PlatformNormalizer> createDefaultPlatformNormalizers() {
        return List.of(
                new BilibiliNormalizer(),
                new YouTubeNormalizer(),
                new SpotifyNormalizer(),
                new SoundCloudNormalizer(),
                new AppleMusicNormalizer(),
                new DeezerNormalizer(),
                new AmazonMusicNormalizer(),
                new TidalNormalizer(),
                new YandexMusicNormalizer()
        );
    }

    @Autowired
    public UrlNormalizer(@Nullable List<PlatformNormalizer> platformNormalizers) {
        this.platformNormalizers = (platformNormalizers != null && !platformNormalizers.isEmpty())
                ? platformNormalizers
                : createDefaultPlatformNormalizers();
    }

    public UrlNormalizer() {
        this(createDefaultPlatformNormalizers());
    }

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

        // 3. Platform Normalizers (executed in priority order)
        for (PlatformNormalizer normalizer : platformNormalizers) {
            String result = normalizer.normalize(query);
            if (result != null) {
                return result;
            }
        }

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
}
