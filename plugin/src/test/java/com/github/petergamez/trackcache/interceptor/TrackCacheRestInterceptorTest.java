package com.github.petergamez.trackcache.interceptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.petergamez.trackcache.TrackCache;
import com.github.petergamez.trackcache.config.TrackCacheConfig;
import com.github.petergamez.trackcache.normalizer.UrlNormalizer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class TrackCacheRestInterceptorTest {

    private TrackCacheConfig config;
    private UrlNormalizer normalizer;
    private ObjectMapper objectMapper;
    private TrackCache trackCache;

    @BeforeEach
    void setUp() {
        config = new TrackCacheConfig();
        normalizer = new UrlNormalizer();
        objectMapper = new ObjectMapper();
        trackCache = new TrackCache(config, normalizer, objectMapper, null);
    }

    @Test
    void testPreHandleRejectsWrongPassword() throws Exception {
        TrackCacheRestInterceptor interceptor = new TrackCacheRestInterceptor(
                trackCache, new CachedResultLogger(objectMapper), "correct_password");

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v4/loadtracks");
        request.addParameter("identifier", "https://www.youtube.com/watch?v=dQw4w9WgXcQ");
        request.addHeader("Authorization", "wrong_password");

        MockHttpServletResponse response = new MockHttpServletResponse();

        // Must return true (fall through to Lavalink's auth filter to reject with 403)
        boolean result = interceptor.preHandle(request, response, new Object());
        assertTrue(result);
        assertNull(response.getHeader("X-TrackCache-Status"));
    }

    @Test
    void testPreHandleRejectsMissingAuthHeader() throws Exception {
        TrackCacheRestInterceptor interceptor = new TrackCacheRestInterceptor(
                trackCache, new CachedResultLogger(objectMapper), "correct_password");

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v4/loadtracks");
        request.addParameter("identifier", "https://www.youtube.com/watch?v=dQw4w9WgXcQ");

        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean result = interceptor.preHandle(request, response, new Object());
        assertTrue(result);
        assertNull(response.getHeader("X-TrackCache-Status"));
    }

    @Test
    void testPreHandleMissWithCorrectPassword() throws Exception {
        TrackCacheRestInterceptor interceptor = new TrackCacheRestInterceptor(
                trackCache, new CachedResultLogger(objectMapper), "correct_password");

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v4/loadtracks");
        request.addParameter("identifier", "https://www.youtube.com/watch?v=dQw4w9WgXcQ");
        request.addHeader("Authorization", "correct_password");

        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean result = interceptor.preHandle(request, response, new Object());
        assertTrue(result);
        assertEquals("MISS", response.getHeader("X-TrackCache-Status"));
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ",
                request.getAttribute(TrackCacheRestInterceptor.CACHE_IDENTIFIER_ATTR));
    }

    @Test
    void testPreHandleHitShortCircuitsAndWritesOutputStream() throws Exception {
        String mockCachedJson = "{\"loadType\":\"track\",\"data\":{\"info\":{\"title\":\"Test\"}}}";

        TrackCache hitCache = new TrackCache(config, normalizer, objectMapper, null) {
            @Override
            public String getCachedResult(String identifier) {
                return mockCachedJson;
            }
        };

        TrackCacheRestInterceptor interceptor = new TrackCacheRestInterceptor(
                hitCache, new CachedResultLogger(objectMapper), "correct_password");

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v4/loadtracks");
        request.addParameter("identifier", "https://www.youtube.com/watch?v=dQw4w9WgXcQ");
        request.addHeader("Authorization", "correct_password");

        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean result = interceptor.preHandle(request, response, new Object());
        assertFalse(result); // Must short-circuit (return false)
        assertEquals(200, response.getStatus());
        assertEquals("HIT", response.getHeader("X-TrackCache-Status"));
        assertEquals("application/json;charset=UTF-8", response.getContentType());
        assertEquals(mockCachedJson.getBytes(StandardCharsets.UTF_8).length, response.getContentLength());
        assertEquals(mockCachedJson, response.getContentAsString(StandardCharsets.UTF_8));
    }

    @Test
    void testPreHandleIgnoresNonLoadtracksEndpoints() throws Exception {
        TrackCacheRestInterceptor interceptor = new TrackCacheRestInterceptor(
                trackCache, new CachedResultLogger(objectMapper), "correct_password");

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v4/sessions");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean result = interceptor.preHandle(request, response, new Object());
        assertTrue(result);
        assertNull(response.getHeader("X-TrackCache-Status"));
    }
}
