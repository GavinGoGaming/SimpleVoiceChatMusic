package dev.klash.simpleVoiceChatMusic.commands;

import dev.klash.simpleVoiceChatMusic.audio.GroupManager;
import dev.klash.simpleVoiceChatMusic.audio.MusicManager;
import dev.klash.simpleVoiceChatMusic.i18n.Ph;
import dev.klash.simpleVoiceChatMusic.util.ModUtils;
import org.bukkit.entity.Player;

import static dev.klash.simpleVoiceChatMusic.util.ModUtils.checkPlayerGroup;

public class ResumeCommand implements Command {

    @Override
    public int execute(Player context, String[] args) {
        ModUtils.CheckPlayerGroup result = checkPlayerGroup(context);
        if (result == null) {
            return 1;
        }

        GroupManager gm = MusicManager.getInstance().getGroup(result.group(), result.source().getServer());
        gm.broadcast("resume.done", Ph.raw("player", result.source().getName()));
        gm.getPlayer().setPaused(false);

        return 0;
    }
}
