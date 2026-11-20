package dev.klash.simpleVoiceChatMusic.commands;

import dev.klash.simpleVoiceChatMusic.audio.GroupManager;
import dev.klash.simpleVoiceChatMusic.audio.MusicManager;
import dev.klash.simpleVoiceChatMusic.i18n.Messages;
import dev.klash.simpleVoiceChatMusic.i18n.Ph;
import dev.klash.simpleVoiceChatMusic.util.ModUtils;
import org.bukkit.entity.Player;

import static dev.klash.simpleVoiceChatMusic.util.ModUtils.checkPlayerGroup;

public class VolumeCommand implements Command {

    private static final int MIN_VOLUME = 0;
    private static final int MAX_VOLUME = 100;

    @Override
    public int execute(Player player, String[] args) {
        if (args.length == 0) {
            Messages.send(player, "volume.usage");
            return 1;
        }

        int volume;
        try {
            volume = Integer.parseInt(args[0]);
        } catch (NumberFormatException e) {
            Messages.send(player, "volume.invalid");
            return 1;
        }

        if (volume < MIN_VOLUME || volume > MAX_VOLUME) {
            Messages.send(player, "volume.range");
            return 1;
        }

        ModUtils.CheckPlayerGroup result = checkPlayerGroup(player);
        if (result == null) {
            return 1;
        }

        GroupManager gm = MusicManager.getInstance().getGroup(result.group(), result.source().getServer());
        gm.broadcast("volume.set",
                Ph.raw("player", player.getName()),
                Ph.num("volume", volume));
        gm.setVolume(volume);

        return 0;
    }
}
