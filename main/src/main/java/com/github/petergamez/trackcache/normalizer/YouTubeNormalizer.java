package com.github.petergamez.trackcache.normalizer;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Normalizes YouTube video URLs, shorts, live streams, embed links, playlists, and 11-char IDs.
 */
@Component
@Order(2)
public class YouTubeNormalizer implements PlatformNormalizer {

    private static final Pattern YT_DOMAIN_PATTERN = Pattern.compile(
            "^(?:https?://)?(?:www\\.|m\\.|music\\.)?(?:youtube(?:-nocookie)?\\.com|youtu\\.be)(?:[:/?#]|$)",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern YOUTUBE_LIST_PATTERN = Pattern.compile("[?&]list=([a-zA-Z0-9_-]+)");
    private static final Pattern YOUTUBE_PATH_ID_PATTERN = Pattern.compile(
            "(?:youtu\\.be/|/(?:shorts|live|embed|v|watch)/)([a-zA-Z0-9_-]{11})",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern YOUTUBE_VIDEO_ID_PARAM = Pattern.compile("[?&]v=([a-zA-Z0-9_-]{11})");
    private static final Pattern YOUTUBE_RAW_ID_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]{11}$");

    @Override
    public String normalize(String query) {
        if (YOUTUBE_RAW_ID_PATTERN.matcher(query).matches()) {
            return "youtube:track:" + query;
        }

        if (!YT_DOMAIN_PATTERN.matcher(query).find()) {
            return null;
        }

        Matcher listMatcher = YOUTUBE_LIST_PATTERN.matcher(query);
        if (listMatcher.find()) {
            return "youtube:playlist:" + listMatcher.group(1);
        }

        Matcher pathMatcher = YOUTUBE_PATH_ID_PATTERN.matcher(query);
        if (pathMatcher.find()) {
            return "youtube:track:" + pathMatcher.group(1);
        }

        Matcher videoIdMatcher = YOUTUBE_VIDEO_ID_PARAM.matcher(query);
        if (videoIdMatcher.find()) {
            return "youtube:track:" + videoIdMatcher.group(1);
        }

        return null;
    }

    /**
     * Extracts an 11-character YouTube video ID from a URL or query, if present.
     *
     * @param query The input identifier or URL
     * @return 11-character video ID, or null if not present
     */
    public static String extractVideoId(String query) {
        if (query == null || query.isEmpty()) {
            return null;
        }
        Matcher videoIdMatcher = YOUTUBE_VIDEO_ID_PARAM.matcher(query);
        if (videoIdMatcher.find()) {
            return videoIdMatcher.group(1);
        }
        Matcher pathMatcher = YOUTUBE_PATH_ID_PATTERN.matcher(query);
        if (pathMatcher.find()) {
            return pathMatcher.group(1);
        }
        if (YOUTUBE_RAW_ID_PATTERN.matcher(query.trim()).matches()) {
            return query.trim();
        }
        return null;
    }
}
