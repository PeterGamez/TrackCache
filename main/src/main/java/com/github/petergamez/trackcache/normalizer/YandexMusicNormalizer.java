package com.github.petergamez.trackcache.normalizer;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Normalizes Yandex Music tracks and albums.
 */
@Component
@Order(9)
public class YandexMusicNormalizer implements PlatformNormalizer {

    private static final Pattern YANDEX_TRACK_URL_PATTERN = Pattern.compile(
            "^(?:https?://)?music\\.yandex\\.[a-z]+/(?:album/[0-9]+/)?track/([0-9]+)",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern YANDEX_ALBUM_URL_PATTERN = Pattern.compile(
            "^(?:https?://)?music\\.yandex\\.[a-z]+/album/([0-9]+)",
            Pattern.CASE_INSENSITIVE
    );

    @Override
    public String normalize(String query) {
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
}
