package dev.klash.simpleVoiceChatMusic.commands;

import dev.klash.simpleVoiceChatMusic.config.PluginSettings;
import dev.klash.simpleVoiceChatMusic.i18n.Messages;
import dev.klash.simpleVoiceChatMusic.ui.MusicDialogGui;
import org.bukkit.entity.Player;

/**
 * 打开基于 1.21.7+ Dialog API 的图形菜单。
 */
public class MenuCommand implements Command {

    @Override
    public int execute(Player player, String[] args) {
        if (!PluginSettings.get().menuEnabled()) {
            Messages.send(player, "menu.disabled");
            return 1;
        }

        try {
            MusicDialogGui.open(player);
        } catch (LinkageError | UnsupportedOperationException e) {
            // 服务器版本低于 1.21.7（没有 Dialog API），给出明确提示而不是堆栈
            Messages.send(player, "menu.unsupported");
            return 1;
        }

        return 0;
    }
}
