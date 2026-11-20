package dev.klash.simpleVoiceChatMusic.commands;

import dev.klash.simpleVoiceChatMusic.config.PlayerPreferences;
import dev.klash.simpleVoiceChatMusic.i18n.Messages;
import dev.klash.simpleVoiceChatMusic.i18n.Ph;
import org.bukkit.entity.Player;

import java.util.Locale;

/**
 * 查看/切换自己的默认搜索播放音源。
 *
 * <pre>
 * /music source              查看当前音源
 * /music source netease      设为网易云
 * </pre>
 */
public class SourceCommand implements Command {

    @Override
    public int execute(Player player, String[] args) {
        if (args.length == 0) {
            Messages.send(player, "source.current",
                    Ph.raw("source", Messages.plain(player, PlayerPreferences.sourceFor(player).langKey())));
            Messages.send(player, "source.available");
            return 0;
        }

        String input = String.join(" ", args).toLowerCase(Locale.ROOT);

        if (input.equals("reset") || input.equals("auto") || input.equals("默认")) {
            PlayerPreferences.clearSource(player);
            Messages.send(player, "source.reset",
                    Ph.raw("source", Messages.plain(player, PlayerPreferences.sourceFor(player).langKey())));
            return 0;
        }

        PlayerPreferences.Source source = PlayerPreferences.Source.fromInput(input);

        if (source == null) {
            Messages.send(player, "source.unknown", Ph.raw("input", input));
            return 1;
        }

        PlayerPreferences.setSource(player, source);
        Messages.send(player, "source.set",
                Ph.raw("source", Messages.plain(player, source.langKey())));
        return 0;
    }
}
