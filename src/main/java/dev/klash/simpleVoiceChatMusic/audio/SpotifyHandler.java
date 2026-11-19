package dev.klash.simpleVoiceChatMusic.audio;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.Credentials;
import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 极简的 Spotify Web API 客户端。
 *
 * <p>它<b>不</b>播放 Spotify 音频，而是用 Spotify 的元数据（歌名 + 歌手）去 YouTube 搜索同名歌曲。</p>
 *
 * <p>凭据来自 {@code config.yml}（{@code spotify.client-id} / {@code spotify.client-secret}），
 * 不再硬编码在源码里。未配置时 {@link #isConfigured()} 返回 false，调用方应给出明确提示。</p>
 */
public final class SpotifyHandler {

    private static final String AUTH_URL = "https://accounts.spotify.com/api/token";
    private static final String TRACK_ENDPOINT = "https://api.spotify.com/v1/tracks/";

    private static final OkHttpClient HTTP_CLIENT = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .build();

    private static String clientId = "";
    private static String clientSecret = "";

    /** 缓存的 access token 及其过期时间（Spotify 默认 1 小时） */
    private static String accessToken;
    private static long accessTokenExpiresAt;

    private SpotifyHandler() {
    }

    /**
     * 由插件在 onEnable 时调用，注入 config.yml 里的凭据。
     */
    public static void configure(String id, String secret) {
        clientId = id == null ? "" : id.trim();
        clientSecret = secret == null ? "" : secret.trim();

        synchronized (SpotifyHandler.class) {
            accessToken = null;
            accessTokenExpiresAt = 0L;
        }
    }

    public static boolean isConfigured() {
        return !clientId.isEmpty() && !clientSecret.isEmpty();
    }

    /**
     * 从 Spotify 链接/URI 中提取 track id。
     *
     * @return track id，无法识别时返回 null
     */
    public static String extractTrackId(String spotifyUrl) {
        if (spotifyUrl == null) {
            return null;
        }

        String value = spotifyUrl.trim();
        String marker = "spotify.com/track/";
        int markerIndex = value.indexOf(marker);

        String id;
        if (markerIndex >= 0) {
            id = value.substring(markerIndex + marker.length());
        } else if (value.startsWith("spotify:track:")) {
            id = value.substring("spotify:track:".length());
        } else {
            return null;
        }

        int cut = -1;
        for (char c : new char[]{'?', '#', '/'}) {
            int index = id.indexOf(c);
            if (index >= 0 && (cut < 0 || index < cut)) {
                cut = index;
            }
        }
        if (cut >= 0) {
            id = id.substring(0, cut);
        }

        return id.isEmpty() ? null : id;
    }

    /**
     * 获取 access token，带缓存。
     */
    private static synchronized String getAccessToken() throws IOException {
        long now = System.currentTimeMillis();
        if (accessToken != null && now < accessTokenExpiresAt - 30_000L) {
            return accessToken;
        }

        String credentials = Credentials.basic(clientId, clientSecret);
        RequestBody body = new FormBody.Builder()
                .add("grant_type", "client_credentials")
                .build();

        Request request = new Request.Builder()
                .url(AUTH_URL)
                .post(body)
                .header("Authorization", credentials)
                .build();

        try (Response response = HTTP_CLIENT.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Spotify token request failed (HTTP " + response.code()
                        + "). Please check spotify.client-id / spotify.client-secret in config.yml.");
            }

            JsonObject json = JsonParser.parseString(readBody(response)).getAsJsonObject();
            if (!json.has("access_token")) {
                throw new IOException("Spotify token response did not contain an access_token");
            }

            accessToken = json.get("access_token").getAsString();
            long expiresIn = json.has("expires_in") ? json.get("expires_in").getAsLong() : 3600L;
            accessTokenExpiresAt = System.currentTimeMillis() + expiresIn * 1000L;

            return accessToken;
        }
    }

    private static void invalidateToken() {
        synchronized (SpotifyHandler.class) {
            accessToken = null;
            accessTokenExpiresAt = 0L;
        }
    }

    /**
     * 查询单曲信息。
     */
    public static TrackInfo fetchTrackInfo(String trackId) throws IOException {
        if (!isConfigured()) {
            throw new IOException("Spotify integration is not configured");
        }

        String json = requestTrack(trackId, getAccessToken());
        if (json == null) {
            // token 可能已失效，刷新后重试一次
            invalidateToken();
            json = requestTrack(trackId, getAccessToken());
        }

        if (json == null) {
            throw new IOException("Spotify track lookup failed");
        }

        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        return new TrackInfo(parseTitle(root), parseArtists(root));
    }

    /**
     * @return 响应体；401 时返回 null 以便调用方刷新 token 重试
     */
    private static String requestTrack(String trackId, String token) throws IOException {
        Request request = new Request.Builder()
                .url(TRACK_ENDPOINT + trackId)
                .header("Authorization", "Bearer " + token)
                .build();

        try (Response response = HTTP_CLIENT.newCall(request).execute()) {
            if (response.code() == 401) {
                return null;
            }
            if (!response.isSuccessful()) {
                throw new IOException("Spotify track lookup failed (HTTP " + response.code() + ")");
            }
            return readBody(response);
        }
    }

    private static String readBody(Response response) throws IOException {
        return response.body() == null ? "" : response.body().string();
    }

    private static String parseTitle(JsonObject root) {
        if (root.has("name")) {
            return root.get("name").getAsString();
        }
        return "Unknown Title";
    }

    private static String[] parseArtists(JsonObject root) {
        if (root.has("artists")) {
            List<String> names = new ArrayList<>();
            for (var artistElement : root.getAsJsonArray("artists")) {
                JsonObject artistObj = artistElement.getAsJsonObject();
                if (artistObj.has("name")) {
                    names.add(artistObj.get("name").getAsString());
                }
            }
            return names.toArray(new String[0]);
        }
        return new String[]{"Unknown Artist"};
    }

    /**
     * Spotify 单曲信息，用于拼 YouTube 搜索词。
     */
    public static class TrackInfo {
        private final String title;
        private final String[] artists;

        public TrackInfo(String title, String[] artists) {
            this.title = title;
            this.artists = artists;
        }

        /**
         * @return 适合 YouTube 搜索的 "歌名 + 歌手" 查询串
         */
        public String getCombinedQuery() {
            return title + " " + String.join(" ", artists);
        }

        @Override
        public String toString() {
            return "TrackInfo{title='" + title + "', artists=" + String.join(", ", artists) + "}";
        }
    }
}
