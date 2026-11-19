package dev.klash.simpleVoiceChatMusic.audio;

import de.maxhenkel.voicechat.api.Group;

import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 每个语音组的音量 / 低音 / 循环模式，按组 UUID 保存在内存里（组销毁重建后仍然保留）。
 */
public class GroupSettingsManager {

    /** 循环模式 */
    public enum LoopMode {
        OFF("off", "loop.off"),
        ONE("one", "loop.one"),
        ALL("all", "loop.all");

        private final String id;
        private final String langKey;

        LoopMode(String id, String langKey) {
            this.id = id;
            this.langKey = langKey;
        }

        public String id() {
            return id;
        }

        public String langKey() {
            return langKey;
        }

        public static LoopMode fromInput(String raw) {
            if (raw == null) {
                return null;
            }

            return switch (raw.trim().toLowerCase(Locale.ROOT)) {
                case "off", "none", "0", "关", "关闭", "不循环" -> OFF;
                case "one", "single", "1", "单曲", "单曲循环" -> ONE;
                case "all", "queue", "2", "列表", "列表循环", "循环" -> ALL;
                default -> null;
            };
        }
    }

    private static final ConcurrentHashMap<UUID, GroupSettingsManager> settings = new ConcurrentHashMap<>();

    public volatile float bassboost;
    public volatile int volume;
    public volatile LoopMode loop = LoopMode.OFF;

    private GroupSettingsManager(int volume, float bassboost) {
        this.volume = volume;
        this.bassboost = bassboost;
    }

    public static GroupSettingsManager getGroup(Group group) {
        return getGroup(group, 100, 0f);
    }

    /**
     * @param defaultVolume    config.yml 的 music.default-volume
     * @param defaultBassboost config.yml 的 music.default-bassboost
     */
    public static GroupSettingsManager getGroup(Group group, int defaultVolume, float defaultBassboost) {
        return settings.computeIfAbsent(group.getId(),
                id -> new GroupSettingsManager(
                        Math.max(0, Math.min(100, defaultVolume)),
                        Math.max(0f, Math.min(200f, defaultBassboost))));
    }
}
