package com.github.petergamez.trackcache.interceptor;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Parses cached /v4/loadtracks JSON using a streaming Jackson parser and logs
 * results in standard Lavalink v4 AudioLoader format.
 */
@Component
public class CachedResultLogger {

    private static final Logger log = LoggerFactory.getLogger(CachedResultLogger.class);

    private final ObjectMapper objectMapper;

    @Autowired
    public CachedResultLogger(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
    }

    public void logLoadedResult(String identifier, String cachedJson) {
        log.info("Got request to load for identifier \"{}\"", identifier);
        try (JsonParser parser = objectMapper.getFactory().createParser(cachedJson)) {
            String loadType = "";
            String trackTitle = null;
            String playlistName = "unknown";
            int trackCount = 0;
            String errorMessage = null;

            while (parser.nextToken() != null) {
                if (parser.currentToken() != JsonToken.FIELD_NAME) {
                    continue;
                }
                String fieldName = parser.currentName();
                if ("loadType".equals(fieldName)) {
                    String val = parser.nextTextValue();
                    if (val != null) {
                        loadType = val;
                    }
                } else if ("title".equals(fieldName) && trackTitle == null) {
                    trackTitle = parser.nextTextValue();
                    if ("track".equalsIgnoreCase(loadType) && trackTitle != null) {
                        break;
                    }
                } else if ("name".equals(fieldName) && "unknown".equals(playlistName)) {
                    String val = parser.nextTextValue();
                    if (val != null) {
                        playlistName = val;
                    }
                } else if ("message".equals(fieldName) && errorMessage == null) {
                    errorMessage = parser.nextTextValue();
                } else if ("tracks".equals(fieldName) || "data".equals(fieldName)) {
                    if (parser.nextToken() == JsonToken.START_ARRAY) {
                        while (parser.nextToken() != JsonToken.END_ARRAY && parser.currentToken() != null) {
                            if (parser.currentToken() == JsonToken.START_OBJECT) {
                                trackCount++;
                                parser.skipChildren();
                            }
                        }
                    }
                }
            }

            switch (loadType.toLowerCase(Locale.ROOT)) {
                case "track" -> log.info("Loaded track {}", trackTitle != null ? trackTitle : "unknown");
                case "playlist" -> log.info("Loaded playlist {} with {} tracks", playlistName, trackCount);
                case "search" -> log.info("Loaded search result with {} tracks", trackCount);
                case "empty" -> log.info("Loaded nothing");
                case "error" -> log.error("Failed to load track with identifier {}: {}", identifier, errorMessage != null ? errorMessage : "unknown");
                default -> log.info("Loaded cached result for identifier \"{}\"", identifier);
            }
        } catch (Exception e) {
            log.info("Loaded cached result for identifier \"{}\"", identifier);
        }
    }
}

