package dev.klash.simpleVoiceChatMusic.commands;

import dev.klash.simpleVoiceChatMusic.audio.GroupManager;
import dev.klash.simpleVoiceChatMusic.audio.GroupSettingsManager;
import dev.klash.simpleVoiceChatMusic.audio.MusicManager;
import dev.klash.simpleVoiceChatMusic.i18n.Messages;
import dev.klash.simpleVoiceChatMusic.i18n.Ph;
import dev.klash.simpleVoiceChatMusic.util.ModUtils;
import org.bukkit.entity.Player;

/**
 * 循环播放：关闭 / 单曲循环 / 列表循环。
 *
 * <pre>
 * /music loop            查看当前模式
 * /music loop off        关闭
 * /music loop one        单曲循环
 * /music loop all        列表循环
 * </pre>
 */
public class LoopCommand implements Command {

    @Override
    public int execute(Player player, String[] args) {
        ModUtils.CheckPlayerGroup result = ModUtils.checkPlayerGroup(player);
        if (result == null) {
            return 1;
        }

        GroupManager gm = MusicManager.getInstance().getGroup(result.group(), result.source().getServer());

        if (args.length == 0) {
            Messages.send(player, "loop.current",
                    Ph.raw("mode", Messages.plain(player, gm.loopMode().langKey())));
            return 0;
        }

        GroupSettingsManager.LoopMode mode = GroupSettingsManager.LoopMode.fromInput(args[0]);

        if (mode == null) {
            Messages.send(player, "loop.usage");
            return 1;
        }

        gm.setLoopMode(mode);
        gm.broadcast("loop.set",
                Ph.raw("player", player.getName()),
                Ph.raw("mode", Messages.plain(player, mode.langKey())));

        return 0;
    }
}
