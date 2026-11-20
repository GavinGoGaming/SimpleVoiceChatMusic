package dev.klash.simpleVoiceChatMusic.i18n;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

/**
 * 语言文件里的占位符。
 *
 * <p>区分两种插入方式，这点很关键：</p>
 * <ul>
 *   <li>{@link #raw} / {@link #num}：用于<b>不可信</b>的纯文本（玩家名、歌名、错误信息），
 *       不会被当作 MiniMessage 解析，避免注入；</li>
 *   <li>{@link #comp}：用于已经构造好的 {@link Component}（例如带点击事件的曲目信息）。</li>
 * </ul>
 */
public final class Ph {

    private Ph() {
    }

    public static TagResolver raw(String key, String value) {
        return Placeholder.unparsed(key, value == null ? "" : value);
    }

    public static TagResolver num(String key, Number value) {
        return Placeholder.unparsed(key, value == null ? "" : value.toString());
    }

    public static TagResolver comp(String key, Component value) {
        return Placeholder.component(key, value == null ? Component.empty() : value);
    }
}
