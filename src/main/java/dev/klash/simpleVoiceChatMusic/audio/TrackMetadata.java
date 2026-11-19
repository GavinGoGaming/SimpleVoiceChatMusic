package dev.klash.simpleVoiceChatMusic.audio;

import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 直链音源的显示信息（歌名 / 作者）。
 *
 * <p>B 站、网易云最终都是交给 lavaplayer 播放一个 http 直链，lavaplayer 只能从 URL
 * 里推断标题（通常为空）。在解析出直链时把「歌名 / 作者」记在这里，
 * 聊天栏、Boss 栏、队列显示时优先取这份信息。</p>
 */
public final class TrackMetadata {

    public record Meta(String title, String author) {
    }

    private static final Map<String, Meta> CACHE = new ConcurrentHashMap<>();

    private TrackMetadata() {
    }

    public static void put(@Nullable String key, @Nullable String title, @Nullable String author) {
        if (key == null || key.isBlank() || title == null || title.isBlank()) {
            return;
        }

        CACHE.put(key, new Meta(title.trim(), author == null ? "" : author.trim()));
    }

    @Nullable
    public static Meta get(@Nullable String key) {
        return key == null ? null : CACHE.get(key);
    }
}
