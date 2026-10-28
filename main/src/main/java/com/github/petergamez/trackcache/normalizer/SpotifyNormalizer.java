package com.github.petergamez.trackcache.normalizer;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Normalizes Spotify track, playlist, album, and artist URLs and URIs.
 */
@Component
@Order(3)
public class SpotifyNormalizer implements PlatformNormalizer {

    private static final Pattern SPOTIFY_URL_PATTERN = Pattern.compile(
            "^(?:https?://)?(?:open|play)\\.spotify\\.com/(?:intl-[^/]+/)?(track|playlist|album|artist)/([a-zA-Z0-9]+)",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern SPOTIFY_URI_PATTERN = Pattern.compile(
            "^spotify:(track|playlist|album|artist):([a-zA-Z0-9]+)$",
            Pattern.CASE_INSENSITIVE
    );

    @Override
    public String normalize(String query) {
        Matcher matcher = SPOTIFY_URI_PATTERN.matcher(query);
        if (!matcher.find()) {
            matcher = SPOTIFY_URL_PATTERN.matcher(query);
            if (!matcher.find()) {
                return null;
            }
        }
        return "spotify:" + matcher.group(1).toLowerCase(Locale.ROOT) + ":" + matcher.group(2);
    }
}
