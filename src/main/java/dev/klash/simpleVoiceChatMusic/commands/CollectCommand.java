package dev.klash.simpleVoiceChatMusic.commands;

import dev.klash.simpleVoiceChatMusic.audio.GroupManager;
import dev.klash.simpleVoiceChatMusic.audio.MusicManager;
import dev.klash.simpleVoiceChatMusic.audio.PlayerCatalogue;
import dev.klash.simpleVoiceChatMusic.audio.TrackMetadata;
import dev.klash.simpleVoiceChatMusic.i18n.Messages;
import dev.klash.simpleVoiceChatMusic.i18n.Ph;
import dev.klash.simpleVoiceChatMusic.util.ModUtils;
import org.bukkit.entity.Player;

/**
 * 收藏歌曲到「我的喜欢」。
 *
 * <pre>
 * /music collect &lt;标识&gt; [标题]   收藏指定歌曲（搜索结果的「收藏歌曲」按钮用的就是它）
 * /music like                    收藏当前正在播放的歌曲
 * </pre>
 */
public class CollectCommand implements Command {

    @Override
    public int execute(Player player, String[] args) {
        if (args.length == 0) {
            return collectCurrent(player);
        }

        String identifier = args[0].trim();

        if (identifier.isEmpty()) {
            Messages.send(player, "collect.usage");
            return 1;
        }

        String title = args.length > 1
                ? String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length)).trim()
                : "";

        if (title.isEmpty()) {
            title = identifier;
        }

        PlayerCatalogue.AddResult result = PlayerCatalogue.add(player.getUniqueId(),
                PlayerCatalogue.FAVORITES,
                new PlayerCatalogue.Entry(identifier, title, "", System.currentTimeMillis()));

        return report(player, result, title);
    }

    private int collectCurrent(Player player) {
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

        String identifier = track.getIdentifier();
        TrackMetadata.Meta meta = TrackMetadata.get(identifier);

        if (meta == null) {
            meta = TrackMetadata.get(track.getInfo().uri);
        }

        String title = meta != null ? meta.title() : track.getInfo().title;
        String author = meta != null ? meta.author() : track.getInfo().author;

        if (title == null || title.isBlank()) {
            title = identifier;
        }

        PlayerCatalogue.AddResult result = PlayerCatalogue.add(player.getUniqueId(),
                PlayerCatalogue.FAVORITES,
                new PlayerCatalogue.Entry(identifier, title, author == null ? "" : author,
                        System.currentTimeMillis()));

        return report(player, result, title);
    }

    private int report(Player player, PlayerCatalogue.AddResult result, String title) {
        return switch (result) {
            case ADDED -> {
                Messages.send(player, "collect.added", Ph.raw("title", title));
                yield 0;
            }
            case DUPLICATE -> {
                Messages.send(player, "collect.duplicate", Ph.raw("title", title));
                yield 0;
            }
            case UNKNOWN_CATALOGUE -> {
                Messages.send(player, "catalogue.unknown");
                yield 1;
            }
        };
    }
}
