package com.github.petergamez.trackcache.interceptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.petergamez.trackcache.TrackCache;
import dev.arbjerg.lavalink.api.RestInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Service
@Order(Ordered.LOWEST_PRECEDENCE)
public class TrackCacheRestInterceptor implements RestInterceptor, Ordered {

    private static final Logger log = LoggerFactory.getLogger(TrackCacheRestInterceptor.class);

    public static final String CACHE_IDENTIFIER_ATTR = "trackcache.identifier";
    private static final String LOADTRACKS_PATH = "/v4/loadtracks";

    private final TrackCache trackCache;
    private final CachedResultLogger resultLogger;
    private final String serverPassword;

    @Autowired
    public TrackCacheRestInterceptor(TrackCache trackCache,
                                     CachedResultLogger resultLogger,
                                     @Value("${lavalink.server.password:}") String serverPassword) {
        this.trackCache = trackCache;
        this.resultLogger = resultLogger;
        this.serverPassword = serverPassword != null ? serverPassword.trim() : "";
    }

    public TrackCacheRestInterceptor(TrackCache trackCache, CachedResultLogger resultLogger) {
        this(trackCache, resultLogger, "");
    }

    public TrackCacheRestInterceptor(TrackCache trackCache, ObjectMapper objectMapper) {
        this(trackCache, new CachedResultLogger(objectMapper), "");
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
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

        // Defense-in-depth: If Lavalink server password is configured, verify constant-time match
        if (!serverPassword.isEmpty()) {
            if (!MessageDigest.isEqual(
                    serverPassword.getBytes(StandardCharsets.UTF_8),
                    authHeader.trim().getBytes(StandardCharsets.UTF_8))) {
                return true; // Let Lavalink's RequestAuthorizationFilter reject with 403 Forbidden
            }
        }

        String identifier = request.getParameter("identifier");
        if (identifier == null || identifier.trim().isEmpty() || !trackCache.isCacheable(identifier)) {
            return true;
        }

        String cachedJson = trackCache.getCachedResult(identifier);
        if (cachedJson != null) {
            resultLogger.logLoadedResult(identifier, cachedJson);
            log.debug("Serving cached loadtracks response for '{}'", identifier);

            byte[] bytes = cachedJson.getBytes(StandardCharsets.UTF_8);
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentType("application/json;charset=UTF-8");
            response.setContentLength(bytes.length);
            response.setHeader("X-TrackCache-Status", "HIT");
            response.getOutputStream().write(bytes);
            response.getOutputStream().flush();
            return false;
        }

        request.setAttribute(CACHE_IDENTIFIER_ATTR, identifier);
        response.setHeader("X-TrackCache-Status", "MISS");
        return true;
    }
}
