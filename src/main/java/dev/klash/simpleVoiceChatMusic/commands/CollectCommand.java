package dev.klash.simpleVoiceChatMusic.commands;

import dev.klash.simpleVoiceChatMusic.audio.GroupManager;
import dev.klash.simpleVoiceChatMusic.audio.MusicManager;
import dev.klash.simpleVoiceChatMusic.audio.PlayerCatalogue;
import dev.klash.simpleVoiceChatMusic.audio.TrackMetadata;
import dev.klash.simpleVoiceChatMusic.i18n.Messages;
import dev.klash.simpleVoiceChatMusic.i18n.Ph;
import dev.klash.simpleVoiceChatMusic.util.ModUtils;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;

/**
 * 收藏歌曲。
 *
 * <pre>
 * /music collect &lt;标识&gt; [标题]   列出所有歌单，点击收藏到此
 * /music like                    同上，用当前正在播放的歌曲
 * </pre>
 */
public class CollectCommand implements Command {

    @Override
    public int execute(Player player, String[] args) {
        String identifier;
        String title;

        if (args.length == 0) {
            ModUtils.CheckPlayerGroup check = ModUtils.checkPlayerGroup(player);
            if (check == null) {
                return 1;
            }

            GroupManager group = MusicManager.getInstance().getGroup(check.group(), check.source().getServer());
            var track = group.getPlayer().getPlayingTrack();

            if (track == null) {
                Messages.send(player, "collect.no-track");
                return 1;
            }

            identifier = track.getIdentifier();

            TrackMetadata.Meta meta = TrackMetadata.get(identifier);

            if (meta == null) {
                meta = TrackMetadata.get(track.getInfo().uri);
            }

            title = meta != null ? meta.title() : track.getInfo().title;
        } else {
            identifier = args[0].trim();
            title = args.length > 1
                    ? String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length)).trim()
                    : identifier;
        }

        if (identifier == null || identifier.isBlank()) {
            Messages.send(player, "collect.usage");
            return 1;
        }

        if (title == null || title.isBlank()) {
            title = identifier;
        }

        Map<String, List<PlayerCatalogue.Entry>> catalogues =
                PlayerCatalogue.catalogues(player.getUniqueId());

        Messages.send(player, "collect.choose", Ph.raw("title", title));

        int index = 1;

        for (Map.Entry<String, List<PlayerCatalogue.Entry>> entry : catalogues.entrySet()) {
            String name = entry.getKey();
            String display = PlayerCatalogue.isFavorites(name)
                    ? Messages.plain(player, "catalogue.favorites")
                    : name;

            Component label = Messages.component(player, "collect.target",
                            Ph.num("index", index++),
                            Ph.raw("name", display),
                            Ph.num("count", entry.getValue().size()))
                    .clickEvent(ClickEvent.runCommand("/music catalogue add " + name
                            + " " + identifier + " " + title))
                    .hoverEvent(HoverEvent.showText(Messages.component(player, "collect.target-hover")));

            player.sendMessage(label);
        }

        Messages.send(player, "collect.choose-hint");

        return 0;
    }
}
