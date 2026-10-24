package com.github.petergamez.trackcache;

import com.fasterxml.jackson.databind.JsonNode;
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
        try {
            JsonNode rootNode = objectMapper.readTree(cachedJson);
            String loadType = rootNode.has("loadType") ? rootNode.get("loadType").asText() : "";
            JsonNode dataNode = rootNode.get("data");

            switch (loadType.toLowerCase(Locale.ROOT)) {
                case "track" -> {
                    String title = extractTrackTitle(dataNode);
                    log.info("Loaded track {}", title);
                }
                case "playlist" -> {
                    String name = "unknown";
                    int count = 0;
                    if (dataNode != null) {
                        if (dataNode.has("info") && dataNode.get("info").has("name")) {
                            name = dataNode.get("info").get("name").asText();
                        }
                        if (dataNode.has("tracks") && dataNode.get("tracks").isArray()) {
                            count = dataNode.get("tracks").size();
                        }
                    }
                    log.info("Loaded playlist {} with {} tracks", name, count);
                }
                case "search" -> {
                    int count = 0;
                    if (dataNode != null) {
                        if (dataNode.isArray()) {
                            count = dataNode.size();
                        } else if (dataNode.has("tracks") && dataNode.get("tracks").isArray()) {
                            count = dataNode.get("tracks").size();
                        }
                    }
                    log.info("Loaded search result with {} tracks", count);
                }
                case "empty" -> log.info("Loaded nothing");
                case "error" -> {
                    String message = "unknown";
                    if (dataNode != null && dataNode.has("message")) {
                        message = dataNode.get("message").asText();
                    }
                    log.error("Failed to load track with identifier {}: {}", identifier, message);
                }
                default -> log.info("Loaded cached result for identifier \"{}\"", identifier);
            }
        } catch (Exception e) {
            log.info("Loaded cached result for identifier \"{}\"", identifier);
        }
    }

    private String extractTrackTitle(JsonNode trackNode) {
        if (trackNode != null) {
            if (trackNode.isArray() && !trackNode.isEmpty()) {
                return extractTrackTitle(trackNode.get(0));
            }
            if (trackNode.has("info") && trackNode.get("info").has("title")) {
                return trackNode.get("info").get("title").asText();
            }
            if (trackNode.has("title")) {
                return trackNode.get("title").asText();
            }
        }
        return "unknown";
    }
}
