package com.github.petergamez.trackcache.normalizer;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Normalizes SoundCloud track and set (playlist) URLs while rejecting system/navigation paths.
 */
@Component
@Order(4)
public class SoundCloudNormalizer implements PlatformNormalizer {

    private static final Set<String> SOUNDCLOUD_RESERVED_SEGMENTS = new HashSet<>(Arrays.asList(
            "search", "discover", "stream", "you", "upload", "settings",
            "messages", "notifications", "charts", "pages", "terms-of-use", "people"
    ));

    private static final Pattern SOUNDCLOUD_URL_PATTERN = Pattern.compile(
            "^(?:https?://)?(?:www\\.|m\\.)?soundcloud\\.com/([a-zA-Z0-9-_]+/[a-zA-Z0-9-_/]+)",
            Pattern.CASE_INSENSITIVE
    );

    @Override
    public String normalize(String query) {
        Matcher scMatcher = SOUNDCLOUD_URL_PATTERN.matcher(query);
        if (scMatcher.find()) {
            String cleanPath = PlatformNormalizer.stripTrackingAndTrailingSlash(scMatcher.group(1));
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
}
