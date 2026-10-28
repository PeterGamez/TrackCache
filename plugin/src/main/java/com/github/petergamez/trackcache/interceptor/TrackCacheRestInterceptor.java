package com.github.petergamez.trackcache.interceptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.petergamez.trackcache.TrackCache;
import dev.arbjerg.lavalink.api.RestInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class TrackCacheRestInterceptor implements RestInterceptor {

    private static final Logger log = LoggerFactory.getLogger(TrackCacheRestInterceptor.class);

    public static final String CACHE_IDENTIFIER_ATTR = "trackcache.identifier";
    private static final String LOADTRACKS_PATH = "/v4/loadtracks";

    private final TrackCache trackCache;
    private final CachedResultLogger resultLogger;

    @Autowired
    public TrackCacheRestInterceptor(TrackCache trackCache, CachedResultLogger resultLogger) {
        this.trackCache = trackCache;
        this.resultLogger = resultLogger;
    }

    public TrackCacheRestInterceptor(TrackCache trackCache, ObjectMapper objectMapper) {
        this(trackCache, new CachedResultLogger(objectMapper));
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (!trackCache.isEnabled() || !"GET".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        String uri = request.getRequestURI();
        if (uri == null || (!uri.endsWith(LOADTRACKS_PATH) && !uri.endsWith(LOADTRACKS_PATH + "/"))) {
            return true;
        }

        // Never short-circuit unauthenticated requests before Lavalink's authorization filter
        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || authHeader.isEmpty()) {
            return true;
        }

        String identifier = request.getParameter("identifier");
        if (identifier == null || identifier.trim().isEmpty() || !trackCache.isCacheable(identifier)) {
            return true;
        }

        String cachedJson = trackCache.getCachedResult(identifier);
        if (cachedJson != null) {
            resultLogger.logLoadedResult(identifier, cachedJson);
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
}

