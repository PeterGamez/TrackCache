package com.github.petergamez.trackcache.normalizer;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Normalizes Bilibili video (BV/av) and audio (au) identifiers.
 * Runs before YouTube to avoid bare 11-char 'av123456789' collisions.
 */
@Component
@Order(1)
public class BilibiliNormalizer implements PlatformNormalizer {

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

    @Override
    public String normalize(String query) {
        Matcher biliVideoMatcher = BILIBILI_VIDEO_URL_PATTERN.matcher(query);
        if (biliVideoMatcher.find()) {
            return "bilibili:video:" + biliVideoMatcher.group(1);
        }
        Matcher biliAudioMatcher = BILIBILI_AUDIO_URL_PATTERN.matcher(query);
        if (biliAudioMatcher.find()) {
            return "bilibili:audio:" + biliAudioMatcher.group(1);
        }
        if (BILIBILI_RAW_ID_PATTERN.matcher(query).matches()) {
            return query.toLowerCase(Locale.ROOT).startsWith("au")
                    ? "bilibili:audio:" + query
                    : "bilibili:video:" + query;
        }
        return null;
    }
}
