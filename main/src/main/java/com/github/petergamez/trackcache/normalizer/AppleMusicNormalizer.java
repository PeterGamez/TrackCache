package com.github.petergamez.trackcache.normalizer;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Normalizes Apple Music songs, albums, and playlists.
 */
@Component
@Order(5)
public class AppleMusicNormalizer implements PlatformNormalizer {

    private static final Pattern APPLE_MUSIC_URL_PATTERN = Pattern.compile(
            "^(?:https?://)?music\\.apple\\.com/(?:[a-z]{2}(?:-[a-z]{2})?/)?(album|playlist|song)/([^?#]+)",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern APPLE_MUSIC_I_PARAM = Pattern.compile("[?&]i=([0-9]+)");

    @Override
    public String normalize(String query) {
        Matcher appleMatcher = APPLE_MUSIC_URL_PATTERN.matcher(query);
        if (appleMatcher.find()) {
            if (query.contains("?i=") || query.contains("&i=")) {
                Matcher iMatcher = APPLE_MUSIC_I_PARAM.matcher(query);
                if (iMatcher.find()) {
                    return "applemusic:song:" + iMatcher.group(1);
                }
            }
            String idOrPath = PlatformNormalizer.stripTrackingAndTrailingSlash(appleMatcher.group(2));
            return idOrPath.isEmpty() ? null : "applemusic:" + appleMatcher.group(1).toLowerCase(Locale.ROOT) + ":" + idOrPath;
        }
        return null;
    }
}
