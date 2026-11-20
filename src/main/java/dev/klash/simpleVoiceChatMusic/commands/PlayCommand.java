package dev.klash.simpleVoiceChatMusic.commands;

import dev.klash.simpleVoiceChatMusic.SimpleVoiceChatMusic;
import dev.klash.simpleVoiceChatMusic.audio.AllSourceSearch;
import dev.klash.simpleVoiceChatMusic.audio.BilibiliApi;
import dev.klash.simpleVoiceChatMusic.audio.GroupManager;
import dev.klash.simpleVoiceChatMusic.audio.HttpProbe;
import dev.klash.simpleVoiceChatMusic.audio.MusicManager;
import dev.klash.simpleVoiceChatMusic.audio.NetEaseApi;
import dev.klash.simpleVoiceChatMusic.audio.PlayLoadHandler;
import dev.klash.simpleVoiceChatMusic.audio.SpotifyHandler;
import dev.klash.simpleVoiceChatMusic.config.PlayerPreferences;
import dev.klash.simpleVoiceChatMusic.config.PluginSettings;
import dev.klash.simpleVoiceChatMusic.i18n.Messages;
import dev.klash.simpleVoiceChatMusic.i18n.Ph;
import dev.klash.simpleVoiceChatMusic.util.Cooldowns;
import dev.klash.simpleVoiceChatMusic.util.LinkCheckCache;
import dev.klash.simpleVoiceChatMusic.util.LinkResolver;
import dev.klash.simpleVoiceChatMusic.util.ModUtils;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;

import static dev.klash.simpleVoiceChatMusic.util.ModUtils.checkPlayerGroup;

public class PlayCommand implements Command {

    @Override
    public int execute(Player context, String[] args) {
        // 归一化：去掉首尾引号、还原 &amp;、把空格/中文编码成合法 URI
        final String input = LinkResolver.normalize(String.join(" ", args));

        if (input.isEmpty()) {
            Messages.send(context, "play.usage");
            return 1;
        }

        // 冷却：故障时玩家常常连续狂发同一条命令，这里挡一下
        long waitMs = Cooldowns.tryAcquire(context.getUniqueId(), "load", PluginSettings.get().playCooldownMs());
        if (waitMs > 0) {
            Messages.send(context, "general.cooldown",
                    Ph.raw("seconds", String.format(Locale.ROOT, "%.1f", waitMs / 1000.0)));
            return 1;
        }

        final LinkResolver.Type type = LinkResolver.typeOf(input);

        // B 站标识 / B 站链接：走 B 站 API（BV 号、av 号、b23.tv 短链都支持）
        if (type == LinkResolver.Type.BILIBILI) {
            playBilibili(context, input);
            return 0;
        }

        // 网易云：netease://id、163 歌曲/歌单链接（含 163cn.tv 短链）
        if (type == LinkResolver.Type.NETEASE) {
            playNetEase(context, input);
            return 0;
        }

        // 网易云关键词：netease:歌名 / wyy:歌名
        String lowerInput = input.toLowerCase(Locale.ROOT);
        if (lowerInput.startsWith("netease:") || lowerInput.startsWith("wyy:")) {
            String keyword = input.substring(input.indexOf(':') + 1).trim();

            if (keyword.isEmpty()) {
                Messages.send(context, "netease.search-usage");
                return 1;
            }

            searchNetEaseAndPlay(context, keyword);
            return 0;
        }

        // 明确不支持的平台
        if (type == LinkResolver.Type.UNSUPPORTED) {
            Messages.send(context, "play.unsupported-platform",
                    Ph.raw("platform", LinkResolver.platformOf(input)));
            return 1;
        }

        // Spotify 单曲：查元数据后转成搜索
        if (type == LinkResolver.Type.SPOTIFY) {
            if (!SpotifyHandler.isConfigured()) {
                Messages.send(context, "play.spotify-not-configured");
                runPlayTask(context, ModUtils.parseTrackId(input));
                return 0;
            }

            Bukkit.getScheduler().runTaskAsynchronously(SimpleVoiceChatMusic.get(), () -> {
                try {
                    String trackId = SpotifyHandler.extractTrackId(input);
                    if (trackId == null) {
                        runPlayTask(context, ModUtils.parseTrackId(input));
                        return;
                    }

                    SpotifyHandler.TrackInfo trackInfo = SpotifyHandler.fetchTrackInfo(trackId);
                    runPlayTask(context, "ytsearch:" + trackInfo.getCombinedQuery());
                } catch (Exception e) {
                    SimpleVoiceChatMusic.LOGGER.warning("Spotify lookup failed: " + e.getMessage());
                    runOnMainThread(() -> Messages.send(context, "play.spotify-failed",
                            Ph.raw("reason", String.valueOf(e.getMessage()))));
                    runPlayTask(context, ModUtils.parseTrackId(input));
                }
            });
            return 0;
        }

        // 普通关键词：按玩家自己设置的默认音源搜索/播放
        boolean prefixed = input.startsWith("ytsearch:")
                || input.startsWith("ytmsearch:")
                || input.startsWith("scsearch:");

        // 直接输入的 BV / av 号：不搜索，直接播放
        if (type == LinkResolver.Type.SEARCH && !prefixed && BilibiliApi.isVideoId(input)) {
            String identifier = input.trim().toLowerCase(java.util.Locale.ROOT).startsWith("bilibili://")
                    ? input.trim()
                    : "bilibili://" + input.trim();
            playBilibili(context, identifier);
            return 0;
        }

        if (type == LinkResolver.Type.SEARCH && !prefixed) {
            switch (PlayerPreferences.sourceFor(context)) {
                case ALL -> {
                    searchAllAndPlay(context, input);
                    return 0;
                }
                case NETEASE -> {
                    searchNetEaseAndPlay(context, input);
                    return 0;
                }
                case YOUTUBE -> {
                    runPlayTask(context, "ytsearch:" + input);
                    return 0;
                }
                case SOUNDCLOUD -> {
                    runPlayTask(context, "scsearch:" + input);
                    return 0;
                }
                case BILIBILI -> {
                    if (PluginSettings.get().bilibili().enabled()) {
                        searchBilibiliAndPlay(context, input);
                        return 0;
                    }
                }
            }
        }

        final String query = ModUtils.parseTrackId(input);

        // 直链先做一次"体检"（只读 1 KiB，不下载），链接是网页 / 404 / 防盗链时立刻提示
        if (type == LinkResolver.Type.DIRECT && PluginSettings.get().preflightEnabled()) {
            preflightThenPlay(context, query);
            return 0;
        }

        runPlayTask(context, query);
        return 0;
    }

    // ---------------------------------------------------------------- 网易云

    /**
     * 网易云歌曲 / 歌单链接。
     */
    private void playNetEase(Player context, String input) {
        SimpleVoiceChatMusic plugin = SimpleVoiceChatMusic.getOrNull();
        if (plugin == null || !plugin.isEnabled()) {
            return;
        }

        if (!PluginSettings.get().netEaseEnabled()) {
            Messages.send(context, "netease.disabled");
            return;
        }

        Messages.send(context, "play.loading");

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                Long id = NetEaseApi.idFromIdentifier(input);
                String source = input;

                if (id == null && source.contains("163cn.tv")) {
                    String resolved = NetEaseApi.followRedirect(source);
                    if (resolved == null) {
                        throw new java.io.IOException("could not resolve the short link");
                    }
                    source = resolved;
                }

                if (id == null) {
                    Long playlistId = NetEaseApi.playlistIdFromUrl(source);

                    if (playlistId != null) {
                        enqueueNetEasePlaylist(context, playlistId);
                        return;
                    }

                    id = NetEaseApi.songIdFromUrl(source);
                }

                if (id == null) {
                    throw new java.io.IOException("no song id in that link");
                }

                playNetEaseSong(context, id);
            } catch (Exception e) {
                SimpleVoiceChatMusic.LOGGER.warning("NetEase resolve failed for " + input + ": " + e.getMessage());
                runOnMainThread(() -> Messages.send(context, "netease.error",
                        Ph.raw("reason", String.valueOf(e.getMessage()))));
            }
        });
    }

    /**
     * 用关键词搜索网易云并播放第一条。
     */
    private void searchNetEaseAndPlay(Player context, String keyword) {
        SimpleVoiceChatMusic plugin = SimpleVoiceChatMusic.getOrNull();
        if (plugin == null || !plugin.isEnabled()) {
            return;
        }

        if (!PluginSettings.get().netEaseEnabled()) {
            Messages.send(context, "netease.disabled");
            return;
        }

        Messages.send(context, "play.loading");

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                List<NetEaseApi.Song> songs = NetEaseApi.search(keyword, 1);

                if (songs.isEmpty()) {
                    runOnMainThread(() -> Messages.send(context, "netease.no-results"));
                    return;
                }

                NetEaseApi.Song song = songs.get(0);
                String url = NetEaseApi.songUrl(song.id());

                if (url == null || url.isBlank()) {
                    runOnMainThread(() -> Messages.send(context, "netease.vip-required"));
                    return;
                }

                NetEaseApi.remember(url, song);
                runPlayTask(context, url);
            } catch (Exception e) {
                SimpleVoiceChatMusic.LOGGER.warning("NetEase search failed: " + e.getMessage());
                runOnMainThread(() -> Messages.send(context, "netease.error",
                        Ph.raw("reason", String.valueOf(e.getMessage()))));
            }
        });
    }

    /**
     * 解析单曲并播放。
     */
    private void playNetEaseSong(Player context, long songId) {
        try {
            String url = NetEaseApi.songUrl(songId);

            if (url == null || url.isBlank()) {
                runOnMainThread(() -> Messages.send(context, "netease.vip-required"));
                return;
            }

            NetEaseApi.Song detail = NetEaseApi.songDetail(songId);
            if (detail == null) {
                detail = new NetEaseApi.Song(songId, "", "", 0L, false);
            }

            NetEaseApi.remember(url, detail);
            runPlayTask(context, url);
        } catch (Exception e) {
            SimpleVoiceChatMusic.LOGGER.warning("NetEase song url failed: " + e.getMessage());
            runOnMainThread(() -> Messages.send(context, "netease.error",
                    Ph.raw("reason", String.valueOf(e.getMessage()))));
        }
    }

    /**
     * 歌单：解析前 N 首的直链后静默入队，最后只发一条汇总消息。
     */
    private void enqueueNetEasePlaylist(Player context, long playlistId) {
        try {
            List<NetEaseApi.Song> songs = NetEaseApi.playlistDetail(playlistId);

            if (songs.isEmpty()) {
                runOnMainThread(() -> Messages.send(context, "netease.no-results"));
                return;
            }

            int limit = Math.min(songs.size(), Math.min(PluginSettings.get().maxQueueSize(), 20));
            List<String> urls = new java.util.ArrayList<>();

            for (int i = 0; i < limit; i++) {
                NetEaseApi.Song song = songs.get(i);
                try {
                    String url = NetEaseApi.songUrl(song.id());
                    if (url == null || url.isBlank()) {
                        continue;
                    }
                    NetEaseApi.remember(url, song);
                    urls.add(url);
                } catch (Exception ignored) {
                    // 单曲失败不影响其它
                }
            }

            if (urls.isEmpty()) {
                runOnMainThread(() -> Messages.send(context, "netease.vip-required"));
                return;
            }

            runOnMainThread(() -> {
                ModUtils.CheckPlayerGroup result = checkPlayerGroup(context);
                if (result == null) {
                    return;
                }

                GroupManager gm = MusicManager.getInstance().getGroup(result.group(), result.source().getServer());

                for (String url : urls) {
                    MusicManager.getInstance().playerManager.loadItemOrdered(
                            gm.getPlayer(), url, new PlayLoadHandler(result.source(), gm, url, true));
                }

                Messages.send(context, "netease.playlist-queued", Ph.num("count", urls.size()));
            });
        } catch (Exception e) {
            SimpleVoiceChatMusic.LOGGER.warning("NetEase playlist failed: " + e.getMessage());
            runOnMainThread(() -> Messages.send(context, "netease.error",
                    Ph.raw("reason", String.valueOf(e.getMessage()))));
        }
    }

    /**
     * 「全部音源」：并发搜索所有音源，播放排在最前面的结果。
     */
    private void searchAllAndPlay(Player context, String keyword) {
        SimpleVoiceChatMusic plugin = SimpleVoiceChatMusic.getOrNull();
        if (plugin == null || !plugin.isEnabled()) {
            return;
        }

        Messages.send(context, "search.all-loading");

        Bukkit.getScheduler().runTask(plugin, () -> {
            ModUtils.CheckPlayerGroup result = checkPlayerGroup(context);
            if (result == null) {
                return;
            }

            GroupManager group = MusicManager.getInstance().getGroup(result.group(), result.source().getServer());

            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                List<AllSourceSearch.Hit> hits = AllSourceSearch.search(group, keyword, 1, 6000);

                if (hits.isEmpty()) {
                    runOnMainThread(() -> Messages.send(context, "search.all-empty"));
                    return;
                }

                AllSourceSearch.Hit hit = hits.get(0);

                runOnMainThread(() -> {
                    Messages.send(context, "play.all-selected",
                            Ph.raw("source", Messages.plain(context, hit.sourceLangKey())));

                    dev.klash.simpleVoiceChatMusic.util.Cooldowns.beginBypass();
                    try {
                        context.performCommand(hit.command());
                    } finally {
                        dev.klash.simpleVoiceChatMusic.util.Cooldowns.endBypass();
                    }
                });
            });
        });
    }

    /**
     * 用关键词在 B 站搜索并播放第一条。
     */
    private void searchBilibiliAndPlay(Player context, String keyword) {
        SimpleVoiceChatMusic plugin = SimpleVoiceChatMusic.getOrNull();
        if (plugin == null || !plugin.isEnabled()) {
            return;
        }

        Messages.send(context, "play.loading");

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                List<BilibiliApi.Video> videos = BilibiliApi.searchWithRetry(keyword, 1);

                if (videos.isEmpty()) {
                    if (PluginSettings.get().bilibili().fallbackToYoutube()) {
                        runOnMainThread(() -> Messages.send(context, "bilibili.fallback"));
                        runPlayTask(context, ModUtils.parseTrackId(keyword));
                    } else {
                        runOnMainThread(() -> Messages.send(context, "bilibili.no-results"));
                    }
                    return;
                }

                playBilibili(context, videos.get(0).identifier());
            } catch (Exception e) {
                SimpleVoiceChatMusic.LOGGER.warning("Bilibili search failed: " + e.getMessage());

                if (PluginSettings.get().bilibili().fallbackToYoutube()) {
                    runOnMainThread(() -> Messages.send(context, "bilibili.fallback"));
                    runPlayTask(context, ModUtils.parseTrackId(keyword));
                } else {
                    runOnMainThread(() -> Messages.send(context, "bilibili.error",
                            Ph.raw("reason", String.valueOf(e.getMessage()))));
                }
            }
        });
    }

    /**
     * 任意 B 站输入 -> 音频流地址 -> 播放。
     * 支持 bilibili://BVxxx、/video/BVxxx、/video/avxxx、b23.tv 短链、多 P 的 ?p=N。
     */
    private void playBilibili(Player context, String input) {
        SimpleVoiceChatMusic plugin = SimpleVoiceChatMusic.getOrNull();
        if (plugin == null || !plugin.isEnabled()) {
            return;
        }

        Messages.send(context, "play.loading");

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                BilibiliApi.Audio audio = BilibiliApi.resolveFromInput(input);

                if (audio.url() == null || audio.url().isBlank()) {
                    runOnMainThread(() -> Messages.send(context, "bilibili.no-audio"));
                    return;
                }

                runPlayTask(context, audio.url());
            } catch (Exception e) {
                String message = String.valueOf(e.getMessage());
                SimpleVoiceChatMusic.LOGGER.warning("Bilibili resolve failed for " + input + ": " + message);

                boolean invalidLink = message.contains("Could not resolve");
                if (invalidLink) {
                    runOnMainThread(() -> Messages.send(context, "bilibili.invalid-link"));
                } else {
                    runOnMainThread(() -> Messages.send(context, "bilibili.error", Ph.raw("reason", message)));
                }
            }
        });
    }

    /**
     * 异步探测直链，然后回到主线程决定"播放"还是"报错"。
     * 探测只读取 1 KiB，不会把整首歌拉下来。
     */
    private void preflightThenPlay(Player context, String query) {
        // 同一个链接刚刚失败过：直接给结论，不再发请求、不再刷日志
        String cached = LinkCheckCache.failure(query);
        if (cached != null) {
            Messages.send(context, cached);
            return;
        }

        SimpleVoiceChatMusic plugin = SimpleVoiceChatMusic.getOrNull();
        if (plugin == null || !plugin.isEnabled()) {
            return;
        }

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            HttpProbe.Result result = HttpProbe.probe(query, PluginSettings.get());

            if (result.errorKey() == null) {
                LinkCheckCache.clear(query);
                runPlayTask(context, query);
                return;
            }

            if (!result.definitive()) {
                // 探测没有结论（超时 / 连接中断 / 空响应）：可能只是 CDN 抽风，
                // 交给 lavaplayer 试一次并自动重试，不要在这里误杀
                SimpleVoiceChatMusic.LOGGER.info("Link check inconclusive for " + query
                        + " (" + result.errorKey() + "), trying playback anyway");
                runPlayTask(context, query);
                return;
            }

            LinkCheckCache.record(query, result.errorKey(), PluginSettings.get().failureCacheSeconds());

            SimpleVoiceChatMusic.LOGGER.info("Link check failed for " + query
                    + ": " + result.errorKey() + " (HTTP " + result.status() + ")");

            runOnMainThread(() -> Messages.send(context, result.errorKey()));
        });
    }

    /**
     * 回主线程执行一段逻辑（插件已卸载时直接丢弃）。
     */
    private void runOnMainThread(Runnable task) {
        SimpleVoiceChatMusic plugin = SimpleVoiceChatMusic.getOrNull();
        if (plugin == null || !plugin.isEnabled()) {
            return;
        }

        Bukkit.getScheduler().runTask(plugin, task);
    }

    /**
     * Schedules a task on the main thread to load the track using the provided query.
     */
    private void runPlayTask(Player context, String query) {
        runOnMainThread(() -> {
            ModUtils.CheckPlayerGroup result = checkPlayerGroup(context);
            if (result == null) {
                return;
            }

            GroupManager gm = MusicManager.getInstance().getGroup(result.group(), result.source().getServer());
            Messages.send(result.source(), "play.loading");

            // LavaPlayer 的加载本身是异步的，但还是放到异步线程，避免任何实现变动阻塞主线程
            Bukkit.getScheduler().runTaskAsynchronously(SimpleVoiceChatMusic.get(), () ->
                    MusicManager.getInstance().playerManager.loadItemOrdered(
                            gm.getPlayer(),
                            query,
                            new PlayLoadHandler(result.source(), gm, query)
                    ));
        });
    }
}
