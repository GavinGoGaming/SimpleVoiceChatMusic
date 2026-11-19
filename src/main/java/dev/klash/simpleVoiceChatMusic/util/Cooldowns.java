package dev.klash.simpleVoiceChatMusic.util;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 极简的按玩家冷却，防止同一条命令被连续狂刷
 * （日志里一次故障往往能看到同一条 /music play 被连发十几次）。
 */
public final class Cooldowns {

    private static final Map<String, Long> LAST_USED = new ConcurrentHashMap<>();

    private Cooldowns() {
    }

    /**
     * 尝试占用一次冷却。
     *
     * @param cooldownMs 冷却毫秒，<= 0 表示不限制
     * @return 0 表示放行；否则返回还需要等待的毫秒数
     */
    public static long tryAcquire(UUID playerId, String action, long cooldownMs) {
        if (cooldownMs <= 0) {
            return 0L;
        }

        long now = System.currentTimeMillis();
        String key = playerId + ":" + action;

        Long last = LAST_USED.get(key);
        if (last != null) {
            long elapsed = now - last;
            if (elapsed < cooldownMs) {
                return cooldownMs - elapsed;
            }
        }

        LAST_USED.put(key, now);
        return 0L;
    }

    public static void clear(UUID playerId) {
        LAST_USED.keySet().removeIf(key -> key.startsWith(playerId + ":"));
    }
}
