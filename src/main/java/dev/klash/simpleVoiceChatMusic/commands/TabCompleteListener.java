package dev.klash.simpleVoiceChatMusic.commands;

import dev.klash.simpleVoiceChatMusic.i18n.Messages;
import com.destroystokyo.paper.event.server.AsyncTabCompleteEvent;
import org.bukkit.command.CommandSender;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 给 /music 的 Tab 补全加上"功能提示"（鼠标悬停显示每个子命令的作用）。
 *
 * <p>Bukkit 的 {@code TabCompleter} 只能返回字符串，没有 tooltip；Paper 的
 * {@link AsyncTabCompleteEvent.Completion} 支持附带 {@link net.kyori.adventure.text.Component}，
 * 所以这里接管纸面服务端的补全，原版补全则交给插件的 {@code onTabComplete} 兜底。</p>
 */
public class TabCompleteListener implements Listener {

    @EventHandler(priority = EventPriority.HIGH)
    public void onTabComplete(AsyncTabCompleteEvent event) {
        if (!event.isCommand()) {
            return;
        }

        // 已经有别的插件给了带 tooltip 的候选时不要覆盖
        if (event.completions() != null && !event.completions().isEmpty()) {
            return;
        }

        String buffer = event.getBuffer();
        if (buffer == null || !buffer.startsWith("/")) {
            return;
        }

        String body = buffer.substring(1);

        // 还没打出空格时交给原版补全，避免把命令名本身替换成子命令
        if (!body.contains(" ")) {
            return;
        }

        String[] parts = body.split(" ", -1);
        if (parts.length == 0) {
            return;
        }

        String label = parts[0].toLowerCase(Locale.ROOT);
        if (!CommandSuggestions.LABELS.contains(label)) {
            return;
        }

        List<String> tokens = new ArrayList<>();
        for (int i = 1; i < parts.length; i++) {
            tokens.add(parts[i]);
        }
        if (tokens.isEmpty()) {
            tokens.add("");
        }

        CommandSender sender = event.getSender();
        List<AsyncTabCompleteEvent.Completion> completions = new ArrayList<>();

        for (CommandSuggestions.Suggestion suggestion : CommandSuggestions.suggest(sender, tokens)) {
            completions.add(AsyncTabCompleteEvent.Completion.completion(
                    suggestion.value(),
                    Messages.component(sender, suggestion.tooltipKey())));
        }

        event.completions(completions);
        event.setHandled(true);
    }
}
