package dev.klash.simpleVoiceChatMusic.util;

import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 失败缓存：同一个链接刚刚失败过，短时间内再点就直接给结论，
 * 不再重新发起网络请求，也不再刷日志。
 *
 * <p>只记录"错误 key + 过期时间"，不缓存任何音频数据。</p>
 */
public final class LinkCheckCache {

    private record Entry(String errorKey, long expiresAt) {
    }

    private static final Map<String, Entry> FAILURES = new ConcurrentHashMap<>();

    private LinkCheckCache() {
    }

    /**
     * @return 未过期的错误 key；没有缓存或已过期返回 null
     */
    @Nullable
    public static String failure(String query) {
        if (query == null) {
            return null;
        }

        Entry entry = FAILURES.get(query);
        if (entry == null) {
            return null;
        }

        if (System.currentTimeMillis() > entry.expiresAt()) {
            FAILURES.remove(query);
            return null;
        }

        return entry.errorKey();
    }

    public static void record(String query, String errorKey, int ttlSeconds) {
        if (query == null || errorKey == null || ttlSeconds <= 0) {
            return;
        }

        if (FAILURES.size() > 256) {
            prune();
        }

        FAILURES.put(query, new Entry(errorKey, System.currentTimeMillis() + ttlSeconds * 1000L));
    }

    public static void clear(String query) {
        if (query != null) {
            FAILURES.remove(query);
        }
    }

    /**
     * 清理过期条目，避免长时间运行后 map 里堆着旧链接。
     */
    public static void prune() {
        long now = System.currentTimeMillis();
        FAILURES.entrySet().removeIf(entry -> now > entry.getValue().expiresAt());
    }

    public static int size() {
        return FAILURES.size();
    }
}
