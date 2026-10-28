package com.github.petergamez.trackcache.redis;

import com.github.petergamez.trackcache.config.TrackCacheConfig;
import com.github.petergamez.trackcache.normalizer.UrlNormalizer;
import com.github.petergamez.trackcache.util.TrackCacheStats;
import io.lettuce.core.KeyScanCursor;
import io.lettuce.core.ScanArgs;
import io.lettuce.core.ScanCursor;
import io.lettuce.core.api.sync.RedisCommands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;

/**
 * Handles Redis key maintenance, evictions, scan purges, and key counting.
 */
@Component
public class TrackCacheCleaner {

    private static final Logger log = LoggerFactory.getLogger(TrackCacheCleaner.class);

    private final TrackCacheConfig config;
    private final UrlNormalizer normalizer;
    private final RedisConnectionManager connectionManager;
    private final TrackCacheStats stats;

    public TrackCacheCleaner(TrackCacheConfig config,
                             UrlNormalizer normalizer,
                             RedisConnectionManager connectionManager,
                             TrackCacheStats stats) {
        this.config = config;
        this.normalizer = normalizer;
        this.connectionManager = connectionManager;
        this.stats = stats;
    }

    public long deleteKeys(RedisCommands<String, String> commands, String... keys) {
        if (keys == null || keys.length == 0) {
            return 0;
        }
        Long deleted;
        try {
            deleted = commands.unlink(keys);
        } catch (Exception e) {
            deleted = commands.del(keys);
        }
        stats.invalidateKeyCount();
        return deleted != null ? deleted : 0;
    }

    public boolean evict(String identifier, ExecutorService executor) {
        if (identifier == null || identifier.trim().isEmpty()) return false;
        try {
            RedisCommands<String, String> commands = connectionManager.getSyncCommands(executor);
            if (commands == null) return false;
            String prefix = config.getRedis().getKeyPrefix();
            String trimmed = identifier.trim();
            String fullKey;
            if (trimmed.startsWith(prefix)) {
                fullKey = trimmed;
            } else {
                String canonical = normalizer.normalize(trimmed);
                fullKey = prefix + (canonical != null && !canonical.isEmpty() ? canonical : trimmed);
            }
            long deleted = deleteKeys(commands, fullKey);
            connectionManager.markHealthy();
            return deleted > 0;
        } catch (Exception e) {
            connectionManager.recordError("Error evicting key for: " + identifier, e, stats);
            return false;
        }
    }

    public long clearKeysMatching(String pattern, ExecutorService executor) {
        try {
            RedisCommands<String, String> commands = connectionManager.getSyncCommands(executor);
            if (commands == null) return 0;
            ScanCursor cursor = ScanCursor.INITIAL;
            ScanArgs scanArgs = ScanArgs.Builder.matches(pattern).limit(500);
            long totalDeleted = 0;
            do {
                KeyScanCursor<String> scanResult = commands.scan(cursor, scanArgs);
                List<String> keys = scanResult.getKeys();
                if (keys != null && !keys.isEmpty()) {
                    totalDeleted += deleteKeys(commands, keys.toArray(new String[0]));
                }
                cursor = scanResult;
            } while (!cursor.isFinished());
            connectionManager.markHealthy();
            return totalDeleted;
        } catch (Exception e) {
            connectionManager.recordError("Error clearing keys matching: " + pattern, e, stats);
            return 0;
        }
    }

    public long purgeInvalidKeys(ExecutorService executor) {
        try {
            RedisCommands<String, String> commands = connectionManager.getSyncCommands(executor);
            if (commands == null) return 0;

            String prefix = config.getRedis().getKeyPrefix();
            ScanCursor cursor = ScanCursor.INITIAL;
            ScanArgs scanArgs = ScanArgs.Builder.matches(prefix + "*").limit(500);

            long totalDeleted = 0;
            List<String> batchToDelete = new ArrayList<>(100);

            do {
                KeyScanCursor<String> scanResult = commands.scan(cursor, scanArgs);
                List<String> keys = scanResult.getKeys();
                if (keys != null) {
                    for (String key : keys) {
                        String strippedKey = key.startsWith(prefix) ? key.substring(prefix.length()) : key;
                        if (!normalizer.isCacheableKey(strippedKey)) {
                            batchToDelete.add(key);
                            if (batchToDelete.size() >= 100) {
                                totalDeleted += deleteKeys(commands, batchToDelete.toArray(new String[0]));
                                batchToDelete.clear();
                            }
                        }
                    }
                }
                cursor = scanResult;
            } while (!cursor.isFinished());

            if (!batchToDelete.isEmpty()) {
                totalDeleted += deleteKeys(commands, batchToDelete.toArray(new String[0]));
            }

            connectionManager.markHealthy();
            if (totalDeleted > 0) {
                log.info("Purged {} invalid/legacy HTTP and uncacheable keys from Redis in a single scan pass.", totalDeleted);
            }
            return totalDeleted;
        } catch (Exception e) {
            connectionManager.recordError("Error purging invalid keys from Redis", e, stats);
            return 0;
        }
    }

    public long clearAll(ExecutorService executor) {
        return clearKeysMatching(config.getRedis().getKeyPrefix() + "*", executor);
    }

    public long scanKeyCount(String pattern, ExecutorService executor) {
        RedisCommands<String, String> commands = connectionManager.getSyncCommands(executor);
        if (commands == null) {
            return -1;
        }
        ScanCursor cursor = ScanCursor.INITIAL;
        ScanArgs scanArgs = ScanArgs.Builder.matches(pattern).limit(1000);
        long totalKeys = 0;
        do {
            KeyScanCursor<String> scanResult = commands.scan(cursor, scanArgs);
            totalKeys += scanResult.getKeys().size();
            cursor = scanResult;
        } while (!cursor.isFinished());
        connectionManager.markHealthy();
        return totalKeys;
    }
}

