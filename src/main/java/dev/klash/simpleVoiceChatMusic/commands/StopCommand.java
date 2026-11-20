package dev.klash.simpleVoiceChatMusic.commands;

import dev.klash.simpleVoiceChatMusic.audio.GroupManager;
import dev.klash.simpleVoiceChatMusic.audio.MusicManager;
import dev.klash.simpleVoiceChatMusic.i18n.Ph;
import dev.klash.simpleVoiceChatMusic.util.ModUtils;
import org.bukkit.entity.Player;

import static dev.klash.simpleVoiceChatMusic.util.ModUtils.checkPlayerGroup;

public class StopCommand implements Command {

    @Override
    public int execute(Player context, String[] args) {
        ModUtils.CheckPlayerGroup result = checkPlayerGroup(context);
        if (result == null) {
            return 1;
        }

        GroupManager gm = MusicManager.getInstance().getGroup(result.group(), result.source().getServer());
        gm.broadcast("stop.done", Ph.raw("player", result.source().getName()));

        // 文档写的是"停止并清空队列"，这里必须真的清空，否则下一次 play 会继续放旧队列
        gm.getQueue().clear();
        gm.getPlayer().stopTrack();

        return 0;
    }
}
