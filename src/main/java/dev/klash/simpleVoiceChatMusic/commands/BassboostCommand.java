package dev.klash.simpleVoiceChatMusic.commands;

import dev.klash.simpleVoiceChatMusic.audio.GroupManager;
import dev.klash.simpleVoiceChatMusic.audio.MusicManager;
import dev.klash.simpleVoiceChatMusic.i18n.Messages;
import dev.klash.simpleVoiceChatMusic.i18n.Ph;
import dev.klash.simpleVoiceChatMusic.util.ModUtils;
import org.bukkit.entity.Player;

import static dev.klash.simpleVoiceChatMusic.util.ModUtils.checkPlayerGroup;

public class BassboostCommand implements Command {

    private static final float MIN_BASS = 0f;
    private static final float MAX_BASS = 200f;

    @Override
    public int execute(Player player, String[] args) {
        if (args.length == 0) {
            Messages.send(player, "bassboost.usage");
            return 1;
        }

        float bass;
        try {
            bass = Float.parseFloat(args[0]);
        } catch (NumberFormatException e) {
            Messages.send(player, "bassboost.invalid");
            return 1;
        }

        if (bass < MIN_BASS || bass > MAX_BASS) {
            Messages.send(player, "bassboost.range");
            return 1;
        }

        ModUtils.CheckPlayerGroup result = checkPlayerGroup(player);
        if (result == null) {
            return 1;
        }

        GroupManager gm = MusicManager.getInstance().getGroup(result.group(), result.source().getServer());
        gm.broadcast("bassboost.set",
                Ph.raw("player", player.getName()),
                Ph.raw("bass", formatBass(bass)));
        gm.setBassBoost(bass);

        return 0;
    }

    private static String formatBass(float bass) {
        if (Math.abs(bass - Math.round(bass)) < 0.001f) {
            return Integer.toString(Math.round(bass));
        }
        return String.format("%.1f", bass);
    }
}
