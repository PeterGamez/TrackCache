package com.github.petergamez.trackcache;

import dev.arbjerg.lavalink.api.RestInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;

@Service
public class TrackCacheRestInterceptor implements RestInterceptor {

    private static final Logger log = LoggerFactory.getLogger(TrackCacheRestInterceptor.class);

    public static final String CACHE_IDENTIFIER_ATTR = "trackcache.identifier";
    private static final String LOADTRACKS_PATH = "/v4/loadtracks";

    private final TrackCacheService trackCacheService;

    public TrackCacheRestInterceptor(TrackCacheService trackCacheService) {
        this.trackCacheService = trackCacheService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (!trackCacheService.isEnabled()) {
            return true;
        }

        String uri = request.getRequestURI();

        if (!uri.endsWith(LOADTRACKS_PATH)) {
            return true;
        }

        String identifier = request.getParameter("identifier");
        if (identifier == null || identifier.trim().isEmpty()) {
            return true;
        }

        String cachedJson = trackCacheService.getCachedResult(identifier);
        if (cachedJson != null) {
            log.debug("Serving cached loadtracks response for '{}'", identifier);

            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
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
}
