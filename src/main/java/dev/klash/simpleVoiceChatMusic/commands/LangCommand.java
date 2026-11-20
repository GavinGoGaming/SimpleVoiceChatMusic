package dev.klash.simpleVoiceChatMusic.commands;

import dev.klash.simpleVoiceChatMusic.SimpleVoiceChatMusic;
import dev.klash.simpleVoiceChatMusic.i18n.LanguageManager;
import dev.klash.simpleVoiceChatMusic.i18n.Messages;
import dev.klash.simpleVoiceChatMusic.i18n.Ph;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;

public class LangCommand implements Command {

    @Override
    public int execute(Player player, String[] args) {
        LanguageManager languages = SimpleVoiceChatMusic.get().languages();

        if (args.length == 0) {
            Messages.send(player, "lang.current", Ph.raw("language", languages.resolve(player)));
            Messages.send(player, "lang.available", Ph.raw("languages", String.join(", ", languages.available())));
            return 0;
        }

        String input = args[0].toLowerCase(Locale.ROOT);

        if (input.equals("auto") || input.equals("default") || input.equals("reset")) {
            languages.setOverride(player.getUniqueId(), null);
            Messages.send(player, "lang.reset", Ph.raw("language", languages.resolve(player)));
            return 0;
        }

        String code = LanguageManager.normalize(input);
        List<String> available = languages.available();

        if (!available.contains(code)) {
            Messages.send(player, "lang.unknown",
                    Ph.raw("language", code),
                    Ph.raw("languages", String.join(", ", available)));
            return 1;
        }

        languages.setOverride(player.getUniqueId(), code);
        Messages.send(player, "lang.set", Ph.raw("language", code));
        return 0;
    }
}
