package dev.klash.simpleVoiceChatMusic.util;

import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import de.maxhenkel.voicechat.api.Group;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import dev.klash.simpleVoiceChatMusic.VoiceChatPlugin;
import dev.klash.simpleVoiceChatMusic.config.PluginSettings;
import dev.klash.simpleVoiceChatMusic.i18n.Messages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.Style;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public final class ModUtils {

    /** 只有这些后缀的链接/路径才会被当作"音频文件"，并从中提取文件名作为标题 */
    private static final Set<String> AUDIO_EXTENSIONS = Set.of(
            "mp3", "flac", "wav", "ogg", "oga", "m4a", "aac", "opus", "webm", "wma", "aiff", "aif");

    private ModUtils() {
    }

    /**
     * 曲目信息组件：歌名（可点击打开链接）+ 作者，全部按纯文本渲染。
     * 标题/作者长度取自 config.yml，防止超长标题刷屏。
     */
    public static Component trackInfo(AudioTrackInfo track) {
        return trackInfo(track, false);
    }

    public static Component trackInfo(AudioTrackInfo track, boolean longFormat) {
        PluginSettings settings = PluginSettings.get();

        // 直链音源（B 站 / 网易云）解析时记下的歌名与作者
        dev.klash.simpleVoiceChatMusic.audio.TrackMetadata.Meta meta =
                dev.klash.simpleVoiceChatMusic.audio.TrackMetadata.get(track.uri);

        String rawTitle = meta != null ? meta.title() : track.title;
        String rawAuthor = meta != null ? meta.author() : track.author;

        if (rawTitle == null || rawTitle.isBlank()) {
            String cachedTitle = dev.klash.simpleVoiceChatMusic.audio.BilibiliApi.titleFor(track.uri);
            rawTitle = cachedTitle != null ? cachedTitle : track.uri;
        }

        if (rawTitle == null || rawTitle.isBlank()) {
            rawTitle = "Unknown track";
        }

        Style titleStyle = Style.style(NamedTextColor.AQUA);
        // uri 可能为 null（部分音源不提供），此时不加点击事件
        if (track.uri != null) {
            titleStyle = titleStyle.clickEvent(ClickEvent.openUrl(track.uri));
        }

        Component text = Text.literal(Text.truncate(rawTitle, settings.maxTitleLength()))
                .style(titleStyle);

        // 作者为空时不再输出 " by "
        if (rawAuthor != null && !rawAuthor.isBlank()) {
            text = text.append(Text.literal(" by ").style(Style.empty()))
                    .append(Text.literal(Text.truncate(rawAuthor, settings.maxAuthorLength()))
                            .style(Style.empty().color(NamedTextColor.AQUA)));
        }

        // if long format, add more track data
        if (longFormat) {
            text = text.append(Text.literal(" [" + formatMMSS(track.length) + "]").style(Style.empty()));
        }

        return text;
    }

    public static String formatMMSS(long millis) {
        if (millis < 0) {
            millis = 0;
        }

        String seconds = Long.toString(TimeUnit.MILLISECONDS.toSeconds(millis) -
                TimeUnit.MINUTES.toSeconds(TimeUnit.MILLISECONDS.toMinutes(millis)));

        if (seconds.length() == 1) {
            seconds = "0" + seconds;
        }

        return String.format("%d:%s", TimeUnit.MILLISECONDS.toMinutes(millis), seconds);
    }

    /**
     * BOSS 栏上显示的标题。
     *
     * <p>优先从"文件"里取名字：本地路径、{@code file:} 路径、以及以音频后缀结尾的 http(s) 链接，
     * 都会取最后一段文件名（去掉扩展名）；YouTube/SoundCloud 这类没有文件名概念的源，
     * 退回 {@code track.getInfo().title}。</p>
     */
    public static String displayTitle(AudioTrack track) {
        // 直链音源：优先取解析时记下的歌名
        dev.klash.simpleVoiceChatMusic.audio.TrackMetadata.Meta meta =
                dev.klash.simpleVoiceChatMusic.audio.TrackMetadata.get(track.getIdentifier());

        if (meta == null) {
            meta = dev.klash.simpleVoiceChatMusic.audio.TrackMetadata.get(track.getInfo().uri);
        }

        if (meta != null) {
            return meta.author().isBlank() ? meta.title() : meta.author() + " - " + meta.title();
        }

        String cachedTitle = dev.klash.simpleVoiceChatMusic.audio.BilibiliApi.titleFor(track.getIdentifier());
        if (cachedTitle == null) {
            cachedTitle = dev.klash.simpleVoiceChatMusic.audio.NetEaseApi.titleFor(track.getIdentifier());
        }
        if (cachedTitle != null) {
            return cachedTitle;
        }

        String fromIdentifier = titleFromFile(track.getIdentifier());
        if (fromIdentifier != null) {
            return fromIdentifier;
        }

        String fromUri = titleFromFile(track.getInfo().uri);
        if (fromUri != null) {
            return fromUri;
        }

        return track.getInfo().title;
    }


    /**
     * 从路径/链接里提取文件名（不含扩展名）。
     *
     * @return 文件名；不像音频文件时返回 null
     */
    @Nullable
    public static String titleFromFile(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }

        String path = raw;

        if (path.startsWith("file:")) {
            try {
                path = new File(new URI(path)).getPath();
            } catch (Exception e) {
                path = path.substring("file:".length());
            }
        } else if (path.contains("://")) {
            try {
                path = new URI(path).getPath();
            } catch (Exception e) {
                return null;
            }
        }

        path = stripQuery(path);

        String name = lastPathSegment(path);
        if (name.isBlank()) {
            return null;
        }

        // %20 之类的转义；注意 URLDecoder 会把 '+' 当空格，这里先保护一下
        if (name.indexOf('%') >= 0) {
            try {
                name = URLDecoder.decode(name.replace("+", "%2B"), StandardCharsets.UTF_8);
            } catch (Exception ignored) {
                // 保留原样
            }
        }

        int dot = name.lastIndexOf('.');
        if (dot <= 0) {
            return null;
        }

        String extension = name.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (!AUDIO_EXTENSIONS.contains(extension)) {
            return null;
        }

        return name.substring(0, dot);
    }

    private static String stripQuery(String path) {
        int query = path.indexOf('?');
        if (query >= 0) {
            path = path.substring(0, query);
        }

        int fragment = path.indexOf('#');
        if (fragment >= 0) {
            path = path.substring(0, fragment);
        }

        return path;
    }

    private static String lastPathSegment(String path) {
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    public static String parseTrackId(String userInput) {
        if (userInput == null) {
            return "";
        }

        String input = userInput.trim();

        if (input.startsWith("ytsearch:") || input.startsWith("ytmsearch:") || input.startsWith("scsearch:")) {
            return input;
        }

        // if starts with id:, parse ourselves
        if (input.startsWith("id:")) {
            return input.substring(3);
        }

        // try and parse as URL
        try {
            new URL(input);
        } catch (MalformedURLException e) {
            return "ytsearch:" + input;
        }

        return input;
    }

    public static CheckPlayerGroup checkPlayerGroup(Player source) {
        if (source == null) {
            return null;
        }

        if (VoiceChatPlugin.voicechatServerApi == null) {
            Messages.send(source, "voicechat.not-ready");
            return null;
        }

        VoicechatConnection connection = VoiceChatPlugin.voicechatServerApi.getConnectionOf(source.getUniqueId());

        if (connection == null || !connection.isConnected()) {
            Messages.send(source, "voicechat.not-connected");
            return null;
        }

        Group group = connection.getGroup();

        if (group == null) {
            Messages.send(source, "voicechat.no-group");
            return null;
        }

        return new CheckPlayerGroup(source, group);
    }

    public record CheckPlayerGroup(Player source, Group group) {
    }
}
