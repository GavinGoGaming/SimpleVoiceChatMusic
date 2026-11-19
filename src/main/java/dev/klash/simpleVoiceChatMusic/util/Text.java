package dev.klash.simpleVoiceChatMusic.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * 文本工具类。
 *
 * <p><b>注意：</b>{@link #literal(String)} 故意<b>不</b>使用 MiniMessage。进入这里的字符串
 * （歌曲标题、作者、玩家名、错误信息）全部来自不可信来源，必须按纯文本渲染。
 * 一旦交给 MiniMessage 解析，一个恶意视频标题（例如
 * {@code <click:run_command:'/op someone'>点我</click>}）就能在别人的聊天框里
 * 注入可点击组件并诱导执行命令。</p>
 */
public final class Text {

    private Text() {
    }

    public static Component literal(String text) {
        return Component.text(text == null ? "" : text);
    }

    public static Component empty() {
        return Component.empty();
    }

    /**
     * 截断过长的文本，避免超长标题刷屏。
     *
     * @param text      原始文本
     * @param maxLength 最大长度（<= 0 表示不限制）
     * @return 截断后的文本
     */
    public static String truncate(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        if (maxLength <= 0 || text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength - 1) + "…";
    }

    /**
     * 解析配置里的颜色：支持 {@code green} 这类色名、{@code #22c55e} 十六进制，
     * 也容忍写成 {@code <green>}。无法识别或留空时返回 {@code null}（表示"不覆盖"）。
     */
    @Nullable
    public static TextColor parseColor(@Nullable String raw) {
        if (raw == null) {
            return null;
        }

        String value = raw.trim();

        if (value.startsWith("<") && value.endsWith(">")) {
            value = value.substring(1, value.length() - 1).trim();
        }

        if (value.isEmpty()) {
            return null;
        }

        if (value.startsWith("#")) {
            value = value.substring(1);
        }

        if (value.matches("[0-9a-fA-F]{6}")) {
            try {
                return TextColor.color(Integer.parseInt(value, 16));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }

        return NamedTextColor.NAMES.value(value.toLowerCase(Locale.ROOT));
    }
}
