package dev.klash.simpleVoiceChatMusic.i18n;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.Nullable;

/**
 * 所有玩家可见文本的统一出口。
 *
 * <p>文本本身来自语言文件（可信，按 MiniMessage 解析）；插值来自游戏内数据（不可信，
 * 必须通过 {@link Ph#raw} 以纯文本形式注入）。这样既保留了语言文件里的颜色/排版，
 * 又不会让一个恶意视频标题变成可点击命令。</p>
 */
public final class Messages {

    private static LanguageManager languages;
    private static boolean usePrefix = true;

    private Messages() {
    }

    public static void init(LanguageManager manager, boolean prefixesEnabled) {
        languages = manager;
        usePrefix = prefixesEnabled;
    }

    /**
     * 渲染一条消息（不带前缀）。
     */
    public static Component component(@Nullable CommandSender viewer, String key, TagResolver... placeholders) {
        if (languages == null) {
            return Component.text(key);
        }

        return MiniMessage.miniMessage().deserialize(languages.raw(viewer, key), placeholders);
    }

    /**
     * 渲染一条消息，并按配置加上语言文件里的 {@code general.prefix}。
     */
    public static Component render(@Nullable CommandSender viewer, String key, TagResolver... placeholders) {
        Component body = component(viewer, key, placeholders);

        if (!usePrefix || languages == null) {
            return body;
        }

        String prefix = languages.raw(viewer, "general.prefix");
        if (prefix == null || prefix.isEmpty() || prefix.equals("general.prefix")) {
            return body;
        }

        return MiniMessage.miniMessage().deserialize(prefix).append(body);
    }

    public static void send(CommandSender viewer, String key, TagResolver... placeholders) {
        viewer.sendMessage(render(viewer, key, placeholders));
    }

    /**
     * 取纯文本（用于 Simple Voice Chat 的音量分类名等只能是字符串的地方），不加前缀。
     */
    public static String plain(@Nullable CommandSender viewer, String key, TagResolver... placeholders) {
        return PlainTextComponentSerializer.plainText().serialize(component(viewer, key, placeholders));
    }
}
