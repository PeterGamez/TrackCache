package com.github.petergamez.trackcache.normalizer;

/**
 * Strategy interface for parsing platform-specific URLs, URIs, and raw identifiers.
 */
public interface PlatformNormalizer {

    /**
     * Normalizes a platform query/URL into its canonical key.
     *
     * @param query The input identifier or URL
     * @return The canonical platform key (e.g. "youtube:track:dQw4w9WgXcQ"), or null if not handled
     */
    String normalize(String query);

    /**
     * Helper to strip query parameters, fragments, and trailing slashes from URLs.
     */
    static String stripTrackingAndTrailingSlash(String url) {
        if (url == null) {
            return null;
        }
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
