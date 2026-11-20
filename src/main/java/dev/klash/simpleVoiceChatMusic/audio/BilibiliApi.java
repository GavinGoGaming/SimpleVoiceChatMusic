package dev.klash.simpleVoiceChatMusic.audio;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.klash.simpleVoiceChatMusic.SimpleVoiceChatMusic;
import dev.klash.simpleVoiceChatMusic.config.PluginSettings;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * B 站搜索 / 取音频流。
 *
 * <p>搜索用 {@code api.bilibili.com/x/web-interface/search/type}，
 * 播放用 {@code view} 拿 cid、再 {@code player/playurl}（DASH）取音频流地址。
 * B 站会校验 {@code buvid3} Cookie，插件会自动访问一次首页获取并缓存。</p>
 */
public final class BilibiliApi {

    public record Video(String bvid, String title, String author, long durationMs) {
        public String identifier() {
            return "bilibili://" + bvid;
        }

        public String displayName() {
            return author == null || author.isBlank() ? title : author + " - " + title;
        }
    }

    public record Audio(String url, String title, String author) {
    }

    /** 音频直链 -> 标题（B 站直链没有文件名含义） */
    private static final Map<String, String> TITLES = new ConcurrentHashMap<>();

    private static final Pattern TAG = Pattern.compile("<[^>]+>");

    private static volatile String autoCookie;

    /** /music login bilibili 保存的 Cookie（优先于自动 buvid3） */
    private static volatile String loginCookie = "";

    private BilibiliApi() {
    }

    /**
     * 玩家直接输入的 BV 号 / av 号（不带协议），用于「全部音源」模式下不搜索、直接播放。
     */
    public static boolean isVideoId(@Nullable String input) {
        if (input == null) {
            return false;
        }

        String value = input.trim();

        if (value.toLowerCase(java.util.Locale.ROOT).startsWith("bilibili://")) {
            return true;
        }

        return value.matches("(?i)^BV[0-9A-Za-z]{10}$") || value.matches("(?i)^av\\d+$");
    }

    public static boolean isBilibiliIdentifier(@Nullable String input) {
        return input != null && input.startsWith("bilibili://");
    }

    @Nullable
    public static String bvidFromIdentifier(String identifier) {
        if (!isBilibiliIdentifier(identifier)) {
            return null;
        }
        String bvid = identifier.substring("bilibili://".length()).trim();
        return bvid.isEmpty() ? null : bvid;
    }

    /**
     * 从 B 站链接里取 BV 号（支持 {@code /video/BVxxx} 与 {@code b23.tv} 短链的 BV 参数）。
     */
    @Nullable
    public static String bvidFromUrl(String url) {
        Matcher matcher = Pattern.compile("(BV[0-9A-Za-z]{10})").matcher(url);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    @Nullable
    public static String titleFor(@Nullable String url) {
        return url == null ? null : TITLES.get(url);
    }

    // ------------------------------------------------------------------ network

    public static List<Video> search(String keyword, int limit) throws IOException {
        return search(keyword, limit, 1);
    }

    public static List<Video> search(String keyword, int limit, int page) throws IOException {
        PluginSettings settings = PluginSettings.get();
        String url = settings.bilibili().searchUrl()
                .replace("{query}", URLEncoder.encode(keyword, StandardCharsets.UTF_8))
                .replace("{limit}", Integer.toString(Math.max(1, limit)))
                .replace("&page=1", "&page=" + Math.max(1, page));

        return parseSearch(executeApi(url, settings));
    }

    /** 解析目标：BV 号 / av 号 / 番剧 ep / 番剧 season，外加分 P 序号 */
    public record Ref(String bvid, long aid, int page, long epId, long seasonId) {
    }

    /**
     * 从任意玩家输入解析音频：{@code bilibili://BVxxx}、视频链接、b23.tv 短链、av 号、番剧链接。
     */
    public static Audio resolveFromInput(String input) throws IOException {
        return resolve(parseRef(input));
    }

    private static Ref parseRef(String input) throws IOException {
        if (isBilibiliIdentifier(input)) {
            String bvid = bvidFromIdentifier(input);
            if (bvid == null) {
                throw new IOException("bilibili:// id does not contain a BV id");
            }
            return new Ref(bvid, 0L, 1, 0L, 0L);
        }

        String bvid = bvidFromUrl(input);
        long aid = aidFromUrl(input);
        int page = pageFromUrl(input);
        long epId = epIdFromUrl(input);
        long seasonId = seasonIdFromUrl(input);

        if (bvid != null || aid > 0L || epId > 0L || seasonId > 0L) {
            return new Ref(bvid, aid, page, epId, seasonId);
        }

        // 短链（b23.tv / bili2233.cn 等）：跟随跳转后再取 BV / av / ep / ss
        String resolved = followRedirect(input);
        String resolvedBvid = resolved == null ? null : bvidFromUrl(resolved);
        long resolvedAid = resolved == null ? 0L : aidFromUrl(resolved);
        int resolvedPage = resolved == null ? 1 : pageFromUrl(resolved);
        long resolvedEp = resolved == null ? 0L : epIdFromUrl(resolved);
        long resolvedSeason = resolved == null ? 0L : seasonIdFromUrl(resolved);

        if (resolvedBvid == null && resolvedAid <= 0L && resolvedEp <= 0L && resolvedSeason <= 0L) {
            // 有些短链不返回 302，而是直接吐一个带 BV 的 HTML 页面
            String body = fetchBody(input);
            String bodyBvid = body == null ? null : bvidFromUrl(body);

            if (bodyBvid != null) {
                return new Ref(bodyBvid, 0L, 1, 0L, 0L);
            }

            throw new IOException("Could not resolve a BV/av id from that link");
        }

        return new Ref(resolvedBvid, resolvedAid, resolvedPage, resolvedEp, resolvedSeason);
    }

    /**
     * 抓一次页面内容（短链兜底用，只读取有限长度）。
     */
    @Nullable
    private static String fetchBody(String url) {
        PluginSettings settings = PluginSettings.get();

        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(settings.httpConnectTimeoutMs(), TimeUnit.MILLISECONDS)
                .readTimeout(settings.httpReadTimeoutMs(), TimeUnit.MILLISECONDS)
                .followRedirects(true)
                .build();

        Request request = new Request.Builder()
                .url(url)
                .get()
                .header("User-Agent", settings.httpUserAgent())
                .build();

        try (Response response = client.newCall(request).execute()) {
            ResponseBody body = response.body();
            return body == null ? null : body.string();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 解析出音频流地址。
     */
    private static Audio resolve(Ref ref) throws IOException {
        PluginSettings settings = PluginSettings.get();

        // 番剧 / 影视：ep 或 ss 链接走 PGC 接口
        if (ref.epId() > 0L || ref.seasonId() > 0L) {
            return resolvePgc(ref, settings);
        }

        String viewUrl = ref.bvid() != null
                ? settings.bilibili().viewUrl().replace("{bvid}",
                        URLEncoder.encode(ref.bvid(), StandardCharsets.UTF_8))
                : settings.bilibili().viewUrlAid().replace("{aid}", Long.toString(ref.aid()));

        JsonObject view = parseObject(executeApi(viewUrl, settings), "view");
        checkCode(view, "view");
        JsonObject data = object(view, "data");

        String bvid = string(data, "bvid");
        if (bvid.isEmpty() && ref.bvid() != null) {
            bvid = ref.bvid();
        }

        long cid = cidForPage(data, ref.page());
        String title = string(data, "title");
        String author = object(data, "owner") == null ? "" : string(object(data, "owner"), "name");

        if (cid <= 0L) {
            throw new IOException("Bilibili view did not return a cid");
        }
        if (bvid.isEmpty()) {
            throw new IOException("Bilibili view did not return a BV id");
        }

        String playUrl = settings.bilibili().playUrl()
                .replace("{bvid}", URLEncoder.encode(bvid, StandardCharsets.UTF_8))
                .replace("{cid}", Long.toString(cid));

        JsonObject play = parseObject(executeApi(playUrl, settings), "playurl");
        checkCode(play, "playurl");
        String audioUrl = pickAudioUrl(object(play, "data"));

        if (audioUrl == null || audioUrl.isBlank()) {
            throw new IOException("Bilibili returned no audio stream (video may be paid/region locked)");
        }

        String display = author.isBlank() ? title : author + " - " + title;
        TITLES.put(audioUrl, display);
        TrackMetadata.put(audioUrl, title, author);

        return new Audio(audioUrl, title, author);
    }

    /**
     * 番剧 / 影视（pgc）：season 接口取分集，playurl 取音频。
     */
    private static Audio resolvePgc(Ref ref, PluginSettings settings) throws IOException {
        String seasonUrl = settings.bilibili().pgcSeasonUrl()
                .replace("{epId}", ref.epId() > 0L ? Long.toString(ref.epId()) : "")
                .replace("{seasonId}", ref.seasonId() > 0L ? Long.toString(ref.seasonId()) : "");

        JsonObject root = parseObject(executeApi(seasonUrl, settings), "pgc season");
        checkCode(root, "pgc season");

        JsonObject data = object(root, "result");
        if (data == null) {
            data = object(root, "data");
        }
        if (data == null) {
            throw new IOException("Bilibili pgc season returned no data");
        }

        String seasonTitle = string(data, "title");
        JsonArray episodes = array(data, "episodes");
        if (episodes == null || episodes.isEmpty()) {
            throw new IOException("Bilibili pgc season has no episodes");
        }

        JsonObject episode = null;
        for (JsonElement element : episodes) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject candidate = element.getAsJsonObject();
            if (ref.epId() <= 0L || number(candidate, "id") == ref.epId()) {
                episode = candidate;
                break;
            }
        }

        if (episode == null) {
            episode = episodes.get(0).getAsJsonObject();
        }

        long epId = number(episode, "id");
        long cid = number(episode, "cid");
        if (cid <= 0L) {
            throw new IOException("Bilibili pgc episode has no cid");
        }

        String epTitle = string(episode, "long_title");
        if (epTitle.isEmpty()) {
            epTitle = string(episode, "title");
        }
        String title = epTitle.isEmpty() ? seasonTitle : seasonTitle + " - " + epTitle;

        String playUrl = settings.bilibili().pgcPlayUrl()
                .replace("{epId}", Long.toString(epId))
                .replace("{cid}", Long.toString(cid));

        JsonObject play = parseObject(executeApi(playUrl, settings), "pgc playurl");
        checkCode(play, "pgc playurl");

        JsonObject playData = object(play, "result");
        if (playData == null) {
            playData = object(play, "data");
        }

        String audioUrl = pickAudioUrl(playData);
        if (audioUrl == null || audioUrl.isBlank()) {
            throw new IOException("Bilibili returned no audio stream (video may be paid/region locked)");
        }

        TITLES.put(audioUrl, title);
        TrackMetadata.put(audioUrl, title, "");
        return new Audio(audioUrl, title, "");
    }

    /**
     * B 站接口 HTTP 200 也可能是业务错误（-404 不存在、-403 权限、-412 风控），统一检查 code。
     */
    private static void checkCode(@Nullable JsonObject root, String what) throws IOException {
        if (root == null) {
            throw new IOException("Bilibili " + what + " returned an empty response");
        }

        int code = (int) number(root, "code");
        if (code != 0) {
            String message = string(root, "message");
            throw new IOException("Bilibili " + what + " failed: " + code + (message.isEmpty() ? "" : " " + message));
        }
    }

    public static long epIdFromUrl(String url) {
        Matcher matcher = Pattern.compile("/bangumi/play/ep(\\d+)", Pattern.CASE_INSENSITIVE).matcher(url);
        if (matcher.find()) {
            return Long.parseLong(matcher.group(1));
        }
        Matcher query = Pattern.compile("[?&]ep_id=(\\d+)").matcher(url);
        return query.find() ? Long.parseLong(query.group(1)) : 0L;
    }

    public static long seasonIdFromUrl(String url) {
        Matcher matcher = Pattern.compile("/bangumi/play/ss(\\d+)", Pattern.CASE_INSENSITIVE).matcher(url);
        if (matcher.find()) {
            return Long.parseLong(matcher.group(1));
        }
        Matcher query = Pattern.compile("[?&]season_id=(\\d+)").matcher(url);
        return query.find() ? Long.parseLong(query.group(1)) : 0L;
    }

    /**
     * 多 P 视频：{@code ?p=N} 取对应分 P 的 cid，否则用第一个。
     */
    private static long cidForPage(@Nullable JsonObject data, int page) {
        if (data == null) {
            return 0L;
        }

        if (page > 1) {
            JsonArray pages = array(data, "pages");
            if (pages != null) {
                for (JsonElement element : pages) {
                    if (!element.isJsonObject()) {
                        continue;
                    }
                    JsonObject part = element.getAsJsonObject();
                    if (number(part, "page") == page) {
                        return number(part, "cid");
                    }
                }
            }
        }

        return number(data, "cid");
    }

    /**
     * 跟随 b23.tv 的跳转，返回最终 URL（失败返回 null）。
     */
    @Nullable
    private static String followRedirect(String url) {
        PluginSettings settings = PluginSettings.get();

        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(settings.httpConnectTimeoutMs(), TimeUnit.MILLISECONDS)
                .readTimeout(settings.httpReadTimeoutMs(), TimeUnit.MILLISECONDS)
                .followRedirects(true)
                .build();

        Request request = new Request.Builder()
                .url(url)
                .get()
                .header("User-Agent", settings.httpUserAgent())
                .build();

        try (Response response = client.newCall(request).execute()) {
            return response.request().url().toString();
        } catch (Exception e) {
            return null;
        }
    }

    public static long aidFromUrl(String url) {
        Matcher matcher = Pattern.compile("/video/av(\\d+)", Pattern.CASE_INSENSITIVE).matcher(url);
        return matcher.find() ? Long.parseLong(matcher.group(1)) : 0L;
    }

    public static int pageFromUrl(String url) {
        Matcher matcher = Pattern.compile("[?&]p=(\\d+)").matcher(url);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : 1;
    }

    @Nullable
    private static String pickAudioUrl(@Nullable JsonObject data) {
        if (data == null) {
            return null;
        }

        // DASH：挑码率最高的一条音频
        JsonObject dash = object(data, "dash");
        if (dash != null) {
            JsonArray audios = array(dash, "audio");
            String best = null;
            long bestBandwidth = -1;

            if (audios != null) {
                for (JsonElement element : audios) {
                    if (!element.isJsonObject()) {
                        continue;
                    }
                    JsonObject audio = element.getAsJsonObject();
                    long bandwidth = number(audio, "bandwidth");
                    String url = string(audio, "baseUrl");
                    if (url.isEmpty()) {
                        url = string(audio, "base_url");
                    }
                    if (!url.isEmpty() && bandwidth > bestBandwidth) {
                        best = url;
                        bestBandwidth = bandwidth;
                    }
                }
            }

            if (best != null) {
                return best;
            }
        }

        // 老接口的 durl（单文件）
        JsonArray durl = array(data, "durl");
        if (durl != null && durl.size() > 0 && durl.get(0).isJsonObject()) {
            String url = string(durl.get(0).getAsJsonObject(), "url");
            if (!url.isEmpty()) {
                return url;
            }
        }

        return null;
    }

    private static String executeApi(String url, PluginSettings settings) throws IOException {
        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(settings.httpConnectTimeoutMs(), TimeUnit.MILLISECONDS)
                .readTimeout(settings.httpReadTimeoutMs(), TimeUnit.MILLISECONDS)
                .callTimeout(Math.max(settings.httpReadTimeoutMs(), 10000), TimeUnit.MILLISECONDS)
                .followRedirects(true)
                .build();

        Request.Builder builder = new Request.Builder()
                .url(url)
                .get()
                .header("Referer", "https://www.bilibili.com/");

        if (!settings.httpUserAgent().isEmpty()) {
            builder.header("User-Agent", settings.httpUserAgent());
        }

        String cookie = cookie(settings, client);
        if (!cookie.isEmpty()) {
            builder.header("Cookie", cookie);
        }

        try (Response response = client.newCall(builder.build()).execute()) {
            ResponseBody body = response.body();
            String json = body == null ? "" : body.string();

            if (!response.isSuccessful()) {
                throw new IOException("Bilibili API returned HTTP " + response.code());
            }

            return json;
        }
    }

    /**
     * B 站要求请求带 buvid3；没有配置 Cookie 时自动访问一次首页取一个并缓存。
     */
    private static String cookie(PluginSettings settings, OkHttpClient client) {
        String configured = loginCookie;
        if (!configured.isBlank()) {
            return configured;
        }

        if (autoCookie != null) {
            return autoCookie;
        }

        synchronized (BilibiliApi.class) {
            if (autoCookie != null) {
                return autoCookie;
            }

            try {
                Request request = new Request.Builder()
                        .url("https://www.bilibili.com/")
                        .get()
                        .header("User-Agent", settings.httpUserAgent())
                        .build();

                try (Response response = client.newCall(request).execute()) {
                    StringBuilder cookies = new StringBuilder();
                    for (String header : response.headers("Set-Cookie")) {
                        String value = header.split(";", 2)[0].trim();
                        if (value.startsWith("buvid3=") || value.startsWith("b_nut=")) {
                            if (cookies.length() > 0) {
                                cookies.append("; ");
                            }
                            cookies.append(value);
                        }
                    }

                    autoCookie = cookies.toString();
                }
            } catch (Exception e) {
                autoCookie = "";
            }

            return autoCookie;
        }
    }

    // ------------------------------------------------------------------ parsing

    /**
     * 搜索失败或为空时重试一次（首次请求可能因缺少 buvid3 被 B 站风控拦下）。
     */
    public static List<Video> searchWithRetry(String keyword, int limit) throws IOException {
        return searchWithRetry(keyword, limit, 1);
    }

    public static List<Video> searchWithRetry(String keyword, int limit, int page) throws IOException {
        IOException failure = null;

        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                List<Video> videos = search(keyword, limit, page);

                if (!videos.isEmpty()) {
                    return videos;
                }
            } catch (IOException e) {
                failure = e;
                autoCookie = null; // 让下一次重新取 buvid3
                SimpleVoiceChatMusic.LOGGER.warning("Bilibili search attempt " + (attempt + 1)
                        + " failed: " + e.getMessage());
            }

            if (attempt == 0) {
                try {
                    Thread.sleep(300L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        if (failure != null) {
            throw failure;
        }

        return List.of();
    }

    public static List<Video> parseSearch(@Nullable String json) throws IOException {
        List<Video> result = new ArrayList<>();
        if (json == null || json.isBlank()) {
            return result;
        }

        JsonObject root = parseObject(json, "search");
        JsonObject data = object(root, "data");
        if (data == null) {
            return result;
        }

        JsonArray list = array(data, "result");
        if (list == null) {
            return result;
        }

        for (JsonElement element : list) {
            if (!element.isJsonObject()) {
                continue;
            }

            JsonObject item = element.getAsJsonObject();
            String bvid = string(item, "bvid");
            if (bvid.isEmpty()) {
                continue;
            }

            String title = cleanTitle(string(item, "title"));
            String author = string(item, "author");

            result.add(new Video(bvid, title, author, parseDuration(string(item, "duration"))));
        }

        return result;
    }

    private static JsonObject parseObject(String json, String what) throws IOException {
        try {
            return JsonParser.parseString(json).getAsJsonObject();
        } catch (Exception e) {
            throw new IOException("Bilibili " + what + " returned invalid JSON", e);
        }
    }

    private static String cleanTitle(String raw) {
        String text = TAG.matcher(raw).replaceAll("");
        return text.replace("&quot;", "\"").replace("&amp;", "&").replace("&#39;", "'").trim();
    }

    /** 搜索结果里的时长形如 "4:19"，解析失败返回 0 */
    private static long parseDuration(String raw) {
        String[] parts = raw.split(":");
        try {
            if (parts.length == 2) {
                return (Long.parseLong(parts[0].trim()) * 60 + Long.parseLong(parts[1].trim())) * 1000L;
            }
            if (parts.length == 3) {
                return (Long.parseLong(parts[0].trim()) * 3600
                        + Long.parseLong(parts[1].trim()) * 60
                        + Long.parseLong(parts[2].trim())) * 1000L;
            }
        } catch (NumberFormatException ignored) {
            // 忽略
        }
        return 0L;
    }

    @Nullable
    private static JsonObject object(@Nullable JsonObject parent, String key) {
        if (parent == null || !parent.has(key) || !parent.get(key).isJsonObject()) {
            return null;
        }
        return parent.getAsJsonObject(key);
    }

    @Nullable
    private static JsonArray array(@Nullable JsonObject parent, String key) {
        if (parent == null || !parent.has(key) || !parent.get(key).isJsonArray()) {
            return null;
        }
        return parent.getAsJsonArray(key);
    }

    private static String string(JsonObject object, String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
            return "";
        }
        try {
            return object.get(key).getAsString();
        } catch (Exception e) {
            return "";
        }
    }

    private static long number(JsonObject object, String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
            return 0L;
        }
        try {
            return object.get(key).getAsLong();
        } catch (Exception e) {
            return 0L;
        }
    }

    // ---------------------------------------------------------------- login

    public static void setCookie(String cookie) {
        loginCookie = cookie == null ? "" : cookie.trim();
    }

    public static boolean isLoggedIn() {
        return loginCookie.contains("SESSDATA=");
    }

    /** 扫码登录用的二维码 */
    public record Qr(String url, String key) {
    }

    /**
     * 轮询结果：0=成功，86101=等待扫码，86090=已扫码，86038=二维码过期，其它=异常。
     */
    public record QrPoll(int code, @Nullable String cookie, @Nullable String nickname, @Nullable String detail) {
        public boolean waiting() {
            return code == 86101;
        }

        public boolean scanned() {
            return code == 86090;
        }

        public boolean success() {
            return code == 0;
        }

        public boolean expired() {
            return code == 86038;
        }

        public boolean failed() {
            return code != 0 && code != 86101 && code != 86090 && code != 86038;
        }
    }

    private record HttpAnswer(String body, String headerCookie, int status) {
    }

    private static volatile OkHttpClient loginClient;

    private static OkHttpClient loginClient() {
        if (loginClient == null) {
            synchronized (BilibiliApi.class) {
                if (loginClient == null) {
                    PluginSettings settings = PluginSettings.get();
                    loginClient = new OkHttpClient.Builder()
                            .connectTimeout(settings.httpConnectTimeoutMs(), TimeUnit.MILLISECONDS)
                            .readTimeout(settings.httpReadTimeoutMs(), TimeUnit.MILLISECONDS)
                            .build();
                }
            }
        }
        return loginClient;
    }

    private static HttpAnswer loginGet(String url, String cookie) throws IOException {
        PluginSettings settings = PluginSettings.get();

        Request request = new Request.Builder()
                .url(url)
                .get()
                .header("User-Agent", settings.httpUserAgent())
                .header("Referer", "https://www.bilibili.com/")
                .header("Cookie", cookie == null ? "" : cookie)
                .build();

        try (Response response = loginClient().newCall(request).execute()) {
            ResponseBody body = response.body();
            String text = body == null ? "" : body.string();

            if (response.code() >= 500) {
                throw new IOException("Bilibili HTTP " + response.code());
            }

            return new HttpAnswer(text, joinCookies(response), response.code());
        }
    }

    private static String joinCookies(Response response) {
        StringBuilder builder = new StringBuilder();

        for (String header : response.headers("Set-Cookie")) {
            String value = header.split(";", 2)[0].trim();
            if (value.isEmpty()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append("; ");
            }
            builder.append(value);
        }

        return builder.toString();
    }

    /**
     * 申请一个扫码登录二维码。
     */
    public static Qr qrGenerate() throws IOException {
        HttpAnswer answer = loginGet(
                "https://passport.bilibili.com/x/passport-login/web/qrcode/generate", "");
        JsonObject root = parseObject(answer.body(), "qr generate");

        if (((int) number(root, "code")) != 0) {
            throw new IOException("Bilibili qr generate failed: " + preview(answer.body()));
        }

        JsonObject data = object(root, "data");
        String url = string(data, "url");
        String key = string(data, "qrcode_key");

        if (url.isBlank() || key.isBlank()) {
            throw new IOException("Bilibili qr generate returned no url/key: " + preview(answer.body()));
        }

        return new Qr(url, key);
    }

    /**
     * 轮询扫码状态。
     */
    public static QrPoll qrPoll(String key) throws IOException {
        String url = "https://passport.bilibili.com/x/passport-login/web/qrcode/poll?qrcode_key="
                + URLEncoder.encode(key, StandardCharsets.UTF_8) + "&source=main-fe-header";

        HttpAnswer answer = loginGet(url, "");
        JsonObject root = parseObject(answer.body(), "qr poll");

        if (((int) number(root, "code")) != 0) {
            throw new IOException("Bilibili qr poll failed: " + preview(answer.body()));
        }

        JsonObject data = object(root, "data");
        int code = ((int) number(data, "code"));

        if (code != 0) {
            return new QrPoll(code, null, null, preview(answer.body()));
        }

        StringBuilder cookies = new StringBuilder();
        mergeCookie(cookies, answer.headerCookie());

        // 成功时 data.url 里也带 SESSDATA / bili_jct / DedeUserID
        String redirect = string(data, "url");
        int queryStart = redirect.indexOf('?');
        if (queryStart >= 0) {
            String query = redirect.substring(queryStart + 1);
            int fragment = query.indexOf('#');
            if (fragment >= 0) {
                query = query.substring(0, fragment);
            }
            for (String pair : query.split("&")) {
                int equals = pair.indexOf('=');
                if (equals <= 0) {
                    continue;
                }
                String name = pair.substring(0, equals);
                if (name.equals("SESSDATA") || name.equals("bili_jct") || name.equals("DedeUserID")) {
                    mergeCookie(cookies, pair);
                }
            }
        }

        return new QrPoll(0, cookies.toString(), null, null);
    }

    private static void mergeCookie(StringBuilder target, String cookieText) {
        if (cookieText == null || cookieText.isBlank()) {
            return;
        }

        for (String part : cookieText.split(";")) {
            String trimmed = part.trim();
            int equals = trimmed.indexOf('=');
            if (equals <= 0) {
                continue;
            }
            String name = trimmed.substring(0, equals);
            if (target.indexOf(name + "=") >= 0) {
                continue;
            }
            if (target.length() > 0) {
                target.append("; ");
            }
            target.append(trimmed);
        }
    }

    /**
     * 用 Cookie 取昵称（同时校验登录是否有效）。
     */
    @Nullable
    public static String nickname(String cookie) {
        try {
            HttpAnswer answer = loginGet("https://api.bilibili.com/x/web-interface/nav", cookie);
            JsonObject root = parseObject(answer.body(), "nav");

            if (((int) number(root, "code")) != 0) {
                return null;
            }

            String name = string(object(root, "data"), "uname");
            return name.isBlank() ? "" : name;
        } catch (Exception e) {
            return null;
        }
    }

    private static String preview(String json) {
        return json.length() > 200 ? json.substring(0, 200) : json;
    }
}
