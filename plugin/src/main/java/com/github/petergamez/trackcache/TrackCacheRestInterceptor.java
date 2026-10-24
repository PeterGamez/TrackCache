package com.github.petergamez.trackcache;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.arbjerg.lavalink.api.RestInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Locale;

@Service
public class TrackCacheRestInterceptor implements RestInterceptor {

    private static final Logger log = LoggerFactory.getLogger(TrackCacheRestInterceptor.class);

    public static final String CACHE_IDENTIFIER_ATTR = "trackcache.identifier";
    private static final String LOADTRACKS_PATH = "/v4/loadtracks";

    private final TrackCacheService trackCacheService;
    private final ObjectMapper objectMapper;

    public TrackCacheRestInterceptor(TrackCacheService trackCacheService, ObjectMapper objectMapper) {
        this.trackCacheService = trackCacheService;
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
    }

    public TrackCacheRestInterceptor(TrackCacheService trackCacheService) {
        this(trackCacheService, new ObjectMapper());
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (!trackCacheService.isEnabled()) {
            return true;
        }

        if (!"GET".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        String uri = request.getRequestURI();

        if (!uri.endsWith(LOADTRACKS_PATH) && !uri.endsWith(LOADTRACKS_PATH + "/")) {
            return true;
        }

        String identifier = request.getParameter("identifier");
        if (identifier == null || identifier.trim().isEmpty()) {
            return true;
        }

        String cachedJson = trackCacheService.getCachedResult(identifier);
        if (cachedJson != null) {
            log.info("Got request to load for identifier \"{}\"", identifier);
            logLoadedResult(identifier, cachedJson);

            log.debug("Serving cached loadtracks response for '{}'", identifier);

            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentType("application/json;charset=UTF-8");
            response.setCharacterEncoding("UTF-8");
            response.setHeader("X-TrackCache-Status", "HIT");
            response.getWriter().write(cachedJson);
            response.getWriter().flush();

            return false;
        }

        request.setAttribute(CACHE_IDENTIFIER_ATTR, identifier);
        response.setHeader("X-TrackCache-Status", "MISS");
        return true;
    }

    private void logLoadedResult(String identifier, String cachedJson) {
        try (JsonParser parser = objectMapper.getFactory().createParser(cachedJson)) {
            String loadType = "";
            String trackTitle = null;
            String playlistName = "unknown";
            int trackCount = 0;
            String errorMessage = null;

            while (parser.nextToken() != null) {
                String fieldName = parser.currentName();
                if ("loadType".equals(fieldName)) {
                    loadType = parser.nextTextValue();
                } else if ("title".equals(fieldName) && trackTitle == null) {
                    trackTitle = parser.nextTextValue();
                } else if ("name".equals(fieldName) && "unknown".equals(playlistName)) {
                    playlistName = parser.nextTextValue();
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

            switch (loadType != null ? loadType.toLowerCase(Locale.ROOT) : "") {
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
