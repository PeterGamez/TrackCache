package com.github.petergamez.trackcache.normalizer;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Normalizes Amazon Music tracks, albums, and playlists.
 */
@Component
@Order(7)
public class AmazonMusicNormalizer implements PlatformNormalizer {

    private static final Pattern AMAZON_MUSIC_URL_PATTERN = Pattern.compile(
            "^(?:https?://)?(?:music|www)\\.amazon\\.[a-z.]+(?:/[a-z]{2})?/(albums|tracks|playlists)/([a-zA-Z0-9]+)",
            Pattern.CASE_INSENSITIVE
    );

    @Override
    public String normalize(String query) {
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
}
