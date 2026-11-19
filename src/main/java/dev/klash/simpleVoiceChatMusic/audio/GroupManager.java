package dev.klash.simpleVoiceChatMusic.audio;

import com.sedmelluq.discord.lavaplayer.filter.equalizer.EqualizerFactory;
import com.sedmelluq.discord.lavaplayer.format.StandardAudioDataFormats;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.playback.MutableAudioFrame;
import de.maxhenkel.voicechat.api.Group;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.audiochannel.StaticAudioChannel;
import dev.klash.simpleVoiceChatMusic.SimpleVoiceChatMusic;
import dev.klash.simpleVoiceChatMusic.VoiceChatPlugin;
import dev.klash.simpleVoiceChatMusic.config.PlayerPreferences;
import dev.klash.simpleVoiceChatMusic.config.PluginSettings;
import dev.klash.simpleVoiceChatMusic.i18n.Messages;
import dev.klash.simpleVoiceChatMusic.i18n.Ph;
import dev.klash.simpleVoiceChatMusic.util.ModUtils;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.entity.Player;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.logging.Level;

import static dev.klash.simpleVoiceChatMusic.util.Constants.BASS_BOOST;

public class GroupManager {
    private final Group group;
    private final AudioPlayer lavaplayer;
    private final Server server;
    private final BlockingQueue<AudioTrack> queue;
    private final GroupSettingsManager settingsStore;

    private final ConcurrentHashMap<UUID, StaticAudioChannel> connections = new ConcurrentHashMap<>();
    private final MutableAudioFrame currentFrame;
    private final EqualizerFactory equalizer = new EqualizerFactory();

    /** 每个组员一个 BOSS 栏（这样每个人都能用自己的语言显示标题） */
    private final Map<UUID, BossBar> bossBars = new ConcurrentHashMap<>();
    private final Set<UUID> bossBarViewers = ConcurrentHashMap.newKeySet();

    /** 循环重播时不再重复播报"开始播放" */
    private final AtomicBoolean loopReplay = new AtomicBoolean(false);
    private final boolean bossBarEnabled;
    private final BossBar.Color bossBarColor;
    private final BossBar.Overlay bossBarOverlay;

    /** 是否已经 cleanup，保证 cleanup 幂等，并阻止销毁后再被使用 */
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private volatile ScheduledFuture<?> audioFrameSendingTask = null;
    private volatile ScheduledFuture<?> playerTrackingTask = null;

    /**
     * 只负责把 opus 帧推给 SVC。
     * 单独一个线程：{@code provide()} 在缓冲区为空时会阻塞（最长帧缓冲时长），
     * 不能和"玩家进出组扫描""切歌"共用线程，否则会拖慢它们。
     */
    private final ScheduledExecutorService audioExecutor =
            Executors.newSingleThreadScheduledExecutor(r -> newDaemonThread(r, "SVCGroupMusicAudio"));

    /** 负责组内玩家跟踪与切歌 */
    private final ScheduledExecutorService executorService =
            Executors.newSingleThreadScheduledExecutor(r -> newDaemonThread(r, "SVCGroupMusicExecutor"));

    public GroupManager(Group group, AudioPlayer player, Server server) {
        this.group = group;
        this.server = server;
        this.lavaplayer = player;
        this.currentFrame = new MutableAudioFrame();

        PluginSettings settings = PluginSettings.get();
        this.settingsStore = GroupSettingsManager.getGroup(group, settings.defaultVolume(), settings.defaultBassboost());

        this.bossBarEnabled = settings.bossBarEnabled();
        this.bossBarColor = parseColor(settings.bossBarColor());
        this.bossBarOverlay = parseOverlay(settings.bossBarOverlay());

        // apply EQ
        this.lavaplayer.setFilterFactory(this.equalizer);
        this.lavaplayer.setFrameBufferDuration(500);

        // buffer for storing the current opus frame.
        // lavaplayer 默认输出 DISCORD_OPUS，单帧最大 maximumChunkSize()（1568 字节），
        // 用 1024 会在高码率帧上抛 BufferOverflowException。
        ByteBuffer buffer = ByteBuffer.allocate(StandardAudioDataFormats.DISCORD_OPUS.maximumChunkSize());
        currentFrame.setBuffer(buffer);

        this.queue = new LinkedBlockingQueue<>(Math.max(1, settings.maxQueueSize()));

        // register events
        player.addListener(new TrackScheduler(this));

        // schedule task
        startGroupTracking();
        startAudioFrameSending();

        // restore settings
        this.setVolume(this.settingsStore.volume);
        this.setBassBoost(this.settingsStore.bassboost);
    }

    private static Thread newDaemonThread(Runnable runnable, String name) {
        Thread thread = new Thread(runnable, name);
        thread.setDaemon(true);
        thread.setUncaughtExceptionHandler(
                (t, e) -> SimpleVoiceChatMusic.LOGGER.log(Level.SEVERE, "Uncaught exception in thread " + t.getName(), e)
        );
        return thread;
    }

    private void startAudioFrameSending() {
        if (this.closed.get()) {
            return;
        }

        if (this.audioFrameSendingTask != null && !this.audioFrameSendingTask.isDone()) {
            // already started, so leave it.
            return;
        }

        if (this.audioFrameSendingTask != null && this.audioFrameSendingTask.isDone()) {
            // stop and restart
            SimpleVoiceChatMusic.LOGGER.info("Frame task in stuck state, attempting to revive");
            this.audioFrameSendingTask.cancel(true);
        }

        try {
            this.audioFrameSendingTask = this.audioExecutor.scheduleAtFixedRate(() -> {
                // 整个任务体必须包住异常：scheduleAtFixedRate 的周期性任务一旦抛出异常
                // 就会被永久取消（表现为"放着放着突然没声音了"）
                try {
                    if (this.closed.get() || VoiceChatPlugin.voicechatServerApi == null) {
                        return;
                    }

                    // check if playback is paused
                    if (this.lavaplayer.isPaused() || this.lavaplayer.getPlayingTrack() == null) {
                        return;
                    }

                    if (this.lavaplayer.provide(this.currentFrame)) {
                        for (StaticAudioChannel channel : this.connections.values()) {
                            try {
                                channel.send(this.currentFrame.getData());
                            } catch (Exception e) {
                                SimpleVoiceChatMusic.LOGGER.log(Level.WARNING,
                                        "Failed to send an audio frame to a player in group " + this.group.getName(), e);
                            }
                        }
                    }
                } catch (Exception e) {
                    SimpleVoiceChatMusic.LOGGER.log(Level.SEVERE,
                            "Error while sending audio frames in group " + this.group.getName(), e);
                }
            }, 1000L, 20L, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            // 执行器已关闭（组已销毁），忽略
            SimpleVoiceChatMusic.LOGGER.log(Level.FINE, "Audio executor already shut down for group " + this.group.getName());
        }
    }

    private void startGroupTracking() {
        this.playerTrackingTask = this.executorService.scheduleAtFixedRate(() -> {
            try {
                if (this.closed.get() || VoiceChatPlugin.voicechatServerApi == null) {
                    return;
                }

                HashSet<UUID> uuids = new HashSet<>();

                for (Player serverPlayer : server.getOnlinePlayers()) {
                    VoicechatConnection playerConnection =
                            VoiceChatPlugin.voicechatServerApi.getConnectionOf(serverPlayer.getUniqueId());

                    if (playerConnection == null || !playerConnection.isConnected()) continue;
                    Group playerGroup = playerConnection.getGroup();
                    // 注意：UUID 必须用 equals 比较，用 != 是在比较对象引用
                    if (playerGroup == null || !Objects.equals(playerGroup.getId(), this.group.getId())) continue;

                    uuids.add(serverPlayer.getUniqueId());

                    this.connections.computeIfAbsent(
                            serverPlayer.getUniqueId(),
                            (uuid) -> {
                                StaticAudioChannel channel = VoiceChatPlugin.voicechatServerApi.createStaticAudioChannel(
                                        UUID.randomUUID(),
                                        VoiceChatPlugin.voicechatServerApi.fromServerLevel(serverPlayer.getWorld()),
                                        playerConnection
                                );

                                if (channel == null) return null;
                                channel.setCategory(VoiceChatPlugin.MUSIC_CATEGORY);

                                return channel;
                            }
                    );
                }

                // now remove all that aren't here anymore
                for (UUID uuid : this.connections.keySet()) {
                    if (uuids.contains(uuid)) continue;
                    this.connections.remove(uuid);
                }

                // clean up if no players
                if (this.connections.isEmpty()) {
                    SimpleVoiceChatMusic.LOGGER.info("Group " + this.group.getName() + " is now empty. Cleaning up...");
                    this.cleanup();
                }
            } catch (Exception e) {
                SimpleVoiceChatMusic.LOGGER.log(Level.SEVERE,
                        "Error while tracking players of group " + this.group.getName(), e);
            }
        }, 0L, 100L, TimeUnit.MILLISECONDS);
    }

    /**
     * 入队/直接播放一首歌。
     *
     * @return 是否成功；组已销毁或队列已满时返回 false
     */
    public boolean enqueueSong(AudioTrack track) {
        if (this.closed.get()) {
            return false;
        }

        try {
            // noInterrupt true => false return if smth already playing
            //                     true return if nothing playing
            if (!lavaplayer.startTrack(track, true)) {
                return this.queue.offer(track);
            }

            return true;
        } catch (Exception e) {
            SimpleVoiceChatMusic.LOGGER.log(Level.WARNING, "Failed to enqueue track", e);
            return false;
        }
    }

    public BlockingQueue<AudioTrack> getQueue() {
        return queue;
    }

    /** 清空等待队列（不打断当前歌曲），返回清掉的歌曲数 */
    public int clearQueue() {
        int size = this.queue.size();
        this.queue.clear();
        return size;
    }

    /** 当前组的循环模式 */
    public GroupSettingsManager.LoopMode loopMode() {
        return this.settingsStore.loop;
    }

    public void setLoopMode(GroupSettingsManager.LoopMode mode) {
        this.settingsStore.loop = mode == null ? GroupSettingsManager.LoopMode.OFF : mode;
    }

    /** 循环时重播当前歌曲（不重复播报"开始播放"） */
    public void replay(AudioTrack track) {
        if (this.closed.get() || track == null) {
            return;
        }

        this.loopReplay.set(true);

        try {
            this.executorService.execute(() -> {
                if (this.closed.get()) {
                    return;
                }

                try {
                    AudioTrack clone = track.makeClone();
                    clone.setPosition(0);
                    this.lavaplayer.startTrack(clone, false);
                    this.startAudioFrameSending();
                } catch (Exception e) {
                    this.loopReplay.set(false);
                    SimpleVoiceChatMusic.LOGGER.log(Level.WARNING,
                            "Could not loop the current track in group " + this.group.getName(), e);
                }
            });
        } catch (RejectedExecutionException e) {
            this.loopReplay.set(false);
        }
    }

    /** 取出并清除"这次 onTrackStart 是循环重播"的标记 */
    public boolean consumeLoopReplay() {
        return this.loopReplay.getAndSet(false);
    }

    public void nextTrack() {
        if (this.closed.get()) {
            return;
        }

        try {
            // ensure this happens in the correct thread
            this.executorService.execute(() -> {
                if (this.closed.get()) {
                    return;
                }

                try {
                    // poll returns track or null
                    // if null, lavaplayer stops
                    AudioTrack track = queue.poll();
                    lavaplayer.startTrack(track, false);

                    // revive task if needed
                    if (track != null) {
                        this.startAudioFrameSending();
                    } else {
                        // no more songs to play, so quit
                        this.cleanup();
                    }
                } catch (Exception e) {
                    SimpleVoiceChatMusic.LOGGER.log(Level.SEVERE,
                            "Error while advancing the queue of group " + this.group.getName(), e);
                }
            });
        } catch (RejectedExecutionException e) {
            // 执行器已关闭（组已销毁），忽略
        }
    }

    public AudioPlayer getPlayer() {
        return this.lavaplayer;
    }

    /**
     * 用语言文件里的 key 广播，每个收件人按自己的语言渲染。
     */
    public void broadcast(String key, TagResolver... placeholders) {
        broadcastRender(player -> Messages.render(player, key, placeholders));
    }

    /**
     * 广播一个已经渲染好的组件。
     */
    public void broadcast(Component text) {
        broadcastRender(player -> text);
    }

    private void broadcastRender(Function<Player, Component> renderer) {
        SimpleVoiceChatMusic plugin = SimpleVoiceChatMusic.getOrNull();

        // 关服/重载阶段插件已 disabled，此时再往调度器丢任务会抛
        // IllegalPluginAccessException 并中断清理流程，所以直接跳过广播。
        if (plugin == null || !plugin.isEnabled()) {
            return;
        }

        // 先快照收件人：cleanup() 会清空 connections，等任务在主线程执行时再查就没人了
        List<Player> recipients = new ArrayList<>();
        for (Player player : server.getOnlinePlayers()) {
            if (this.connections.containsKey(player.getUniqueId())) {
                recipients.add(player);
            }
        }

        if (recipients.isEmpty()) {
            return;
        }

        Bukkit.getScheduler().runTask(plugin, () -> {
            for (Player player : recipients) {
                if (player.isOnline()) {
                    player.sendMessage(renderer.apply(player));
                }
            }
        });
    }

    /**
     * 销毁这个组的播放器。幂等，可重复调用。
     */
    public void cleanup() {
        if (!this.closed.compareAndSet(false, true)) {
            return;
        }

        try {
            this.broadcast("playback.queue-empty");
        } catch (Exception e) {
            SimpleVoiceChatMusic.LOGGER.log(Level.WARNING, "Failed to broadcast cleanup message", e);
        } finally {
            this.hideAllBossBars();

            if (this.audioFrameSendingTask != null) this.audioFrameSendingTask.cancel(true);

            // 让客户端立刻停住缓冲的音频，避免"音乐已停但耳机里还有半秒"的爆音
            for (StaticAudioChannel channel : this.connections.values()) {
                try {
                    channel.flush();
                } catch (Exception ignored) {
                    // 通道可能已经关闭
                }
            }
            this.connections.clear();

            try {
                this.lavaplayer.destroy();
            } catch (Exception e) {
                SimpleVoiceChatMusic.LOGGER.log(Level.WARNING, "Failed to destroy the audio player", e);
            } finally {
                MusicManager.getInstance().deleteGroup(this.group);
                if (this.playerTrackingTask != null) this.playerTrackingTask.cancel(false);
                this.audioExecutor.shutdown();
                this.executorService.shutdown();
            }
        }
    }

    public boolean isClosed() {
        return this.closed.get();
    }

    /**
     * 刷新 BOSS 栏（由 {@code BossBarTask} 在主线程按配置的间隔调用）。
     *
     * <p>标题格式来自语言文件的 {@code bossbar.title}：文件名 + [当前时间/总时长]，
     * 进度条本身用 BOSS 栏的原生进度。</p>
     */
    public void tickBossBar() {
        if (!this.bossBarEnabled || this.closed.get()) {
            return;
        }

        AudioTrack track = this.lavaplayer.getPlayingTrack();

        for (UUID uuid : this.connections.keySet()) {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null || !player.isOnline()) {
                continue;
            }

            // 玩家自己关掉了进度条：收起并跳过
            if (PlayerPreferences.hideProgress(player)) {
                this.hideBossBar(uuid);
                continue;
            }

            if (track == null) {
                this.hideBossBar(uuid);
                continue;
            }

            BossBar bar = this.bossBars.computeIfAbsent(uuid,
                    id -> BossBar.bossBar(Component.empty(), 0f, this.bossBarColor, this.bossBarOverlay));

            long position = Math.max(0L, track.getPosition());
            long duration = track.getDuration();
            boolean knownDuration = duration > 0L;

            String durationText = knownDuration
                    ? ModUtils.formatMMSS(duration)
                    : Messages.plain(player, "bossbar.unknown-duration");

            bar.name(Messages.component(player, "bossbar.title",
                    Ph.raw("title", ModUtils.displayTitle(track)),
                    Ph.raw("position", ModUtils.formatMMSS(position)),
                    Ph.raw("duration", durationText)));

            bar.progress(knownDuration
                    ? Math.min(1f, Math.max(0f, (float) position / (float) duration))
                    : 0f);

            if (this.bossBarViewers.add(uuid)) {
                player.showBossBar(bar);
            }
        }

        // 玩家离开组或掉线：收起并清理对应的 BOSS 栏
        for (UUID uuid : new ArrayList<>(this.bossBars.keySet())) {
            if (!this.connections.containsKey(uuid)) {
                this.hideBossBar(uuid);
            }
        }
    }

    private void hideBossBar(UUID uuid) {
        BossBar bar = this.bossBars.remove(uuid);
        this.bossBarViewers.remove(uuid);

        if (bar == null) {
            return;
        }

        Player player = Bukkit.getPlayer(uuid);
        if (player != null) {
            player.hideBossBar(bar);
        }
    }

    private void hideAllBossBars() {
        for (UUID uuid : new ArrayList<>(this.bossBars.keySet())) {
            this.hideBossBar(uuid);
        }
        this.bossBars.clear();
        this.bossBarViewers.clear();
    }

    private static BossBar.Color parseColor(String raw) {
        try {
            return BossBar.Color.valueOf(String.valueOf(raw).trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            SimpleVoiceChatMusic.LOGGER.warning("Unknown bossbar.color '" + raw
                    + "', falling back to PINK. Valid values: PINK/BLUE/RED/GREEN/YELLOW/PURPLE/WHITE");
            return BossBar.Color.PINK;
        }
    }

    private static BossBar.Overlay parseOverlay(String raw) {
        try {
            return BossBar.Overlay.valueOf(String.valueOf(raw).trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            SimpleVoiceChatMusic.LOGGER.warning("Unknown bossbar.overlay '" + raw
                    + "', falling back to PROGRESS. Valid values: PROGRESS/NOTCHED_10/NOTCHED_12/NOTCHED_20");
            return BossBar.Overlay.PROGRESS;
        }
    }

    /** 本组当前在听的玩家（音频通道存在的玩家），供歌词 ActionBar 使用 */
    public java.util.Set<UUID> listenerIds() {
        return java.util.Set.copyOf(this.connections.keySet());
    }

    public void setBassBoost(float percentage) {
        this.settingsStore.bassboost = percentage;
        final float multiplier = percentage / 100.00f;

        for (int i = 0; i < BASS_BOOST.length; i++) {
            this.equalizer.setGain(i, BASS_BOOST[i] * multiplier);
        }
    }

    public void setVolume(int volume) {
        int clamped = Math.max(0, Math.min(100, volume));
        this.settingsStore.volume = clamped;
        this.getPlayer().setVolume(clamped);
    }

    public final GroupSettingsManager getSettingsStore() {
        return this.settingsStore;
    }
}
