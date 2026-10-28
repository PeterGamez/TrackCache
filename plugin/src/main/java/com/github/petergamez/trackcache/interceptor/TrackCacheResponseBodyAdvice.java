package com.github.petergamez.trackcache.interceptor;

import com.github.petergamez.trackcache.TrackCache;
import dev.arbjerg.lavalink.protocol.v4.LoadResult;
import dev.arbjerg.lavalink.protocol.v4.Mapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

import java.util.function.Supplier;

@ControllerAdvice
public class TrackCacheResponseBodyAdvice implements ResponseBodyAdvice<Object> {

    private static final Logger log = LoggerFactory.getLogger(TrackCacheResponseBodyAdvice.class);

    private final TrackCache trackCache;

    public TrackCacheResponseBodyAdvice(TrackCache trackCache) {
        this.trackCache = trackCache;
    }

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return trackCache.isEnabled();
    }

    @Override
    public Object beforeBodyWrite(Object body,
                                  MethodParameter returnType,
                                  MediaType selectedContentType,
                                  Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                  ServerHttpRequest request,
                                  ServerHttpResponse response) {
        if (body == null) {
            return null;
        }

        try {
            if (request instanceof ServletServerHttpRequest servletRequest) {
                HttpServletRequest httpRequest = servletRequest.getServletRequest();
                Object attr = httpRequest.getAttribute(TrackCacheRestInterceptor.CACHE_IDENTIFIER_ATTR);
                if (!(attr instanceof String identifier) || identifier.isEmpty()) {
                    return body;
                }
                // Remove attribute immediately so error/async dispatches cannot re-trigger caching
                httpRequest.removeAttribute(TrackCacheRestInterceptor.CACHE_IDENTIFIER_ATTR);

                if (response instanceof ServletServerHttpResponse servletResponse) {
                    int status = servletResponse.getServletResponse().getStatus();
                    if (status != HttpServletResponse.SC_OK) {
                        log.debug("Skipping caching response for '{}' due to non-200 HTTP status: {}", identifier, status);
                        return body;
                    }
                }

                log.debug("Intercepted response body for identifier: '{}', caching to Redis", identifier);
                if (body instanceof LoadResult loadResult) {
                    Supplier<String> jsonSupplier = () -> Mapper.getJson()
                            .encodeToString(LoadResult.Companion.serializer(), loadResult);
                    trackCache.cacheResult(identifier, jsonSupplier);
                } else {
                    trackCache.cacheResult(identifier, body);
                }
            }
        } catch (Exception e) {
            log.warn("Error while caching response body in ResponseBodyAdvice: {}", e.getMessage(), e);
        }

        return body;
    }
}

