package dev.klash.simpleVoiceChatMusic.audio;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManagers;
import de.maxhenkel.voicechat.api.Group;
import dev.klash.simpleVoiceChatMusic.SimpleVoiceChatMusic;
import dev.lavalink.youtube.YoutubeAudioSourceManager;
import org.bukkit.Server;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class MusicManager {
    private static final MusicManager INSTANCE = new MusicManager();

    public final AudioPlayerManager playerManager;
    /**
     * 多线程访问：命令线程（主线程）创建/读取，组自己的执行线程在 cleanup 里删除，
     * 因此必须用 ConcurrentHashMap。
     */
    private final Map<UUID, GroupManager> groups = new ConcurrentHashMap<>();

    private MusicManager() {
        SimpleVoiceChatMusic.LOGGER.info("Loading sources...");
        this.playerManager = new DefaultAudioPlayerManager();

        // allow hotswapping EQ levels
        this.playerManager.getConfiguration().setFilterHotSwapEnabled(true);

        AudioSourceManagers.registerRemoteSources(
                this.playerManager,
                // we will load v2 of yt music player
                com.sedmelluq.discord.lavaplayer.source.youtube.YoutubeAudioSourceManager.class,
                // HTTP 源要自己建，才能配置 UA / Referer / 超时
                com.sedmelluq.discord.lavaplayer.source.http.HttpAudioSourceManager.class
        );

        YoutubeAudioSourceManager ytSourceManager = new YoutubeAudioSourceManager();
        this.playerManager.registerSourceManager(ytSourceManager);

        // 兜底的直链音源放最后，避免把 YouTube 之类的链接当成普通文件
        this.playerManager.registerSourceManager(createHttpSourceManager());


        SimpleVoiceChatMusic.LOGGER.info("Loaded all sources!");
    }

    public static MusicManager getInstance() {
        return INSTANCE;
    }

    /**
     * 直链音源（http/https）。配置里可以指定 User-Agent / Referer 和超时，
     * 有些 CDN（防盗链）不带浏览器 UA 时会直接返回 0 字节。
     */
    private static com.sedmelluq.discord.lavaplayer.source.http.HttpAudioSourceManager createHttpSourceManager() {
        dev.klash.simpleVoiceChatMusic.config.PluginSettings settings =
                dev.klash.simpleVoiceChatMusic.config.PluginSettings.get();

        com.sedmelluq.discord.lavaplayer.source.http.HttpAudioSourceManager manager =
                new com.sedmelluq.discord.lavaplayer.source.http.HttpAudioSourceManager();

        manager.configureRequests(config -> org.apache.http.client.config.RequestConfig.copy(config)
                .setConnectTimeout(settings.httpConnectTimeoutMs())
                .setSocketTimeout(settings.httpReadTimeoutMs())
                .build());

        String userAgent = settings.httpUserAgent();
        String referer = settings.httpReferer();

        // B 站番剧/影视的音频 CDN（*.bilivideo.com）有防盗链，不带 Referer 直接 403；
        // 普通视频的 mcdn.bilivideo.cn 又不需要。这里按域名判断，只给 B 站 CDN 补 Referer，
        // 不影响其它直链。
        manager.configureBuilder(builder -> {
            if (!userAgent.isEmpty() || !referer.isEmpty()) {
                java.util.List<org.apache.http.Header> headers = new java.util.ArrayList<>();
                if (!userAgent.isEmpty()) {
                    headers.add(new org.apache.http.message.BasicHeader("User-Agent", userAgent));
                }
                if (!referer.isEmpty()) {
                    headers.add(new org.apache.http.message.BasicHeader("Referer", referer));
                }
                builder.setDefaultHeaders(headers);
            }

            builder.addInterceptorFirst((org.apache.http.HttpRequestInterceptor) (request, context) -> {
                if (request.containsHeader("Referer")) {
                    return;
                }

                // lavaplayer 发出去的请求行是相对路径（/upgcxcode/...），主机名要从 context 里拿
                String host = null;
                try {
                    org.apache.http.HttpHost target =
                            org.apache.http.client.protocol.HttpClientContext.adapt(context).getTargetHost();
                    if (target != null) {
                        host = target.getHostName();
                    }
                } catch (Exception ignored) {
                    // 拿不到主机名就按"不是 B 站"处理
                }

                if (host != null && (host.endsWith("bilivideo.com")
                        || host.endsWith("bilivideo.cn")
                        || host.endsWith("hdslb.com"))) {
                    request.setHeader("Referer", "https://www.bilibili.com/");
                }
            });
        });

        return manager;
    }

    public GroupManager getGroup(Group group, Server server) {
        return groups.computeIfAbsent(
                group.getId(),
                id -> new GroupManager(group, playerManager.createPlayer(), server)
        );
    }

    public GroupManager deleteGroup(Group group) {
        return groups.remove(group.getId());
    }

    /**
     * @return 当前所有语音组的快照（供 BOSS 栏刷新等全局任务遍历）
     */
    public java.util.Collection<GroupManager> groups() {
        return List.copyOf(groups.values());
    }

    /**
     * Destroys all groups
     */
    public void cleanup() {
        // 遍历副本：GroupManager.cleanup() 内部会调用 deleteGroup() 修改 groups
        for (GroupManager gm : List.copyOf(groups.values())) {
            gm.cleanup();
        }

        groups.clear();
    }
}
