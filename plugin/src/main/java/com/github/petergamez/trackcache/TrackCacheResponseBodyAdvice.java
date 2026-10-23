package com.github.petergamez.trackcache;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

@ControllerAdvice
public class TrackCacheResponseBodyAdvice implements ResponseBodyAdvice<Object> {

    private static final Logger log = LoggerFactory.getLogger(TrackCacheResponseBodyAdvice.class);

    private final TrackCacheService trackCacheService;

    public TrackCacheResponseBodyAdvice(TrackCacheService trackCacheService) {
        this.trackCacheService = trackCacheService;
    }

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return trackCacheService.isEnabled();
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
                String identifier = (String) httpRequest.getAttribute(TrackCacheRestInterceptor.CACHE_IDENTIFIER_ATTR);

                if (identifier != null && !identifier.isEmpty()) {
                    log.debug("Intercepted response body for identifier: '{}', caching to Redis", identifier);
                    trackCacheService.cacheResult(identifier, body);
                }
            }
        } catch (Exception e) {
            log.warn("Error while caching response body in ResponseBodyAdvice: {}", e.getMessage(), e);
        }

        return body;
    }
}
