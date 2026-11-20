package dev.klash.simpleVoiceChatMusic.audio;

import dev.klash.simpleVoiceChatMusic.config.PluginSettings;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.BufferedSource;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * 直链"体检"：在交给 lavaplayer 之前用一次小请求探一下这个地址到底是什么。
 *
 * <p>只读取 <b>1 KiB</b>（判断服务器有没有真的发数据），不会下载文件，也不需要落盘。</p>
 *
 * <p>结论分两类：</p>
 * <ul>
 *   <li><b>definitive</b>：404 / 403 / 网页 / 5xx —— 这个链接明确不能用，直接拦下并提示；</li>
 *   <li><b>transient</b>：超时 / 连接断开 / 空响应 —— CDN 可能只是临时抽风，
 *       不拦，交给 lavaplayer 去试（我们自己也会重试）。</li>
 * </ul>
 */
public final class HttpProbe {

    /**
     * @param errorKey   语言文件 key；null 表示看起来正常
     * @param definitive true 表示"这个链接确定不可用"，可以安全地拦截并缓存
     */
    public record Result(@Nullable String errorKey, boolean definitive, int status, String contentType) {
    }

    private HttpProbe() {
    }

    /** 只验证 1 KiB：足够判断"服务器到底发不发数据"，又不会真的下载文件 */
    private static final int PROBE_BYTES = 1024;

    public static Result probe(String url, PluginSettings settings) {
        int timeout = Math.max(1000, settings.preflightTimeoutMs());

        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(Math.min(settings.httpConnectTimeoutMs(), timeout), TimeUnit.MILLISECONDS)
                .readTimeout(timeout, TimeUnit.MILLISECONDS)
                .callTimeout(timeout, TimeUnit.MILLISECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .build();

        Request.Builder builder = new Request.Builder()
                .url(url)
                .get()
                // 只取 1 KiB，既不会真的把整首歌拉下来，又能暴露"切片损坏"的 CDN
                .header("Range", "bytes=0-" + (PROBE_BYTES - 1));

        if (!settings.httpUserAgent().isEmpty()) {
            builder.header("User-Agent", settings.httpUserAgent());
        }
        if (!settings.httpReferer().isEmpty()) {
            builder.header("Referer", settings.httpReferer());
        }

        try (Response response = client.newCall(builder.build()).execute()) {
            int status = response.code();
            String contentType = response.header("Content-Type", "");

            String classified = classify(status, contentType);
            if (classified != null) {
                // 这些是"明确不可用"：状态码/内容类型已经说明问题了
                return new Result(classified, true, status, contentType);
            }

            ResponseBody body = response.body();
            if (body != null) {
                BufferedSource source = body.source();
                // 声明了内容却连 1 KiB 都给不出来 → 可能是 CDN 切片没准备好（也可能是彻底坏了）
                if (!source.request(PROBE_BYTES)) {
                    return new Result("play.error-empty", false, status, contentType);
                }
            }

            return new Result(null, false, status, contentType);
        } catch (SocketTimeoutException e) {
            return new Result("play.error-timeout", false, -1, "");
        } catch (UnknownHostException | ConnectException e) {
            return new Result("play.error-unreachable", false, -1, "");
        } catch (IOException e) {
            return new Result("play.error-disconnected", false, -1, "");
        }
    }

    /**
     * 纯函数，便于单测：根据状态码和 Content-Type 判断这个响应像不像音频。
     *
     * @return 语言文件 key；null 表示"看起来没问题"
     */
    @Nullable
    public static String classify(int status, @Nullable String contentType) {
        if (status == 401 || status == 403) {
            return "play.error-forbidden";
        }
        if (status == 404 || status == 410) {
            return "play.error-not-found";
        }
        if (status == 416) {
            return "play.error-empty";
        }
        if (status >= 500) {
            return "play.error-server";
        }

        String type = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        if (type.startsWith("text/html")
                || type.startsWith("application/json")
                || type.startsWith("text/plain")
                || type.startsWith("application/xml")) {
            return "play.error-not-audio";
        }

        return null;
    }
}
