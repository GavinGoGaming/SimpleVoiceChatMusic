package dev.klash.simpleVoiceChatMusic.commands;

import dev.klash.simpleVoiceChatMusic.SimpleVoiceChatMusic;
import dev.klash.simpleVoiceChatMusic.i18n.LanguageManager;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Tab 补全的候选与提示文案。
 *
 * <p>只负责"有哪些候选、对应哪条语言 key"，渲染与事件处理分别由
 * {@link TabCompleteListener}（带 tooltip）和插件的 {@code onTabComplete}（原版补全）完成，
 * 这样两边的候选永远一致，也方便单测。</p>
 */
public final class CommandSuggestions {

    /** 一条补全候选：值 + 提示文案的 language key */
    public record Suggestion(String value, String tooltipKey) {
    }

    /** 命令名（含别名），用于判断是不是本插件的命令 */
    public static final List<String> LABELS = List.of("music", "svcmusic");

    private static final List<String> SUBCOMMANDS = List.of(
            "play", "search", "now-playing", "queue", "skip",
            "pause", "resume", "stop", "volume", "bassboost", "lang", "menu", "login", "source", "loop", "kill"
    );

    private static final List<String> VOLUME_VALUES = List.of("0", "10", "25", "50", "75", "100");
    private static final List<String> BASSBOOST_VALUES = List.of("0", "50", "100", "150", "200");

    private CommandSuggestions() {
    }

    /**
     * @param tokens 命令名之后的参数；最后一个元素是正在输入的片段（可以为空串）
     */
    public static List<Suggestion> suggest(@Nullable org.bukkit.command.CommandSender sender, List<String> tokens) {
        List<String> completed = tokens.size() <= 1
                ? List.of()
                : tokens.subList(0, tokens.size() - 1);
        String partial = tokens.isEmpty() ? "" : tokens.get(tokens.size() - 1);

        if (completed.isEmpty()) {
            List<Suggestion> result = new ArrayList<>();

            for (String subcommand : SUBCOMMANDS) {
                if (startsWithIgnoreCase(subcommand, partial)) {
                    result.add(new Suggestion(subcommand, "tabcomplete." + subcommand));
                }
            }

            return result;
        }

        if (completed.size() == 1) {
            return arguments(completed.get(0).toLowerCase(Locale.ROOT), partial);
        }

        return List.of();
    }

    private static List<Suggestion> arguments(String subcommand, String partial) {
        List<Suggestion> result = new ArrayList<>();

        switch (subcommand) {
            case "play" -> {
                add(result, "https://", "tabcomplete.url", partial);
                add(result, "bilibili://", "tabcomplete.bilibili", partial);
                add(result, "netease:", "tabcomplete.netease", partial);
                add(result, "ytsearch:", "tabcomplete.ytsearch", partial);
                add(result, "scsearch:", "tabcomplete.scsearch", partial);
            }
            case "volume" -> {
                for (String value : VOLUME_VALUES) {
                    add(result, value, "tabcomplete.volume-value", partial);
                }
            }
            case "bassboost" -> {
                for (String value : BASSBOOST_VALUES) {
                    add(result, value, "tabcomplete.bassboost-value", partial);
                }
            }
            case "lang" -> {
                add(result, "auto", "tabcomplete.lang-value", partial);

                SimpleVoiceChatMusic plugin = SimpleVoiceChatMusic.getOrNull();
                LanguageManager languages = plugin == null ? null : plugin.languages();

                if (languages != null) {
                    for (String language : languages.available()) {
                        add(result, language, "tabcomplete.lang-value", partial);
                    }
                }
            }
            case "login" -> {
                add(result, "netease", "tabcomplete.login-netease", partial);
                add(result, "bilibili", "tabcomplete.login-bilibili", partial);
                add(result, "status", "tabcomplete.login-status", partial);
                add(result, "logout", "tabcomplete.login-logout", partial);
                add(result, "cookie", "tabcomplete.login-cookie", partial);
            }
            case "loop" -> {
                add(result, "off", "tabcomplete.loop-off", partial);
                add(result, "one", "tabcomplete.loop-one", partial);
                add(result, "all", "tabcomplete.loop-all", partial);
            }
            case "source" -> {
                add(result, "bilibili", "tabcomplete.source-bilibili", partial);
                add(result, "netease", "tabcomplete.source-netease", partial);
                add(result, "youtube", "tabcomplete.source-youtube", partial);
                add(result, "soundcloud", "tabcomplete.source-soundcloud", partial);
                add(result, "all", "tabcomplete.source-all", partial);
                add(result, "reset", "tabcomplete.source-reset", partial);
            }
            default -> {
                // search / queue / now-playing 等是自由文本或没有候选
            }
        }

        return result;
    }

    private static void add(List<Suggestion> result, String value, String tooltipKey, String partial) {
        if (startsWithIgnoreCase(value, partial)) {
            result.add(new Suggestion(value, tooltipKey));
        }
    }

    private static boolean startsWithIgnoreCase(String value, String prefix) {
        return prefix.isEmpty() || value.toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT));
    }
}
