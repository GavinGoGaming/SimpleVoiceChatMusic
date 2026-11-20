package dev.klash.simpleVoiceChatMusic.commands;

import dev.klash.simpleVoiceChatMusic.audio.GroupManager;
import dev.klash.simpleVoiceChatMusic.audio.MusicManager;
import dev.klash.simpleVoiceChatMusic.i18n.Messages;
import dev.klash.simpleVoiceChatMusic.i18n.Ph;
import dev.klash.simpleVoiceChatMusic.util.ModUtils;
import org.bukkit.entity.Player;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static dev.klash.simpleVoiceChatMusic.util.ModUtils.checkPlayerGroup;

public class KillCommand implements Command {

    /** 已经被告知过风险的玩家（需要执行两次才真正杀） */
    private final Set<UUID> warned = new HashSet<>();

    @Override
    public int execute(Player player, String[] args) {
        ModUtils.CheckPlayerGroup result = checkPlayerGroup(player);
        if (result == null) {
            return 1;
        }

        if (warned.add(player.getUniqueId())) {
            Messages.send(player, "kill.warning");
            return 0;
        }

        GroupManager gm = MusicManager.getInstance().getGroup(result.group(), result.source().getServer());
        gm.broadcast("kill.broadcast", Ph.raw("player", player.getName()));
        gm.cleanup();

        // 杀完之后重置确认状态，下次再 kill 还要重新确认一次
        warned.remove(player.getUniqueId());

        return 0;
    }
}
