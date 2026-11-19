package dev.klash.simpleVoiceChatMusic.audio;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.klash.simpleVoiceChatMusic.SimpleVoiceChatMusic;
import dev.klash.simpleVoiceChatMusic.config.PluginSettings;
import dev.klash.simpleVoiceChatMusic.util.NetEaseCrypto;
import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 网易云音乐 API（weapi / eapi），用法与 NeteaseCloudMusicApi 一致。
 *
 * <p>登录态（{@code MUSIC_U} 等 Cookie）由服主通过 {@code /music login 网易云} 扫码获得，
 * 保存在插件数据目录里；这里只持有内存副本，所有玩家共用。</p>
 */
public final class NetEaseApi {

    public record Song(long id, String title, String artist, long durationMs, boolean vip) {
        public String identifier() {
            return "netease://" + id;
        }

        public String displayName() {
            return artist == null || artist.isBlank() ? title : artist + " - " + title;
        }
    }

    public record QrPoll(int code, @Nullable String cookie, @Nullable String nickname, @Nullable String detail) {
        public boolean scanned() {
            return code == 802;
        }

        public boolean success() {
            return code == 803;
        }

        public boolean expired() {
            return code == 800;
        }

        /** 未知状态（例如 8821 风控） */
        public boolean failed() {
            return code != 800 && code != 801 && code != 802 && code != 803;
        }
    }

    public record LyricLine(long timeMs, String text) {
    }

    /** 音频直链 -> 歌曲元数据（标题 / 歌词 id），用于 ActionBar 歌词与标题显示 */
    private static final Map<String, Song> META_BY_URL = new ConcurrentHashMap<>();

    /** 歌曲 id -> 解析好的歌词；null 表示还没取过，空列表表示该歌没有歌词 */
    private static final Map<Long, List<LyricLine>> LYRICS = new ConcurrentHashMap<>();

    private static final Set<Long> FETCHING = ConcurrentHashMap.newKeySet();

    private static volatile String cookie = "";

    /** 二维码登录会话的 Cookie（每次 key/check 都会下发新 Cookie，必须带上，否则永远等不到 803） */
    private static volatile String qrSessionCookie = "";

    /** 当前扫码流程用的接口（明文 /api 或 weapi） */
    private static volatile QrMode qrMode = QrMode.PLAIN;

    /** 稳定的设备 ID（网页登录的 chainId / Cookie 里要用） */
    private static volatile String deviceId;

    private static final Pattern SONG_ID = Pattern.compile("[?&#/]id=(\\d+)");
    private static final Pattern NETEASE_ID = Pattern.compile("netease://(\\d+)");

    private NetEaseApi() {
    }

    // ------------------------------------------------------------- login state

    public static void setCookie(@Nullable String value) {
        cookie = value == null ? "" : value;
    }

    public static String cookie() {
        return cookie;
    }

    public static boolean isLoggedIn() {
        return cookie.contains("MUSIC_U=");
    }

    // ---------------------------------------------------------------- caching

    public static void remember(String url, Song song) {
        if (url != null && !url.isBlank()) {
            META_BY_URL.put(url, song);
            TrackMetadata.put(url, song.title(), song.artist());
        }
    }

    @Nullable
    public static Song metaFor(@Nullable String url) {
        return url == null ? null : META_BY_URL.get(url);
    }

    @Nullable
    public static String titleFor(@Nullable String url) {
        Song song = metaFor(url);
        return song == null ? null : song.displayName();
    }

    private static final Map<String, List<LyricLine>> TITLE_LYRICS = new ConcurrentHashMap<>();
    private static final Set<String> TITLE_FETCHING = ConcurrentHashMap.newKeySet();

    /**
     * 非网易云歌曲：按"歌名 + 歌手"去网易云匹配歌词。
     *
     * @return 已缓存的歌词行；还没取到（正在异步取）返回 null
     */
    @Nullable
    public static List<LyricLine> lyricsForTitle(@Nullable String title) {
        String key = normalizeTitle(title);

        if (key.isEmpty()) {
            return java.util.Collections.emptyList();
        }

        List<LyricLine> cached = TITLE_LYRICS.get(key);
        if (cached != null) {
            return cached;
        }

        if (TITLE_FETCHING.add(key)) {
            Thread worker = new Thread(() -> fetchTitleLyrics(key), "SVC-Music-Lyrics");
            worker.setDaemon(true);
            worker.start();
        }

        return null;
    }

    private static void fetchTitleLyrics(String key) {
        List<LyricLine> lines = java.util.Collections.emptyList();

        try {
            for (Song song : search(key, 5)) {
                String raw = rawLyric(song.id());

                if (raw == null || raw.isBlank()) {
                    continue;
                }

                List<LyricLine> parsed = parseLyric(raw);

                if (!parsed.isEmpty()) {
                    lines = parsed;
                    break;
                }
            }
        } catch (Exception e) {
            SimpleVoiceChatMusic.LOGGER.fine("Could not match lyrics for '" + key + "': " + e.getMessage());
        }

        TITLE_LYRICS.put(key, lines);
        TITLE_FETCHING.remove(key);
    }

    /** 去掉【】()[] 里的修饰词，便于跨平台匹配歌词 */
    public static String normalizeTitle(@Nullable String raw) {
        if (raw == null) {
            return "";
        }

        String text = raw
                .replaceAll("[（(\\[【].*?[）)\\]】]", " ")
                .replaceAll("\\s+", " ")
                .trim();

        return text.length() > 80 ? text.substring(0, 80) : text;
    }

    @Nullable
    public static List<LyricLine> lyricsFor(long songId) {
        return LYRICS.get(songId);
    }

    public static void cacheLyrics(long songId, @Nullable List<LyricLine> lines) {
        LYRICS.put(songId, lines == null ? Collections.emptyList() : lines);
    }

    // ---------------------------------------------------------------- helpers

    @Nullable
    public static Long idFromIdentifier(String identifier) {
        Matcher matcher = NETEASE_ID.matcher(identifier == null ? "" : identifier);
        return matcher.find() ? Long.parseLong(matcher.group(1)) : null;
    }

    @Nullable
    public static Long songIdFromUrl(String url) {
        if (url == null) {
            return null;
        }
        Matcher matcher = SONG_ID.matcher(url);
        return matcher.find() ? Long.parseLong(matcher.group(1)) : null;
    }

    // ------------------------------------------------------------------ auth

    /**
     * 获取二维码登录的 unikey。
     *
     * <p>type=3 是当前（2026）网页/客户端扫码流程的类型；旧的 type=1 只能扫到"已扫描"，
     * 永远不会下发 803 授权成功。</p>
     *
     * <p>优先走新版网页端的明文接口 {@code /api/login/qrcode/unikey}，
     * 失败再退回 weapi（两条路都带同一份设备 Cookie 与扫码会话 Cookie）。</p>
     */
    public static String qrKey() throws IOException {
        try {
            String key = qrKeyPlain();
            qrMode = QrMode.PLAIN;
            return key;
        } catch (IOException plainError) {
            warn("NetEase plain qrKey failed ("
                    + plainError.getMessage() + "), falling back to weapi");
        }

        String key = qrKeyWeapi();
        qrMode = QrMode.WEAPI;
        return key;
    }

    /** 重新开始一次扫码会话（清掉上一轮 Cookie） */
    public static void resetQrSession() {
        qrSessionCookie = "";
    }

    /** 二维码流程用的接口 */
    public enum QrMode {
        PLAIN,
        WEAPI
    }

    /**
     * 二维码内容。type=3 属于网页流程，需要带 chainId（与网易云网页登录保持一致）。
     */
    public static String qrContent(String key) {
        return "https://music.163.com/login?codekey=" + key
                + "&chainId=v1_" + deviceId() + "_web_login_" + System.currentTimeMillis();
    }

    /**
     * 用浏览器/客户端里已有的 Cookie 直接登录（二维码被风控时的备用方案）。
     *
     * @return 成功时返回昵称（可能为空串），失败返回 null
     */
    @Nullable
    public static String loginWithCookie(String cookieText) {
        if (cookieText == null || !cookieText.contains("MUSIC_U=")) {
            return null;
        }

        String previous = cookie;
        setCookie(cookieText.trim());

        String nickname = nickname();

        if (nickname == null) {
            setCookie(previous);
            return null;
        }

        return nickname;
    }

    // -------------------------------------------------------------- qr: plain

    private static String qrKeyPlain() throws IOException {
        HttpAnswer answer = plainPost("https://music.163.com/api/login/qrcode/unikey", "type=3");
        mergeQrSession(answer.headerCookie());

        JsonObject root = parseJsonOrThrow(answer, "qrKey");
        int code = intOf(root, "code");

        if (code != 200) {
            throw new IOException("NetEase qrKey failed: code=" + code + " body=" + preview(answer.body()));
        }

        String unikey = stringOf(root, "unikey");
        if (unikey.isEmpty()) {
            throw new IOException("NetEase qrKey returned no unikey: " + preview(answer.body()));
        }

        return unikey;
    }

    private static QrPoll qrPollPlain(String key) throws IOException {
        HttpAnswer answer = plainPost("https://music.163.com/api/login/qrcode/client/login",
                "key=" + urlEncode(key) + "&type=3");
        mergeQrSession(answer.headerCookie());

        JsonObject root = parseJsonOrThrow(answer, "qrPoll");
        return toPoll(root, answer);
    }

    // -------------------------------------------------------------- qr: weapi

    private static String qrKeyWeapi() throws IOException {
        HttpAnswer answer = weapiSessionPost("https://music.163.com/weapi/login/qrcode/unikey?csrf_token=",
                "{\"type\":3,\"csrf_token\":\"\"}");
        mergeQrSession(answer.headerCookie());

        JsonObject root = parseObject(answer.body());
        int code = intOf(root, "code");

        if (code != 200) {
            throw new IOException("NetEase qrKey failed: code=" + code + " body=" + preview(answer.body()));
        }

        String unikey = stringOf(root, "unikey");
        if (unikey.isEmpty()) {
            throw new IOException("NetEase qrKey returned no unikey: " + preview(answer.body()));
        }

        return unikey;
    }

    private static QrPoll qrPollWeapi(String key) throws IOException {
        HttpAnswer answer = weapiSessionPost("https://music.163.com/weapi/login/qrcode/client/login?csrf_token=",
                "{\"key\":\"" + escapeJson(key) + "\",\"type\":3,\"csrf_token\":\"\"}");
        mergeQrSession(answer.headerCookie());

        JsonObject root = parseJsonOrThrow(answer, "qrPoll");
        return toPoll(root, answer);
    }

    private static QrPoll toPoll(JsonObject root, HttpAnswer answer) {
        int code = intOf(root, "code");

        if (code == 803) {
            StringBuilder merged = new StringBuilder();
            appendCookies(merged, stringOf(root, "cookie"));
            appendCookies(merged, answer.headerCookie());

            if (merged.length() == 0) {
                merged.append(cookie);
            }

            return new QrPoll(code, merged.toString(), stringOf(root, "nickname"), null);
        }

        // 非成功状态把响应体带回去（不含凭据），8821 之类的风控提示会直接显示在日志里
        return new QrPoll(code, null, null, preview(answer.body()));
    }

    /** 独立运行（测试/无插件）时 LOGGER 可能为 null */
    private static void warn(String message) {
        if (SimpleVoiceChatMusic.LOGGER != null) {
            SimpleVoiceChatMusic.LOGGER.warning(message);
        }
    }

    private static void info(String message) {
        if (SimpleVoiceChatMusic.LOGGER != null) {
            SimpleVoiceChatMusic.LOGGER.info(message);
        }
    }

    // ------------------------------------------------------------- device id

    private static synchronized String deviceId() {
        if (deviceId != null) {
            return deviceId;
        }

        String stored = readDeviceId();

        if (stored == null || stored.length() != 52) {
            stored = randomDeviceId();
            writeDeviceId(stored);
        }

        deviceId = stored;
        return deviceId;
    }

    private static java.io.File deviceFile() {
        try {
            return new java.io.File(SimpleVoiceChatMusic.get().getDataFolder(), "netease-device.txt");
        } catch (Throwable t) {
            return null; // 没有插件实例（单测/独立运行）时只用内存里的设备 ID
        }
    }

    @Nullable
    private static String readDeviceId() {
        try {
            java.io.File file = deviceFile();
            if (file == null || !file.isFile()) {
                return null;
            }
            return java.nio.file.Files.readString(file.toPath(), StandardCharsets.UTF_8).trim();
        } catch (Exception e) {
            return null;
        }
    }

    private static void writeDeviceId(String value) {
        try {
            java.io.File file = deviceFile();
            if (file == null) {
                return;
            }
            if (file.getParentFile() != null && !file.getParentFile().isDirectory()) {
                file.getParentFile().mkdirs();
            }
            java.nio.file.Files.writeString(file.toPath(), value, StandardCharsets.UTF_8);
        } catch (Exception e) {
            warn("Could not save the NetEase device id: " + e.getMessage());
        }
    }

    private static String randomDeviceId() {
        String hex = "0123456789ABCDEF";
        java.security.SecureRandom random = new java.security.SecureRandom();
        StringBuilder builder = new StringBuilder(52);
        for (int i = 0; i < 52; i++) {
            builder.append(hex.charAt(random.nextInt(hex.length())));
        }
        return builder.toString();
    }

    private static String qrRequestCookie() {
        String device = deviceId();
        StringBuilder builder = new StringBuilder();
        builder.append("os=pc; appver=2.10.6; osver=; deviceId=").append(device)
                .append("; sDeviceId=").append(device)
                .append("; NMTID=").append(device.substring(0, 32));

        if (!qrSessionCookie.isBlank()) {
            builder.append("; ").append(qrSessionCookie);
        }

        return builder.toString();
    }

    /**
     * 轮询二维码登录状态。
     *
     * @return code 800=过期 801=等待扫码 802=已扫码 803=登录成功，其它（如 8821）=风控
     */
    public static QrPoll qrPoll(String key) throws IOException {
        return qrMode == QrMode.WEAPI ? qrPollWeapi(key) : qrPollPlain(key);
    }

    private record HttpAnswer(String body, String headerCookie, int status) {
    }

    /**
     * 明文 /api 请求（新版网页端扫码接口）。
     */
    private static HttpAnswer plainPost(String url, String form) throws IOException {
        OkHttpClient client = client();

        Request request = new Request.Builder()
                .url(url)
                .post(okhttp3.RequestBody.create(form,
                        okhttp3.MediaType.parse("application/x-www-form-urlencoded; charset=UTF-8")))
                .header("Referer", "https://music.163.com")
                .header("User-Agent", PluginSettings.get().httpUserAgent())
                .header("Cookie", qrRequestCookie())
                .build();

        try (Response response = client.newCall(request).execute()) {
            ResponseBody body = response.body();
            String text = body == null ? "" : body.string();

            if (response.code() >= 500) {
                throw new IOException("NetEase HTTP " + response.code());
            }

            return new HttpAnswer(text, joinSetCookies(response.headers("Set-Cookie")), response.code());
        }
    }

    private static JsonObject parseJsonOrThrow(HttpAnswer answer, String what) throws IOException {
        try {
            return JsonParser.parseString(answer.body()).getAsJsonObject();
        } catch (Exception e) {
            throw new IOException("NetEase " + what + " returned invalid JSON (HTTP "
                    + answer.status() + "): " + preview(answer.body()), e);
        }
    }

    private static String urlEncode(String value) {
        try {
            return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return value;
        }
    }

    /**
     * 发一个 weapi 请求，并带上/更新扫码会话 Cookie。
     */
    private static HttpAnswer weapiSessionPost(String url, String json) throws IOException {
        NetEaseCrypto.WeapiResult encrypted = NetEaseCrypto.weapi(json);
        OkHttpClient client = client();

        FormBody form = new FormBody.Builder()
                .add("params", encrypted.params())
                .add("encSecKey", encrypted.encSecKey())
                .build();

        Request request = new Request.Builder()
                .url(url)
                .post(form)
                .header("Referer", "https://music.163.com")
                .header("User-Agent", PluginSettings.get().httpUserAgent())
                .header("Cookie", qrRequestCookie())
                .build();

        try (Response response = client.newCall(request).execute()) {
            ResponseBody body = response.body();
            String text = body == null ? "" : body.string();
            return new HttpAnswer(text, joinSetCookies(response.headers("Set-Cookie")), response.code());
        }
    }

    private static synchronized void mergeQrSession(String headerCookie) {
        if (headerCookie == null || headerCookie.isBlank()) {
            return;
        }

        StringBuilder merged = new StringBuilder(qrSessionCookie);
        appendCookies(merged, headerCookie);
        qrSessionCookie = merged.toString();
    }

    private static String joinSetCookies(List<String> headers) {
        StringBuilder builder = new StringBuilder();

        for (String header : headers) {
            if (header == null) {
                continue;
            }
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

    private static void appendCookies(StringBuilder target, String cookieText) {
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
                continue; // 同名 cookie 不重复
            }

            if (target.length() > 0) {
                target.append("; ");
            }
            target.append(trimmed);
        }
    }

    private static String preview(String json) {
        return json.length() > 200 ? json.substring(0, 200) : json;
    }

    /**
     * 用登录态 Cookie 取昵称（登录成功后显示用）。
     */
    @Nullable
    public static String nickname() {
        try {
            String body = weapiPost("https://music.163.com/weapi/w/nuser/account/get?csrf_token=",
                    "{\"csrf_token\":\"\"}");

            JsonObject profile = objectOf(parseObject(body), "profile");
            return profile == null ? null : stringOf(profile, "nickname");
        } catch (Exception e) {
            return null;
        }
    }

    // ----------------------------------------------------------------- search

    public static List<Song> search(String keyword, int limit) throws IOException {
        String json = "{\"s\":\"" + escapeJson(keyword) + "\",\"type\":1,\"limit\":" + Math.max(1, limit)
                + ",\"offset\":0,\"csrf_token\":\"\"}";

        String body = weapiPost("https://music.163.com/weapi/search/get?csrf_token=", json);
        return parseSearch(body);
    }

    /**
     * 单曲播放地址（eapi）。拿不到时用 standard 档再试一次。
     */
    @Nullable
    public static String songUrl(long songId) throws IOException {
        PluginSettings settings = PluginSettings.get();
        String quality = settings.netEaseQuality();

        List<String> levels = new ArrayList<>();
        levels.add(quality);
        if (!"standard".equalsIgnoreCase(quality)) {
            levels.add("standard");
        }

        for (String level : levels) {
            String url = trySongUrl(songId, level);
            if (url != null && !url.isBlank()) {
                return url;
            }
        }

        return null;
    }

    @Nullable
    private static String trySongUrl(long songId, String level) throws IOException {
        String dataJson = "{\"ids\":\"[" + songId + "]\",\"level\":\"" + level + "\",\"encodeType\":\"aac\","
                + "\"header\":" + eapiHeaderJson() + "}";

        String params = NetEaseCrypto.eapi("/api/song/enhance/player/url/v1", dataJson);

        OkHttpClient client = client();
        FormBody form = new FormBody.Builder().add("params", params).build();

        Request request = new Request.Builder()
                .url("https://interface.music.163.com/eapi/song/enhance/player/url/v1")
                .post(form)
                .header("Referer", "https://music.163.com")
                .header("User-Agent", PluginSettings.get().httpUserAgent())
                .header("Cookie", eapiCookie())
                .build();

        try (Response response = client.newCall(request).execute()) {
            ResponseBody responseBody = response.body();
            byte[] bytes = responseBody == null ? new byte[0] : responseBody.bytes();

            // eapi 正常返回 AES-ECB 密文；个别节点/风控情况下会直接给明文 JSON，
            // 这里两种都接受，避免"能播但被解密逻辑卡住"。
            String json;
            try {
                json = NetEaseCrypto.decryptEapi(bytes);
            } catch (Exception e) {
                json = new String(bytes, StandardCharsets.UTF_8);
            }

            JsonObject root = parseObject(json);
            JsonArray data = arrayOf(root, "data");
            if (data != null && data.size() > 0 && data.get(0).isJsonObject()) {
                JsonObject first = data.get(0).getAsJsonObject();
                String url = stringOf(first, "url");
                if (url != null && !url.isBlank()) {
                    return url;
                }
            }

            return null;
        }
    }


    /**
     * 单曲详情（标题 / 歌手 / 时长），用于直链播放时显示歌名。
     */
    @Nullable
    public static Song songDetail(long songId) {
        try {
            String c = "[{\"id\":" + songId + "}]";
            String json = "{\"c\":\"" + c.replace("\"", "\\\"") + "\",\"ids\":\"[" + songId
                    + "]\",\"csrf_token\":\"\"}";

            String body = weapiPost("https://music.163.com/weapi/v3/song/detail?csrf_token=", json);
            JsonObject root = parseObject(body);
            JsonArray songs = arrayOf(root, "songs");
            if (songs == null || songs.isEmpty() || !songs.get(0).isJsonObject()) {
                return null;
            }

            JsonObject song = songs.get(0).getAsJsonObject();
            long fee = longOf(song, "fee");
            String artist = joinArtists(arrayOf(song, "ar"));
            if (artist.isEmpty()) {
                artist = joinArtists(arrayOf(song, "artists"));
            }

            long duration = longOf(song, "dt");
            if (duration <= 0) {
                duration = longOf(song, "duration");
            }

            return new Song(songId, stringOf(song, "name"), artist, duration, fee == 1 || fee == 4);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 跟随短链跳转（163cn.tv），返回最终地址。
     */
    @Nullable
    public static String followRedirect(String url) {
        try (Response response = client().newCall(new Request.Builder()
                .url(url)
                .get()
                .header("User-Agent", PluginSettings.get().httpUserAgent())
                .build()).execute()) {
            return response.request().url().toString();
        } catch (Exception e) {
            return null;
        }
    }

    @Nullable
    public static Long playlistIdFromUrl(String url) {
        if (url == null || !url.contains("playlist")) {
            return null;
        }
        return songIdFromUrl(url);
    }

    // --------------------------------------------------------------- playlist

    /**
     * 歌单详情（weapi）。返回全部歌曲（接口返回上限 1000 首，够用）。
     */
    public static List<Song> playlistDetail(long playlistId) throws IOException {
        String json = "{\"id\":\"" + playlistId + "\",\"n\":100000,\"s\":8,\"csrf_token\":\"\"}";
        String body = weapiPost("https://music.163.com/weapi/v6/playlist/detail?csrf_token=", json);
        return parsePlaylist(body);
    }

    // ------------------------------------------------------------------ lyric

    /**
     * 取歌词原文（LRC 文本），没有歌词返回 null。
     */
    @Nullable
    public static String rawLyric(long songId) throws IOException {
        OkHttpClient client = client();

        FormBody form = new FormBody.Builder()
                .add("id", Long.toString(songId))
                .add("tv", "-1")
                .add("lv", "-1")
                .add("rv", "-1")
                .add("kv", "-1")
                .build();

        Request request = new Request.Builder()
                .url("https://music.163.com/api/song/lyric?_nmclfl=1")
                .post(form)
                .header("Referer", "https://music.163.com")
                .header("User-Agent", PluginSettings.get().httpUserAgent())
                .header("Cookie", cookie)
                .build();

        try (Response response = client.newCall(request).execute()) {
            ResponseBody body = response.body();
            String json = body == null ? "" : body.string();

            JsonObject root;
            try {
                root = JsonParser.parseString(json).getAsJsonObject();
            } catch (Exception e) {
                return null;
            }

            JsonObject lrc = objectOf(root, "lrc");
            if (lrc == null) {
                return null;
            }

            String lyric = stringOf(lrc, "lyric");
            if (lyric == null || lyric.isBlank()) {
                return null;
            }

            return lyric;
        }
    }

    /**
     * 异步取歌词并解析，完成后回调（空列表 = 该歌曲没有歌词）。
     */
    public static void requestLyricAsync(long songId, Consumer<List<LyricLine>> callback) {
        if (LYRICS.containsKey(songId)) {
            callback.accept(LYRICS.get(songId));
            return;
        }

        if (!FETCHING.add(songId)) {
            return; // 已经有人在取
        }

        Runnable work = () -> {
            try {
                String raw = rawLyric(songId);
                List<LyricLine> lines = raw == null ? Collections.emptyList() : parseLyric(raw);
                cacheLyrics(songId, lines);
                callback.accept(lines);
            } catch (Exception e) {
                cacheLyrics(songId, Collections.emptyList());
                callback.accept(Collections.emptyList());
            } finally {
                FETCHING.remove(songId);
            }
        };

        dev.klash.simpleVoiceChatMusic.SimpleVoiceChatMusic plugin =
                dev.klash.simpleVoiceChatMusic.SimpleVoiceChatMusic.getOrNull();

        if (plugin == null || !plugin.isEnabled()) {
            work.run();
        } else {
            org.bukkit.Bukkit.getScheduler().runTaskAsynchronously(plugin, work);
        }
    }

    // --------------------------------------------------------------- parsing

    public static List<Song> parseSearch(@Nullable String json) throws IOException {
        List<Song> result = new ArrayList<>();
        if (json == null || json.isBlank()) {
            return result;
        }

        JsonObject root = parseObject(json);
        JsonObject resultObj = objectOf(root, "result");
        if (resultObj == null) {
            return result;
        }

        JsonArray songs = arrayOf(resultObj, "songs");
        if (songs == null) {
            return result;
        }

        for (JsonElement element : songs) {
            if (!element.isJsonObject()) {
                continue;
            }

            JsonObject song = element.getAsJsonObject();
            long id = longOf(song, "id");
            if (id <= 0) {
                continue;
            }

            String title = stringOf(song, "name");

            String artist = joinArtists(arrayOf(song, "ar"));
            if (artist.isEmpty()) {
                artist = joinArtists(arrayOf(song, "artists"));
            }

            long duration = longOf(song, "dt");
            if (duration <= 0) {
                duration = longOf(song, "duration");
            }

            long fee = longOf(song, "fee");
            boolean vip = fee == 1 || fee == 4;

            result.add(new Song(id, title, artist, duration, vip));
        }

        return result;
    }

    public static List<Song> parsePlaylist(@Nullable String json) throws IOException {
        List<Song> result = new ArrayList<>();
        if (json == null || json.isBlank()) {
            return result;
        }

        JsonObject root = parseObject(json);
        JsonObject playlist = objectOf(root, "playlist");
        if (playlist == null) {
            return result;
        }

        JsonArray tracks = arrayOf(playlist, "tracks");
        if (tracks == null) {
            return result;
        }

        for (JsonElement element : tracks) {
            if (!element.isJsonObject()) {
                continue;
            }

            JsonObject song = element.getAsJsonObject();
            long id = longOf(song, "id");
            if (id <= 0) {
                continue;
            }

            long fee = longOf(song, "fee");

            String artist = joinArtists(arrayOf(song, "ar"));
            if (artist.isEmpty()) {
                artist = joinArtists(arrayOf(song, "artists"));
            }

            long duration = longOf(song, "dt");
            if (duration <= 0) {
                duration = longOf(song, "duration");
            }

            result.add(new Song(id, stringOf(song, "name"), artist, duration, fee == 1 || fee == 4));
        }

        return result;
    }

    /**
     * 解析 LRC：支持 [mm:ss.xx] 与 [mm:ss]，同一行多个时间戳会展开。
     */
    public static List<LyricLine> parseLyric(String lrc) {
        List<LyricLine> lines = new ArrayList<>();
        if (lrc == null || lrc.isBlank()) {
            return lines;
        }

        Pattern timePattern = Pattern.compile("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]");

        for (String raw : lrc.split("\\r?\\n")) {
            Matcher matcher = timePattern.matcher(raw);
            List<Long> times = new ArrayList<>();

            while (matcher.find()) {
                long minutes = Long.parseLong(matcher.group(1));
                long seconds = Long.parseLong(matcher.group(2));
                long fraction = 0;
                String fractionText = matcher.group(3);
                if (fractionText != null && !fractionText.isEmpty()) {
                    fraction = Long.parseLong(fractionText);
                    if (fractionText.length() == 1) {
                        fraction *= 100;
                    } else if (fractionText.length() == 2) {
                        fraction *= 10;
                    }
                }
                times.add((minutes * 60 + seconds) * 1000 + fraction);
            }

            if (times.isEmpty()) {
                continue;
            }

            String text = timePattern.matcher(raw).replaceAll("").trim();
            if (text.isEmpty()) {
                continue;
            }

            for (long time : times) {
                lines.add(new LyricLine(time, text));
            }
        }

        lines.sort((a, b) -> Long.compare(a.timeMs(), b.timeMs()));
        return lines;
    }

    // ------------------------------------------------------------ http helpers

    private static String weapiPost(String url, String json) throws IOException {
        NetEaseCrypto.WeapiResult encrypted = NetEaseCrypto.weapi(json);

        OkHttpClient client = client();

        FormBody form = new FormBody.Builder()
                .add("params", encrypted.params())
                .add("encSecKey", encrypted.encSecKey())
                .build();

        Request request = new Request.Builder()
                .url(url)
                .post(form)
                .header("Referer", "https://music.163.com")
                .header("User-Agent", PluginSettings.get().httpUserAgent())
                .header("Cookie", cookie)
                .build();

        try (Response response = client.newCall(request).execute()) {
            ResponseBody body = response.body();
            return body == null ? "" : body.string();
        }
    }

    private static OkHttpClient client() {
        PluginSettings settings = PluginSettings.get();
        return new OkHttpClient.Builder()
                .connectTimeout(settings.httpConnectTimeoutMs(), TimeUnit.MILLISECONDS)
                .readTimeout(settings.httpReadTimeoutMs(), TimeUnit.MILLISECONDS)
                .followRedirects(true)
                .build();
    }

    /**
     * eapi 请求头字段（与 NeteaseCloudMusicApi 一致），MUSIC_U 带上登录态。
     */
    private static String eapiHeaderJson() {
        String musicU = extractCookieValue(cookie, "MUSIC_U");
        long now = System.currentTimeMillis();

        StringBuilder builder = new StringBuilder("{");
        builder.append("\"osver\":\"\",\"deviceId\":\"\",\"appver\":\"8.10.05\",\"versioncode\":\"140\",")
                .append("\"mobilename\":\"\",\"buildver\":\"").append(now / 1000).append("\",")
                .append("\"resolution\":\"1920x1080\",\"__csrf\":\"\",\"os\":\"android\",\"channel\":\"\",")
                .append("\"requestId\":\"").append(now).append("_0000\"");

        if (!musicU.isEmpty()) {
            builder.append(",\"MUSIC_U\":\"").append(escapeJson(musicU)).append("\"");
        }

        builder.append("}");

        return builder.toString();
    }

    private static String eapiCookie() {
        StringBuilder builder = new StringBuilder();
        builder.append("osver=; deviceId=; appver=8.10.05; versioncode=140; mobilename=; ")
                .append("buildver=").append(System.currentTimeMillis() / 1000).append("; ")
                .append("resolution=1920x1080; __csrf=; os=android; channel=; ");

        String musicU = extractCookieValue(cookie, "MUSIC_U");
        if (!musicU.isEmpty()) {
            builder.append("MUSIC_U=").append(musicU).append("; ");
        }

        return builder.toString();
    }

    private static String extractCookieValue(String cookieText, String name) {
        if (cookieText == null || cookieText.isEmpty()) {
            return "";
        }

        for (String part : cookieText.split(";")) {
            String trimmed = part.trim();
            if (trimmed.startsWith(name + "=")) {
                return trimmed.substring(name.length() + 1);
            }
        }

        return "";
    }

    private static String joinArtists(@Nullable JsonArray array) {
        if (array == null || array.isEmpty()) {
            return "";
        }

        StringBuilder builder = new StringBuilder();
        for (JsonElement element : array) {
            if (!element.isJsonObject()) {
                continue;
            }
            String name = stringOf(element.getAsJsonObject(), "name");
            if (name.isEmpty()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append("/");
            }
            builder.append(name);
        }

        return builder.toString();
    }

    private static String escapeJson(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }

    private static JsonObject parseObject(String json) throws IOException {
        try {
            return JsonParser.parseString(json).getAsJsonObject();
        } catch (Exception e) {
            throw new IOException("NetEase returned invalid JSON", e);
        }
    }

    @Nullable
    private static JsonObject objectOf(@Nullable JsonObject parent, String key) {
        if (parent == null || !parent.has(key) || !parent.get(key).isJsonObject()) {
            return null;
        }
        return parent.getAsJsonObject(key);
    }

    @Nullable
    private static JsonArray arrayOf(@Nullable JsonObject parent, String key) {
        if (parent == null || !parent.has(key) || !parent.get(key).isJsonArray()) {
            return null;
        }
        return parent.getAsJsonArray(key);
    }

    private static String stringOf(JsonObject object, String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
            return "";
        }
        try {
            return object.get(key).getAsString();
        } catch (Exception e) {
            return "";
        }
    }

    private static int intOf(JsonObject object, String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
            return 0;
        }
        try {
            return object.get(key).getAsInt();
        } catch (Exception e) {
            return 0;
        }
    }

    private static long longOf(JsonObject object, String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
            return 0L;
        }
        try {
            return object.get(key).getAsLong();
        } catch (Exception e) {
            return 0L;
        }
    }
}
