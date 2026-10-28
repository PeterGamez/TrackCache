package com.github.petergamez.trackcache.util;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe metrics and key-count cache holder for TrackCache.
 */
@Component
public class TrackCacheStats {

    private static final long KEY_COUNT_CACHE_TTL_MS = 15_000;

    private final AtomicLong hits = new AtomicLong(0);
    private final AtomicLong misses = new AtomicLong(0);
    private final AtomicLong puts = new AtomicLong(0);
    private final AtomicLong errors = new AtomicLong(0);

    private volatile long cachedKeyCount = -1;
    private volatile long lastKeyCountScanTime = 0;
    private final AtomicBoolean keyCountScanInProgress = new AtomicBoolean(false);

    public void recordHit() {
        hits.incrementAndGet();
    }

    public void recordMiss() {
        misses.incrementAndGet();
    }

    public void recordPut() {
        puts.incrementAndGet();
    }

    public void recordPuts(long count) {
        puts.addAndGet(count);
    }

    public void recordError() {
        errors.incrementAndGet();
    }

    public long getCachedKeyCount() {
        return cachedKeyCount;
    }

    public boolean isKeyCountFresh(long now) {
        return cachedKeyCount >= 0 && (now - lastKeyCountScanTime) < KEY_COUNT_CACHE_TTL_MS;
    }

    public boolean tryAcquireScanLock() {
        return keyCountScanInProgress.compareAndSet(false, true);
    }

    public void releaseScanLock() {
        keyCountScanInProgress.set(false);
    }

    public void updateCachedKeyCount(long count) {
        this.cachedKeyCount = count;
        this.lastKeyCountScanTime = System.currentTimeMillis();
    }

    public void invalidateKeyCount() {
        this.cachedKeyCount = -1;
        this.lastKeyCountScanTime = 0;
    }

    public Map<String, Object> createBaseStatsMap(boolean enabled, boolean redisHealthy) {
        long h = hits.get();
        long m = misses.get();
        long totalRequests = h + m;
        double hitRatio = totalRequests > 0 ? ((double) h / totalRequests) * 100.0 : 0.0;

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("enabled", enabled);
        stats.put("redisHealthy", redisHealthy);
        stats.put("hits", h);
        stats.put("misses", m);
        stats.put("puts", puts.get());
        stats.put("errors", errors.get());
        stats.put("hitRatioPercent", Math.round(hitRatio * 100.0) / 100.0);
        return stats;
    }
}

