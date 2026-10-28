package com.github.petergamez.trackcache.normalizer;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Normalizes Tidal track, album, and playlist URLs and URIs.
 */
@Component
@Order(8)
public class TidalNormalizer implements PlatformNormalizer {

    private static final Pattern TIDAL_URL_PATTERN = Pattern.compile(
            "^(?:https?://)?(?:listen\\.|browse\\.)?tidal\\.com/(?:browse/)?(track|playlist|album)/([0-9]+)",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern TIDAL_URI_PATTERN = Pattern.compile(
            "^tidal:(track|playlist|album):([0-9]+)$",
            Pattern.CASE_INSENSITIVE
    );

    @Override
    public String normalize(String query) {
        Matcher matcher = TIDAL_URI_PATTERN.matcher(query);
        if (!matcher.find()) {
            matcher = TIDAL_URL_PATTERN.matcher(query);
            if (!matcher.find()) {
                return null;
            }
        }
        return "tidal:" + matcher.group(1).toLowerCase(Locale.ROOT) + ":" + matcher.group(2);
    }
}
