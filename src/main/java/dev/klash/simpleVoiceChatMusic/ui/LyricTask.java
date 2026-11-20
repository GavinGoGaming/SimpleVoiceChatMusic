package dev.klash.simpleVoiceChatMusic.ui;

import dev.klash.simpleVoiceChatMusic.SimpleVoiceChatMusic;
import dev.klash.simpleVoiceChatMusic.audio.GroupManager;
import dev.klash.simpleVoiceChatMusic.audio.MusicManager;
import dev.klash.simpleVoiceChatMusic.audio.NetEaseApi;
import dev.klash.simpleVoiceChatMusic.config.PluginSettings;
import dev.klash.simpleVoiceChatMusic.i18n.Messages;
import dev.klash.simpleVoiceChatMusic.config.PlayerPreferences;
import dev.klash.simpleVoiceChatMusic.i18n.Ph;
import dev.klash.simpleVoiceChatMusic.util.Text;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 网易云歌词的 ActionBar 显示。
 *
 * <p>每个 tick 用 {@code track.getPosition()} 对歌词时间戳做二分查找，保证
 * <b>歌词强制与播放进度同步</b>（拖动进度条 / 切歌都会立即跟到正确的一行）；
 * 歌曲没有歌词时不发送任何内容。</p>
 */
public final class LyricTask implements Runnable {

    /** 每个组上一次是否显示了歌词（用于切歌/没歌词时清空 ActionBar） */
    private final Map<GroupManager, Boolean> showing = new ConcurrentHashMap<>();

    @Override
    public void run() {
        SimpleVoiceChatMusic plugin = SimpleVoiceChatMusic.getOrNull();
        if (plugin == null || !plugin.isEnabled()) {
            return;
        }

        PluginSettings settings = PluginSettings.get();
        if (!settings.lyricsEnabled()) {
            return;
        }

        TextColor color = Text.parseColor(settings.lyricsColor());
        boolean bold = settings.lyricsBold();

        Key font = null;
        String fontText = settings.lyricsFont();
        if (fontText != null && !fontText.isBlank()) {
            try {
                font = Key.key(fontText.trim());
            } catch (Exception e) {
                SimpleVoiceChatMusic.LOGGER.warning("Invalid lyrics.font '" + fontText + "', using the default font.");
            }
        }

        for (GroupManager group : MusicManager.getInstance().groups()) {
            tickGroup(group, settings, color, bold, font);
        }
    }

    private void tickGroup(GroupManager group, PluginSettings settings, TextColor color,
                           boolean bold, Key font) {
        List<UUID> listeners = List.copyOf(group.listenerIds());
        if (listeners.isEmpty() || group.isClosed()) {
            return;
        }

        AudioTrack track = group.getPlayer().getPlayingTrack();
        if (track == null) {
            clearIfNeeded(group, listeners);
            return;
        }

        NetEaseApi.Song song = NetEaseApi.metaFor(track.getIdentifier());
        List<NetEaseApi.LyricLine> lines;

        if (song != null) {
            lines = NetEaseApi.lyricsFor(song.id());

            if (lines == null) {
                // 还没取过：异步去取，下一 tick 自然显示
                NetEaseApi.requestLyricAsync(song.id(), ignored -> {
                });
                clearIfNeeded(group, listeners);
                return;
            }
        } else {
            // 其他音源（B 站 / YouTube / SoundCloud / 直链）：按歌名去网易云匹配歌词
            dev.klash.simpleVoiceChatMusic.audio.TrackMetadata.Meta meta =
                    dev.klash.simpleVoiceChatMusic.audio.TrackMetadata.get(track.getIdentifier());

            if (meta == null) {
                meta = dev.klash.simpleVoiceChatMusic.audio.TrackMetadata.get(track.getInfo().uri);
            }

            String query = meta != null
                    ? (meta.author().isBlank() ? meta.title() : meta.author() + " " + meta.title())
                    : track.getInfo().title
                    + (track.getInfo().author == null || track.getInfo().author.isBlank()
                    ? "" : " " + track.getInfo().author);

            lines = NetEaseApi.lyricsForTitle(query);

            if (lines == null) {
                clearIfNeeded(group, listeners);
                return;
            }
        }

        if (lines.isEmpty()) {
            // 这首歌没有歌词：不显示
            clearIfNeeded(group, listeners);
            return;
        }

        long position = track.getPosition() + settings.lyricsOffsetMs();
        NetEaseApi.LyricLine line = lineAt(lines, position);
        if (line == null) {
            clearIfNeeded(group, listeners);
            return;
        }

        showing.put(group, true);

        for (UUID uuid : listeners) {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null || !player.isOnline()) {
                continue;
            }

            // 玩家自己关掉了歌词：跳过（不影响同组其他人）
            if (PlayerPreferences.hideLyrics(player)) {
                continue;
            }

            Component text = Messages.component(player, "lyrics.line", Ph.raw("text", line.text()));

            if (color != null) {
                text = text.color(color);
            }
            if (bold) {
                text = text.decorate(TextDecoration.BOLD);
            }
            if (font != null) {
                text = text.font(font);
            }

            player.sendActionBar(text);
        }
    }

    private void clearIfNeeded(GroupManager group, List<UUID> listeners) {
        if (!Boolean.TRUE.equals(showing.remove(group))) {
            return;
        }

        for (UUID uuid : listeners) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null && player.isOnline() && !PlayerPreferences.hideLyrics(player)) {
                player.sendActionBar(Component.empty());
            }
        }
    }

    /**
     * 二分查找：返回 {@code position} 时刻正在唱的那一行（公开给单测）。
     */
    public static NetEaseApi.LyricLine lineAt(List<NetEaseApi.LyricLine> lines, long position) {
        int low = 0;
        int high = lines.size() - 1;
        int best = -1;

        while (low <= high) {
            int mid = (low + high) >>> 1;

            if (lines.get(mid).timeMs() <= position) {
                best = mid;
                low = mid + 1;
            } else {
                high = mid - 1;
            }
        }

        return best < 0 ? null : lines.get(best);
    }
}
