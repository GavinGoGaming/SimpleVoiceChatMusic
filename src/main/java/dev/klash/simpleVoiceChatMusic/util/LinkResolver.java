package dev.klash.simpleVoiceChatMusic.util;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 链接识别：把玩家粘贴进来的东西分类，避免"看起来像链接、其实是网页/别的平台"的情况
 * 一路走到 lavaplayer 才报一个看不懂的错。
 */
public final class LinkResolver {

    public enum Type {
        /** 搜索词，或 ytsearch: / scsearch: 这类前缀 */
        SEARCH,
        /** lavaplayer 原生支持的平台（YouTube / SoundCloud / Vimeo / Twitch / Bandcamp） */
        SUPPORTED_URL,
        /** Spotify 单曲链接，走元数据查询 */
        SPOTIFY,
        /** B 站链接 / bilibili:// 标识，走 B 站 API */
        BILIBILI,
        /** 网易云音乐链接 / netease:// 标识，走网易云 API */
        NETEASE,
        /** 未知主机，按直链处理（会做一次探测） */
        DIRECT,
        /** 明确不支持的平台（网易云 / QQ 音乐 …） */
        UNSUPPORTED
    }

    private static final Set<String> SUPPORTED_HOSTS = Set.of(
            "youtube.com", "youtu.be", "music.youtube.com",
            "soundcloud.com", "m.soundcloud.com",
            "vimeo.com", "twitch.tv", "www.twitch.tv", "bandcamp.com"
    );

    private static final Map<String, String> UNSUPPORTED_HOSTS = Map.ofEntries(
            Map.entry("y.qq.com", "QQ 音乐"),
            Map.entry("c.y.qq.com", "QQ 音乐"),
            Map.entry("i.y.qq.com", "QQ 音乐"),
            Map.entry("kuwo.cn", "酷我音乐"),
            Map.entry("migu.cn", "咪咕音乐")
    );

    private LinkResolver() {
    }

    /**
     * 清理玩家输入：去掉首尾的引号/尖括号、还原 {@code &amp;}、把空格和中文编码成合法的 URI。
     */
    public static String normalize(String raw) {
        if (raw == null) {
            return "";
        }

        String value = raw.trim().replace("&amp;", "&");

        while (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            boolean wrapped = (first == '"' && last == '"')
                    || (first == '\'' && last == '\'')
                    || (first == '<' && last == '>')
                    || (first == '《' && last == '》');
            if (!wrapped) {
                break;
            }
            value = value.substring(1, value.length() - 1).trim();
        }

        // App 分享出来的内容常常是「【标题】 https://b23.tv/xxxx」这样的整段文字，先把 URL 抠出来
        if (!startsWithHttp(value)) {
            int index = firstHttpIndex(value);
            if (index > 0) {
                value = cutUrl(value.substring(index));
            }
        }

        if (startsWithHttp(value)) {
            String candidate = value.replace(" ", "%20");
            try {
                return new URI(candidate).toASCIIString();
            } catch (URISyntaxException e) {
                return candidate;
            }
        }

        return value;
    }

    public static boolean startsWithHttp(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    public static Type typeOf(String input) {
        if (input == null || input.isBlank()) {
            return Type.SEARCH;
        }

        String value = input.trim();
        String lower = value.toLowerCase(Locale.ROOT);

        if (lower.startsWith("bilibili://")) {
            return Type.BILIBILI;
        }

        if (lower.startsWith("netease://")) {
            return Type.NETEASE;
        }

        if (lower.startsWith("ytsearch:") || lower.startsWith("ytmsearch:") || lower.startsWith("scsearch:")) {
            return Type.SEARCH;
        }

        if (lower.startsWith("spotify:track:") || lower.contains("open.spotify.com/track/")) {
            return Type.SPOTIFY;
        }

        if (!startsWithHttp(value)) {
            return Type.SEARCH;
        }

        String host = hostOf(value);
        if (host == null) {
            return Type.SEARCH;
        }

        if (matchesHost(host, "spotify.com")) {
            return Type.SPOTIFY;
        }

        if (isBilibiliHost(host)) {
            return Type.BILIBILI;
        }

        if (matchesHost(host, "music.163.com") || matchesHost(host, "163cn.tv")) {
            return Type.NETEASE;
        }

        for (String supported : SUPPORTED_HOSTS) {
            if (matchesHost(host, supported)) {
                return Type.SUPPORTED_URL;
            }
        }

        for (String unsupported : UNSUPPORTED_HOSTS.keySet()) {
            if (matchesHost(host, unsupported)) {
                return Type.UNSUPPORTED;
            }
        }

        return Type.DIRECT;
    }

    /**
     * @return 主机名（含子域名匹配），解析失败返回 null
     */
    public static String hostOf(String url) {
        try {
            String host = new URI(url.trim()).getHost();
            return host == null ? null : host.toLowerCase(Locale.ROOT);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 不支持的平台的友好名字（例如"网易云音乐"），识别不出时返回主机名。
     */
    public static String platformOf(String url) {
        String host = hostOf(url);
        if (host == null) {
            return url;
        }

        for (Map.Entry<String, String> entry : UNSUPPORTED_HOSTS.entrySet()) {
            if (matchesHost(host, entry.getKey())) {
                return entry.getValue();
            }
        }

        return host;
    }

    private static boolean isBilibiliHost(String host) {
        return matchesHost(host, "bilibili.com")
                || matchesHost(host, "b23.tv")
                || matchesHost(host, "bili2233.cn")
                || matchesHost(host, "bili22.cn")
                || matchesHost(host, "bili23.cn")
                || matchesHost(host, "bili33.cn");
    }

    private static int firstHttpIndex(String value) {
        int https = value.indexOf("https://");
        int http = value.indexOf("http://");

        if (https < 0) {
            return http;
        }
        if (http < 0) {
            return https;
        }
        return Math.min(https, http);
    }

    /** 截断到第一个空白/中文标点，避免把「】」「。」一起带进 URL */
    private static String cutUrl(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isWhitespace(c) || "】)）>》，。；;\"'".indexOf(c) >= 0) {
                return value.substring(0, i);
            }
        }
        return value;
    }
    private static boolean matchesHost(String host, String candidate) {
        return host.equals(candidate) || host.endsWith("." + candidate);
    }
}
