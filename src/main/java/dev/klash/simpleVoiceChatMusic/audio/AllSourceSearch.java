package dev.klash.simpleVoiceChatMusic.audio;

import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import dev.klash.simpleVoiceChatMusic.SimpleVoiceChatMusic;
import dev.klash.simpleVoiceChatMusic.config.PluginSettings;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 「全部音源」模式：一次搜索并发查询所有已支持音源。
 *
 * <p>B 站 / 网易云走各自的搜索接口；YouTube / SoundCloud 走 lavaplayer 搜索。
 * 任何一个音源失败都不影响其它音源的结果。</p>
 *
 * <p>YouTube / SoundCloud 在网络不通时会超时并打印大量 lavaplayer 日志，
 * 所以这里做了两件事：配置开关（{@code music.all-sources.*}）+ 失败后冷却 10 分钟，
 * 冷却期内直接跳过该源，不再反复重试。</p>
 */
public final class AllSourceSearch {

    /** 一条可点击的搜索结果 */
    public record Hit(String sourceId, String sourceLangKey, String display,
                      long durationMs, boolean vip, String command) {
    }

    /** 失败后的冷却时间 */
    private static final long COOLDOWN_MS = 10 * 60 * 1000L;

    /** 每个远程源最多等多久（避免拖慢整个搜索） */
    private static final long REMOTE_TIMEOUT_CAP_MS = 4000L;

    /** sourceId -> 冷却结束时间戳 */
    private static final Map<String, Long> UNAVAILABLE_UNTIL = new ConcurrentHashMap<>();

    private AllSourceSearch() {
    }

    /**
     * 并发搜索所有音源（阻塞，调用方必须在异步线程里调用）。
     *
     * @param group       当前语音组（YouTube / SoundCloud 需要它的播放器实例）
     * @param keyword     关键词
     * @param perSource   每个音源取多少条
     * @param timeoutMs   单个音源最长等待时间
     */
    public static List<Hit> search(GroupManager group, String keyword, int perSource, long timeoutMs) {
        PluginSettings settings = PluginSettings.get();
        int limit = Math.max(1, perSource);
        long remoteTimeout = Math.max(1000L, Math.min(timeoutMs, REMOTE_TIMEOUT_CAP_MS));

        ExecutorService pool = Executors.newFixedThreadPool(4, runnable -> {
            Thread thread = new Thread(runnable, "SVC-Music-SearchAll");
            thread.setDaemon(true);
            return thread;
        });

        List<Future<List<Hit>>> futures = new ArrayList<>();

        try {
            if (settings.bilibili().enabled()) {
                futures.add(pool.submit(() -> bilibili(keyword, limit)));
            }

            if (settings.netEaseEnabled()) {
                futures.add(pool.submit(() -> netEase(keyword, limit)));
            }

            if (group != null && settings.allSourcesYoutube() && isAvailable("youtube")) {
                futures.add(pool.submit(() -> {
                    if (!reachable("www.youtube.com", 443, 700)) {
                        markUnavailable("youtube", "host unreachable");
                        return List.of();
                    }
                    return lavaplayer(group, "ytsearch:" + keyword,
                            "youtube", "source.youtube", limit, remoteTimeout);
                }));
            }

            if (group != null && settings.allSourcesSoundcloud() && isAvailable("soundcloud")) {
                futures.add(pool.submit(() -> {
                    if (!reachable("soundcloud.com", 443, 700)) {
                        markUnavailable("soundcloud", "host unreachable");
                        return List.of();
                    }
                    return lavaplayer(group, "scsearch:" + keyword,
                            "soundcloud", "source.soundcloud", limit, remoteTimeout);
                }));
            }

            List<Hit> hits = new ArrayList<>();
            long deadline = System.currentTimeMillis() + remoteTimeout + 2000L;

            for (Future<List<Hit>> future : futures) {
                try {
                    long remaining = Math.max(1L, deadline - System.currentTimeMillis());
                    hits.addAll(future.get(remaining, TimeUnit.MILLISECONDS));
                } catch (Exception e) {
                    SimpleVoiceChatMusic.LOGGER.fine("A source did not return search results: " + e.getMessage());
                }
            }

            return hits;
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * 某个远程音源现在是否可以查询（配置开启 + 不在冷却期）。
     */
    public static boolean isAvailable(String sourceId) {
        Long until = UNAVAILABLE_UNTIL.get(sourceId);

        if (until == null) {
            return true;
        }

        if (System.currentTimeMillis() >= until) {
            UNAVAILABLE_UNTIL.remove(sourceId);
            return true;
        }

        return false;
    }

    private static void markUnavailable(String sourceId, String reason) {
        long until = System.currentTimeMillis() + COOLDOWN_MS;

        if (UNAVAILABLE_UNTIL.put(sourceId, until) == null) {
            // 只在"刚失败"时打印一次，冷却期内不再刷屏
            SimpleVoiceChatMusic.LOGGER.info("Skipping " + sourceId
                    + " in all-source search for 10 minutes: " + reason);
        }
    }

    private static void markAvailable(String sourceId) {
        UNAVAILABLE_UNTIL.remove(sourceId);
    }

    /**
     * 快速 TCP 连通性探测：连不上就完全不调用 lavaplayer，
     * 避免它自己打印成片的连接超时堆栈。
     */
    private static boolean reachable(String host, int port, int timeoutMs) {
        try (java.net.Socket socket = new java.net.Socket()) {
            socket.connect(new java.net.InetSocketAddress(host, port), timeoutMs);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static List<Hit> bilibili(String keyword, int limit) {
        List<Hit> hits = new ArrayList<>();

        try {
            for (BilibiliApi.Video video : BilibiliApi.searchWithRetry(keyword, limit)) {
                hits.add(new Hit("bilibili", "source.bilibili", video.displayName(),
                        video.durationMs(), false, "music play " + video.identifier()));
            }
        } catch (Exception e) {
            SimpleVoiceChatMusic.LOGGER.fine("Bilibili search failed in all-source mode: " + e.getMessage());
        }

        return hits;
    }

    private static List<Hit> netEase(String keyword, int limit) {
        List<Hit> hits = new ArrayList<>();

        try {
            for (NetEaseApi.Song song : NetEaseApi.search(keyword, limit)) {
                hits.add(new Hit("netease", "source.netease", song.displayName(),
                        song.durationMs(), song.vip(), "music play " + song.identifier()));
            }
        } catch (Exception e) {
            SimpleVoiceChatMusic.LOGGER.fine("NetEase search failed in all-source mode: " + e.getMessage());
        }

        return hits;
    }

    private static List<Hit> lavaplayer(GroupManager group, String query, String sourceId,
                                        String langKey, int limit, long timeoutMs) {
        List<Hit> hits = new ArrayList<>();
        AudioPlayer player = group.getPlayer();

        if (player == null) {
            return hits;
        }

        CompletableFuture<List<AudioTrack>> future = new CompletableFuture<>();

        MusicManager.getInstance().playerManager.loadItemOrdered(player, query, new AudioLoadResultHandler() {
            @Override
            public void trackLoaded(AudioTrack track) {
                future.complete(List.of(track));
            }

            @Override
            public void playlistLoaded(AudioPlaylist playlist) {
                future.complete(playlist == null ? List.of() : playlist.getTracks());
            }

            @Override
            public void noMatches() {
                future.complete(List.of());
            }

            @Override
            public void loadFailed(FriendlyException exception) {
                // 加载失败（网络 / 平台限制）：冷却一段时间，避免每次搜索都重试
                markUnavailable(sourceId, exception == null ? "load failed" : exception.getMessage());
                future.complete(List.of());
            }
        });

        try {
            List<AudioTrack> tracks = future.get(timeoutMs, TimeUnit.MILLISECONDS);
            markAvailable(sourceId);

            for (AudioTrack track : tracks) {
                if (hits.size() >= limit) {
                    break;
                }

                String display = track.getInfo().author == null || track.getInfo().author.isBlank()
                        ? track.getInfo().title
                        : track.getInfo().author + " - " + track.getInfo().title;

                hits.add(new Hit(sourceId, langKey, display, track.getDuration(), false,
                        "music play " + track.getIdentifier()));
            }
        } catch (Exception e) {
            markUnavailable(sourceId, "search timed out");
            SimpleVoiceChatMusic.LOGGER.fine(query + " search timed out: " + e.getMessage());
        }

        return hits;
    }
}
