package dev.klash.simpleVoiceChatMusic.config;

import dev.klash.simpleVoiceChatMusic.SimpleVoiceChatMusic;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * 插件配置（config.yml）的强类型视图。
 *
 * <p>在 {@code onEnable} 时读取一次，运行期只读，避免每个 tick 都去读 YAML。</p>
 */
public final class PluginSettings {

    /** 默认伪装成浏览器 UA：不少 CDN 对非浏览器 UA 直接返回 0 字节 */
    public static final String DEFAULT_BILIBILI_SEARCH =
            "https://api.bilibili.com/x/web-interface/search/type?search_type=video&keyword={query}&page=1&page_size={limit}";
    public static final String DEFAULT_BILIBILI_VIEW =
            "https://api.bilibili.com/x/web-interface/view?bvid={bvid}";
    public static final String DEFAULT_BILIBILI_VIEW_AID =
            "https://api.bilibili.com/x/web-interface/view?aid={aid}";
    public static final String DEFAULT_BILIBILI_PGC_SEASON =
            "https://api.bilibili.com/pgc/view/web/season?ep_id={epId}&season_id={seasonId}";
    public static final String DEFAULT_BILIBILI_PGC_PLAY =
            "https://api.bilibili.com/pgc/player/web/playurl?ep_id={epId}&cid={cid}&fnval=16&fnver=0&fourk=1";
    public static final String DEFAULT_BILIBILI_PLAY =
            "https://api.bilibili.com/x/player/playurl?bvid={bvid}&cid={cid}&fnval=16&fnver=0&fourk=1";

    public static final String DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0 Safari/537.36";

    private final int maxQueueSize;
    private final int maxSearchResults;
    private final int maxTitleLength;
    private final int maxAuthorLength;
    private final int defaultVolume;
    private final float defaultBassboost;
    private final boolean usePrefix;
    private final String defaultLanguage;
    private final boolean useClientLocale;

    private final boolean bossBarEnabled;
    private final int bossBarIntervalTicks;
    private final String bossBarColor;
    private final String bossBarOverlay;

    private final String httpUserAgent;
    private final String httpReferer;
    private final int httpConnectTimeoutMs;
    private final int httpReadTimeoutMs;
    private final boolean preflightEnabled;
    private final int preflightTimeoutMs;
    private final int failureCacheSeconds;
    private final int retryCount;
    private final int retryDelayMs;

    private final String searchClickColor;
    private final boolean searchClickBold;

    private final boolean autoGroupEnabled;
    private final String autoGroupName;
    private final String autoGroupPassword;
    private final boolean autoGroupPersistent;

    private final int configVersion;
    private final boolean menuEnabled;
    private final int menuQueuePageSize;
    private final int menuPreviewCount;
    private final int menuSearchResults;

    private final boolean netEaseEnabled;
    private final String netEaseQuality;
    private final String defaultSource;
    private final boolean allSourcesYoutube;
    private final boolean allSourcesSoundcloud;

    private final boolean lyricsEnabled;
    private final int lyricsUpdateIntervalTicks;
    private final int lyricsOffsetMs;
    private final String lyricsColor;
    private final boolean lyricsBold;
    private final String lyricsFont;

    private final int playCooldownMs;
    private final BilibiliOptions bilibili;

    /**
     * B 站相关配置。
     *
     */
    public record BilibiliOptions(boolean enabled, String searchUrl, String viewUrl,
                                  String viewUrlAid, String playUrl, String pgcSeasonUrl, String pgcPlayUrl,
                                  boolean fallbackToYoutube) {
    }
    private PluginSettings(int maxQueueSize, int maxSearchResults, int maxTitleLength, int maxAuthorLength,
                           int defaultVolume, float defaultBassboost, boolean usePrefix,
                           String defaultLanguage, boolean useClientLocale,
                           boolean bossBarEnabled, int bossBarIntervalTicks, String bossBarColor, String bossBarOverlay,
                           String httpUserAgent, String httpReferer, int httpConnectTimeoutMs, int httpReadTimeoutMs,
                           boolean preflightEnabled, int preflightTimeoutMs, int failureCacheSeconds, int retryCount, int retryDelayMs,
                           String searchClickColor, boolean searchClickBold,
                           boolean autoGroupEnabled, String autoGroupName, String autoGroupPassword,
                           boolean autoGroupPersistent,
                           int configVersion, boolean menuEnabled, int menuQueuePageSize, int menuPreviewCount, int menuSearchResults,
                           boolean netEaseEnabled, String netEaseQuality,
                           String defaultSource,
                           boolean allSourcesYoutube, boolean allSourcesSoundcloud,
                           boolean lyricsEnabled, int lyricsUpdateIntervalTicks, int lyricsOffsetMs, String lyricsColor,
                           boolean lyricsBold, String lyricsFont,
                           int playCooldownMs, BilibiliOptions bilibili) {
        this.maxQueueSize = maxQueueSize;
        this.maxSearchResults = maxSearchResults;
        this.maxTitleLength = maxTitleLength;
        this.maxAuthorLength = maxAuthorLength;
        this.defaultVolume = defaultVolume;
        this.defaultBassboost = defaultBassboost;
        this.usePrefix = usePrefix;
        this.defaultLanguage = defaultLanguage;
        this.useClientLocale = useClientLocale;
        this.bossBarEnabled = bossBarEnabled;
        this.bossBarIntervalTicks = bossBarIntervalTicks;
        this.bossBarColor = bossBarColor;
        this.bossBarOverlay = bossBarOverlay;
        this.httpUserAgent = httpUserAgent;
        this.httpReferer = httpReferer;
        this.httpConnectTimeoutMs = httpConnectTimeoutMs;
        this.httpReadTimeoutMs = httpReadTimeoutMs;
        this.preflightEnabled = preflightEnabled;
        this.preflightTimeoutMs = preflightTimeoutMs;
        this.failureCacheSeconds = failureCacheSeconds;
        this.retryCount = retryCount;
        this.retryDelayMs = retryDelayMs;
        this.searchClickColor = searchClickColor;
        this.searchClickBold = searchClickBold;
        this.autoGroupEnabled = autoGroupEnabled;
        this.autoGroupName = autoGroupName;
        this.autoGroupPassword = autoGroupPassword;
        this.autoGroupPersistent = autoGroupPersistent;
        this.configVersion = configVersion;
        this.menuEnabled = menuEnabled;
        this.menuQueuePageSize = menuQueuePageSize;
        this.menuPreviewCount = menuPreviewCount;
        this.menuSearchResults = menuSearchResults;
        this.netEaseEnabled = netEaseEnabled;
        this.netEaseQuality = netEaseQuality;
        this.defaultSource = defaultSource;
        this.allSourcesYoutube = allSourcesYoutube;
        this.allSourcesSoundcloud = allSourcesSoundcloud;
        this.lyricsEnabled = lyricsEnabled;
        this.lyricsUpdateIntervalTicks = lyricsUpdateIntervalTicks;
        this.lyricsOffsetMs = lyricsOffsetMs;
        this.lyricsColor = lyricsColor;
        this.lyricsBold = lyricsBold;
        this.lyricsFont = lyricsFont;
        this.playCooldownMs = playCooldownMs;
        this.bilibili = bilibili;
    }

    public static PluginSettings load(FileConfiguration config) {
        int maxQueueSize = clamp(config.getInt("music.max-queue-size", 1000), 1, 5000);
        int maxSearchResults = clamp(config.getInt("music.max-search-results", 10), 1, 50);
        int maxTitleLength = clamp(config.getInt("music.max-title-length", 120), 8, 512);
        int maxAuthorLength = clamp(config.getInt("music.max-author-length", 60), 4, 256);
        int defaultVolume = clamp(config.getInt("music.default-volume", 100), 0, 100);

        double bassRaw = config.getDouble("music.default-bassboost", 0.0D);
        float defaultBassboost = (float) Math.max(0.0D, Math.min(200.0D, bassRaw));

        boolean usePrefix = config.getBoolean("messages.use-prefix", true);

        String defaultLanguage = config.getString("language.default", "en_us");
        if (defaultLanguage == null || defaultLanguage.isBlank()) {
            defaultLanguage = "en_us";
        }

        boolean useClientLocale = config.getBoolean("language.use-client-locale", true);

        boolean bossBarEnabled = config.getBoolean("bossbar.enabled", true);
        int bossBarInterval = clamp(config.getInt("bossbar.update-interval-ticks", 20), 5, 200);
        String bossBarColor = config.getString("bossbar.color", "BLUE");
        String bossBarOverlay = config.getString("bossbar.overlay", "PROGRESS");

        String httpUserAgent = config.getString("music.http.user-agent", DEFAULT_USER_AGENT);
        if (httpUserAgent == null) {
            httpUserAgent = "";
        }

        String httpReferer = config.getString("music.http.referer", "");
        if (httpReferer == null) {
            httpReferer = "";
        }

        int httpConnectTimeout = clamp(config.getInt("music.http.connect-timeout-ms", 5000), 1000, 60000);
        int httpReadTimeout = clamp(config.getInt("music.http.read-timeout-ms", 15000), 1000, 120000);

        boolean preflightEnabled = config.getBoolean("music.http.preflight", true);
        int preflightTimeout = clamp(config.getInt("music.http.preflight-timeout-ms", 4000), 1000, 30000);
        int failureCache = clamp(config.getInt("music.http.failure-cache-seconds", 30), 0, 3600);
        int retryCount = clamp(config.getInt("music.http.retry-count", 2), 0, 5);
        int retryDelay = clamp(config.getInt("music.http.retry-delay-ms", 1500), 200, 10000);

        int playCooldown = clamp(config.getInt("music.command-cooldown-ms", 1000), 0, 60000);

        String searchClickColor = config.getString("music.search.click-color", "green");
        if (searchClickColor == null) {
            searchClickColor = "";
        }
        boolean searchClickBold = config.getBoolean("music.search.click-bold", true);

        boolean autoGroupEnabled = config.getBoolean("music.auto-group.enabled", true);
        String autoGroupName = config.getString("music.auto-group.name", "音乐");
        if (autoGroupName == null) {
            autoGroupName = "音乐";
        }
        String autoGroupPassword = config.getString("music.auto-group.password", "");
        if (autoGroupPassword == null) {
            autoGroupPassword = "";
        }
        boolean autoGroupPersistent = config.getBoolean("music.auto-group.persistent", true);

        int configVersion = config.getInt("config-version", 0);
        boolean menuEnabled = config.getBoolean("menu.enabled", true);
        int menuQueuePageSize = clamp(config.getInt("menu.queue-page-size", 20), 5, 50);
        int menuPreviewCount = clamp(config.getInt("menu.preview-count", 3), 1, 10);
        int menuSearchResults = clamp(config.getInt("menu.search-results", 100), 1, 200);

        boolean netEaseEnabled = config.getBoolean("netease.enabled", true);
        String netEaseQuality = config.getString("netease.quality", "exhigh");
        if (netEaseQuality == null || netEaseQuality.isBlank()) {
            netEaseQuality = "exhigh";
        }
        String defaultSource = config.getString("music.default-source", "bilibili");
        boolean allSourcesYoutube = config.getBoolean("music.all-sources.youtube", true);
        boolean allSourcesSoundcloud = config.getBoolean("music.all-sources.soundcloud", true);
        if (defaultSource == null || defaultSource.isBlank()) {
            defaultSource = "bilibili";
        }

        boolean lyricsEnabled = config.getBoolean("lyrics.enabled", true);
        int lyricsUpdateIntervalTicks = clamp(config.getInt("lyrics.update-interval-ticks", 10), 2, 100);
        int lyricsOffsetMs = config.getInt("lyrics.offset-ms", 0);
        String lyricsColor = config.getString("lyrics.color", "blue");
        if (lyricsColor == null) {
            lyricsColor = "blue";
        }
        boolean lyricsBold = config.getBoolean("lyrics.bold", true);
        String lyricsFont = config.getString("lyrics.font", "");
        if (lyricsFont == null) {
            lyricsFont = "";
        }

        BilibiliOptions bilibili = new BilibiliOptions(
                config.getBoolean("bilibili.enabled", true),
                config.getString("bilibili.search-url", DEFAULT_BILIBILI_SEARCH),
                config.getString("bilibili.view-url", DEFAULT_BILIBILI_VIEW),
                config.getString("bilibili.view-url-aid", DEFAULT_BILIBILI_VIEW_AID),
                config.getString("bilibili.play-url", DEFAULT_BILIBILI_PLAY),
                config.getString("bilibili.pgc-season-url", DEFAULT_BILIBILI_PGC_SEASON),
                config.getString("bilibili.pgc-play-url", DEFAULT_BILIBILI_PGC_PLAY),
                config.getBoolean("bilibili.fallback-to-youtube", true));

        return new PluginSettings(maxQueueSize, maxSearchResults, maxTitleLength, maxAuthorLength,
                defaultVolume, defaultBassboost, usePrefix, defaultLanguage, useClientLocale,
                bossBarEnabled, bossBarInterval, bossBarColor, bossBarOverlay,
                httpUserAgent.trim(), httpReferer.trim(), httpConnectTimeout, httpReadTimeout,
                preflightEnabled, preflightTimeout, failureCache, retryCount, retryDelay,
                searchClickColor.trim(), searchClickBold,
                autoGroupEnabled, autoGroupName.trim(), autoGroupPassword, autoGroupPersistent,
                configVersion, menuEnabled, menuQueuePageSize, menuPreviewCount, menuSearchResults,
                netEaseEnabled, netEaseQuality, defaultSource.trim(),
                allSourcesYoutube, allSourcesSoundcloud,
                lyricsEnabled, lyricsUpdateIntervalTicks, lyricsOffsetMs, lyricsColor.trim(),
                lyricsBold, lyricsFont.trim(),
                playCooldown, bilibili);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    public int maxQueueSize() {
        return maxQueueSize;
    }

    public int maxSearchResults() {
        return maxSearchResults;
    }

    public int maxTitleLength() {
        return maxTitleLength;
    }

    public int maxAuthorLength() {
        return maxAuthorLength;
    }

    public int defaultVolume() {
        return defaultVolume;
    }

    public float defaultBassboost() {
        return defaultBassboost;
    }

    public boolean usePrefix() {
        return usePrefix;
    }

    public String defaultLanguage() {
        return defaultLanguage;
    }

    public boolean useClientLocale() {
        return useClientLocale;
    }

    public boolean bossBarEnabled() {
        return bossBarEnabled;
    }

    public int bossBarIntervalTicks() {
        return bossBarIntervalTicks;
    }

    public String bossBarColor() {
        return bossBarColor;
    }

    public String bossBarOverlay() {
        return bossBarOverlay;
    }

    public String httpUserAgent() {
        return httpUserAgent;
    }

    public String httpReferer() {
        return httpReferer;
    }

    public int httpConnectTimeoutMs() {
        return httpConnectTimeoutMs;
    }

    public int httpReadTimeoutMs() {
        return httpReadTimeoutMs;
    }

    public boolean preflightEnabled() {
        return preflightEnabled;
    }

    public int preflightTimeoutMs() {
        return preflightTimeoutMs;
    }

    public int failureCacheSeconds() {
        return failureCacheSeconds;
    }

    public int retryCount() {
        return retryCount;
    }

    public int retryDelayMs() {
        return retryDelayMs;
    }

    public BilibiliOptions bilibili() {
        return bilibili;
    }

    /** 搜索结果「[点击加入队列]」的颜色（色名或 #rrggbb，空串表示用语言文件样式） */
    public String searchClickColor() {
        return searchClickColor;
    }

    /** 搜索结果「[点击加入队列]」是否加粗 */
    public boolean searchClickBold() {
        return searchClickBold;
    }

    /** 服务器启动完成后是否自动创建音乐群组 */
    public boolean autoGroupEnabled() {
        return autoGroupEnabled;
    }

    public String autoGroupName() {
        return autoGroupName;
    }

    public String autoGroupPassword() {
        return autoGroupPassword;
    }

    public boolean autoGroupPersistent() {
        return autoGroupPersistent;
    }

    /** config.yml 的版本标记，用于提示旧配置 */
    public int configVersion() {
        return configVersion;
    }

    /** 是否允许 /music menu（需要 Paper 1.21.7+） */
    public boolean menuEnabled() {
        return menuEnabled;
    }

    /** 队列页面最多显示多少首 */
    public int menuQueuePageSize() {
        return menuQueuePageSize;
    }

    /** 主菜单预览接下来几首 */
    public int menuPreviewCount() {
        return menuPreviewCount;
    }

    /** 菜单搜索结果默认展示数量 */
    public int menuSearchResults() {
        return menuSearchResults;
    }

    /** 是否启用网易云音源 */
    public boolean netEaseEnabled() {
        return netEaseEnabled;
    }

    /** 网易云音质档位（standard / higher / exhigh / lossless / hires） */
    public String netEaseQuality() {
        return netEaseQuality;
    }

    /** 玩家没设置过音源时用的默认音源（bilibili / netease / youtube / soundcloud） */
    public String defaultSource() {
        return defaultSource;
    }

    /** 全部音源模式下是否查询 YouTube */
    public boolean allSourcesYoutube() {
        return allSourcesYoutube;
    }

    /** 全部音源模式下是否查询 SoundCloud */
    public boolean allSourcesSoundcloud() {
        return allSourcesSoundcloud;
    }

    /** 是否在 ActionBar 显示歌词 */
    public boolean lyricsEnabled() {
        return lyricsEnabled;
    }

    public int lyricsUpdateIntervalTicks() {
        return lyricsUpdateIntervalTicks;
    }

    /** 歌词整体偏移（毫秒），用于修正字幕与音频的偏差 */
    public int lyricsOffsetMs() {
        return lyricsOffsetMs;
    }

    public String lyricsColor() {
        return lyricsColor;
    }

    /** 歌词是否加粗 */
    public boolean lyricsBold() {
        return lyricsBold;
    }

    /** 歌词使用的字体（例如 minecraft:default 或资源包里的自定义字体），空串 = 默认 */
    public String lyricsFont() {
        return lyricsFont;
    }

    public int playCooldownMs() {
        return playCooldownMs;
    }

    /**
     * 便捷访问器：拿当前插件实例的配置。
     */
    public static PluginSettings get() {
        SimpleVoiceChatMusic plugin = SimpleVoiceChatMusic.getOrNull();
        if (plugin == null || plugin.settings() == null) {
            // 正常情况下不会发生；退化为默认值，避免命令直接抛 NPE
            return DEFAULTS;
        }
        return plugin.settings();
    }

    private static final PluginSettings DEFAULTS = new PluginSettings(
            100, 5, 120, 60, 100, 0f, true, "en_us", true,
            true, 20, "PINK", "PROGRESS",
            DEFAULT_USER_AGENT, "", 5000, 15000, true, 4000, 30, 2, 1500, "green", true,
            true, "音乐", "", true,
            1, true, 20, 3, 100,
            true, "exhigh", "bilibili", true, true,
            true, 10, 0, "blue", true, "",
            1000,
            new BilibiliOptions(true, DEFAULT_BILIBILI_SEARCH, DEFAULT_BILIBILI_VIEW, DEFAULT_BILIBILI_VIEW_AID,
                    DEFAULT_BILIBILI_PLAY, DEFAULT_BILIBILI_PGC_SEASON, DEFAULT_BILIBILI_PGC_PLAY, true));
}
