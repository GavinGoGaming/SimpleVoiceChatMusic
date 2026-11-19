package dev.klash.simpleVoiceChatMusic.util;

import org.apache.http.ConnectionClosedException;
import org.apache.http.HttpException;
import org.jetbrains.annotations.Nullable;

import javax.net.ssl.SSLException;
import java.io.EOFException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

/**
 * 把 LavaPlayer / Apache HttpClient 抛出来的异常翻译成"玩家看得懂"的语言 key。
 */
public final class Errors {

    private Errors() {
    }

    /**
     * @return 语言文件里的 key；不是网络类错误时返回 null
     */
    @Nullable
    public static String playerMessageKey(@Nullable Throwable throwable) {
        Throwable current = throwable;

        while (current != null) {
            if (current instanceof SocketTimeoutException) {
                return "play.error-timeout";
            }
            if (current instanceof UnknownHostException || current instanceof ConnectException) {
                return "play.error-unreachable";
            }
            if (current instanceof SSLException) {
                return "play.error-tls";
            }
            if (current instanceof ConnectionClosedException || current instanceof EOFException) {
                return "play.error-disconnected";
            }
            if (current instanceof HttpException) {
                return "play.error-server";
            }

            current = current.getCause();
        }

        String message = throwable == null ? "" : String.valueOf(throwable.getMessage());
        String lower = message.toLowerCase(java.util.Locale.ROOT);

        if (lower.contains("timed out") || lower.contains("timeout")) {
            return "play.error-timeout";
        }
        if (lower.contains("premature end") || lower.contains("connection reset") || lower.contains("closed")) {
            return "play.error-disconnected";
        }
        if (lower.contains("unknown host") || lower.contains("connection refused")) {
            return "play.error-unreachable";
        }
        if (lower.contains("no audio track") || lower.contains("unknown file format")
                || lower.contains("not supported") || lower.contains("unsupported")) {
            return "play.error-not-audio";
        }

        return null;
    }
}
