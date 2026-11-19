package dev.klash.simpleVoiceChatMusic.audio;

import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import dev.klash.simpleVoiceChatMusic.SimpleVoiceChatMusic;
import dev.klash.simpleVoiceChatMusic.config.PluginSettings;
import dev.klash.simpleVoiceChatMusic.i18n.Messages;
import dev.klash.simpleVoiceChatMusic.i18n.Ph;
import dev.klash.simpleVoiceChatMusic.util.Errors;
import dev.klash.simpleVoiceChatMusic.util.LinkCheckCache;
import dev.klash.simpleVoiceChatMusic.util.ModUtils;
import dev.klash.simpleVoiceChatMusic.util.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.Style;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.logging.Level;

public class SearchLoadHandler implements AudioLoadResultHandler {

    protected final Player source;
    protected final GroupManager group;
    /** 交给 lavaplayer 的查询串，用于失败后写进失败缓存（可为 null） */
    protected final String query;
    /** 第几次尝试（0 = 第一次），用于自动重试 */
    protected final int attempt;
    /** 静默模式：只入队，不往聊天栏发消息（歌单批量入队用） */
    protected final boolean silent;

    public SearchLoadHandler(@Nullable Player source, GroupManager group, @Nullable String query) {
        this(source, group, query, 0);
    }

    public SearchLoadHandler(@Nullable Player source, GroupManager group, @Nullable String query, int attempt) {
        this(source, group, query, attempt, false);
    }

    public SearchLoadHandler(@Nullable Player source, GroupManager group, @Nullable String query, int attempt,
                             boolean silent) {
        this.source = source;
        this.group = group;
        this.query = query;
        this.attempt = attempt;
        this.silent = silent;
    }

    @Override
    public void trackLoaded(AudioTrack track) {
        if (this.query != null) {
            LinkCheckCache.clear(this.query);
        }

        if (!this.group.enqueueSong(track)) {
            this.sendMessage(Messages.component(this.source, "play.queue-full"));
            return;
        }

        if (!this.silent && this.source != null) {
            this.group.broadcast("play.queued",
                    Ph.raw("player", this.source.getName()),
                    Ph.comp("track", ModUtils.trackInfo(track.getInfo(), true)));
        }
    }

    @Override
    public void playlistLoaded(AudioPlaylist playlist) {
        List<AudioTrack> tracks = playlist == null ? null : playlist.getTracks();

        if (tracks == null || tracks.isEmpty()) {
            this.noMatches();
            return;
        }

        int limit = Math.max(1, PluginSettings.get().maxSearchResults());
        // 只取前 N 条；注意不能直接 subList(0, 5)，
        // 搜索结果少于 5 条（例如只有 1~4 首的播放列表）会抛 IndexOutOfBoundsException
        List<AudioTrack> loaded = tracks.subList(0, Math.min(limit, tracks.size()));

        if (this.source != null) {
            // 第一行带前缀，后面的条目只渲染正文
            Component text = Messages.render(this.source, "search.header", Ph.num("count", loaded.size()));
            Component bullet = Messages.component(this.source, "search.bullet");
            Component label = Messages.component(this.source, "search.click-to-queue");
            Component hover = Messages.component(this.source, "search.click-hover");

            for (AudioTrack track : loaded) {
                Component clickable = label.style(Style.empty()
                        // 这个插件是"拼接参数"式命令，不需要也不应该加引号
                        .clickEvent(ClickEvent.runCommand("/music play " + track.getIdentifier()))
                        .hoverEvent(HoverEvent.showText(hover)));

                text = text.append(Text.literal("\n"))
                        .append(bullet)
                        .append(ModUtils.trackInfo(track.getInfo(), true))
                        .append(Text.literal(" "))
                        .append(clickable);
            }

            this.sendMessage(text);
        }
    }

    @Override
    public void noMatches() {
        this.sendMessage(Messages.component(this.source, "play.no-matches"));
    }

    @Override
    public void loadFailed(FriendlyException exception) {
        if (exception == null) {
            this.sendMessage(Messages.component(this.source, "play.failed-internal"));
            return;
        }

        // 网络类错误（超时 / 连接被断开 / 域名解析失败…）：先自动重试几次，
        // 这类 CDN 经常"第一次 0 字节、第二次就有数据"
        String networkKey = Errors.playerMessageKey(exception);
        if (networkKey != null) {
            int retries = PluginSettings.get().retryCount();

            if (this.attempt < retries && this.query != null && !this.group.isClosed()) {
                SimpleVoiceChatMusic.LOGGER.info("Load failed (" + exception.getMessage() + "), retry "
                        + (this.attempt + 1) + "/" + retries);
                this.scheduleRetry();
                return;
            }

            SimpleVoiceChatMusic.LOGGER.warning("Failed to load track: " + exception.getMessage());

            // 重试完还是不行：记进失败缓存，短时间内再点同一个链接直接给结论
            if (this.query != null) {
                LinkCheckCache.record(this.query, networkKey, PluginSettings.get().failureCacheSeconds());
            }

            this.sendMessage(Messages.component(this.source, networkKey));
            return;
        }

        if (exception.severity != FriendlyException.Severity.COMMON) {
            SimpleVoiceChatMusic.LOGGER.log(Level.WARNING, "Failed to load track from query", exception);
            this.sendMessage(Messages.component(this.source, "play.failed-internal"));
            return;
        }

        this.sendMessage(Messages.component(this.source, "play.failed",
                Ph.raw("reason", String.valueOf(exception.getMessage()))));
    }

    /**
     * 延迟一段时间后重新加载同一个查询（只用于网络类瞬时错误）。
     */
    private void scheduleRetry() {
        SimpleVoiceChatMusic plugin = SimpleVoiceChatMusic.getOrNull();
        if (plugin == null || !plugin.isEnabled()) {
            return;
        }

        long delayTicks = Math.max(1L, PluginSettings.get().retryDelayMs() / 50L);

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (this.group.isClosed() || this.source != null && !this.source.isOnline()) {
                return;
            }

            Bukkit.getScheduler().runTaskAsynchronously(plugin, () ->
                    MusicManager.getInstance().playerManager.loadItemOrdered(
                            this.group.getPlayer(),
                            this.query,
                            new PlayLoadHandler(this.source, this.group, this.query, this.attempt + 1)));
        }, delayTicks);
    }

    /**
     * 统一把消息发回主线程，避免从 LavaPlayer 的加载线程直接操作玩家连接。
     */
    protected void sendMessage(Component message) {
        if (this.silent || this.source == null) {
            return;
        }

        SimpleVoiceChatMusic plugin = SimpleVoiceChatMusic.getOrNull();
        if (plugin == null || !plugin.isEnabled()) {
            return;
        }

        Bukkit.getScheduler().runTask(plugin, () -> this.source.sendMessage(message));
    }
}
