package dev.klash.simpleVoiceChatMusic.commands;

import dev.klash.simpleVoiceChatMusic.SimpleVoiceChatMusic;
import dev.klash.simpleVoiceChatMusic.audio.CataloguePlayer;
import dev.klash.simpleVoiceChatMusic.audio.GroupManager;
import dev.klash.simpleVoiceChatMusic.audio.MusicManager;
import dev.klash.simpleVoiceChatMusic.audio.PlayerCatalogue;
import dev.klash.simpleVoiceChatMusic.audio.TrackMetadata;
import dev.klash.simpleVoiceChatMusic.i18n.Messages;
import dev.klash.simpleVoiceChatMusic.i18n.Ph;
import dev.klash.simpleVoiceChatMusic.util.ModUtils;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;

/**
 * 玩家收藏目录（歌单）管理。
 *
 * <pre>
 * /music catalogue                    查看所有目录
 * /music catalogue create &lt;名字&gt;       新建目录
 * /music catalogue delete &lt;名字&gt;       删除目录（"我的喜欢"不可删）
 * /music catalogue play &lt;名字&gt;         按顺序播放整个目录
 * /music catalogue add &lt;名字&gt;          把当前歌曲加入目录
 * /music catalogue remove &lt;名字&gt; &lt;序号&gt; 从目录移除
 * /music catalogue list &lt;名字&gt;         查看目录内容
 * </pre>
 */
public class CatalogueCommand implements Command {

    @Override
    public int execute(Player player, String[] args) {
        if (args.length == 0) {
            listCatalogues(player);
            return 0;
        }

        String action = args[0].toLowerCase(java.util.Locale.ROOT);

        return switch (action) {
            case "create", "new", "创建" -> create(player, args);
            case "delete", "remove-dir", "删除" -> delete(player, args);
            case "play", "播放" -> play(player, args);
            case "add", "添加" -> add(player, args);
            case "remove", "移除" -> remove(player, args);
            case "list", "列表", "查看" -> listEntries(player, args);
            default -> {
                Messages.send(player, "catalogue.usage");
                yield 1;
            }
        };
    }

    private void listCatalogues(Player player) {
        Map<String, List<PlayerCatalogue.Entry>> catalogues = PlayerCatalogue.catalogues(player.getUniqueId());

        Messages.send(player, "catalogue.header", Ph.num("count", catalogues.size()));

        for (Map.Entry<String, List<PlayerCatalogue.Entry>> entry : catalogues.entrySet()) {
            String name = displayName(player, entry.getKey());
            Messages.send(player, "catalogue.line",
                    Ph.raw("name", name),
                    Ph.num("count", entry.getValue().size()));
        }
    }

    private int create(Player player, String[] args) {
        if (args.length < 2) {
            Messages.send(player, "catalogue.usage");
            return 1;
        }

        String name = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length));

        if (PlayerCatalogue.create(player.getUniqueId(), name)) {
            Messages.send(player, "catalogue.created", Ph.raw("name", PlayerCatalogue.normalizeName(name)));
            return 0;
        }

        Messages.send(player, "catalogue.create-failed", Ph.raw("name", PlayerCatalogue.normalizeName(name)));
        return 1;
    }

    private int delete(Player player, String[] args) {
        if (args.length < 2) {
            Messages.send(player, "catalogue.usage");
            return 1;
        }

        String input = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length));

        if (isFavoritesInput(player, input)) {
            Messages.send(player, "catalogue.cannot-delete");
            return 1;
        }

        String name = PlayerCatalogue.resolveName(player.getUniqueId(), input);

        if (PlayerCatalogue.delete(player.getUniqueId(), name)) {
            Messages.send(player, "catalogue.deleted", Ph.raw("name", input));
            return 0;
        }

        Messages.send(player, "catalogue.unknown-name", Ph.raw("name", args[1]));
        return 1;
    }

    private int play(Player player, String[] args) {
        if (args.length < 2) {
            Messages.send(player, "catalogue.usage");
            return 1;
        }

        String input = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length));
        String name = PlayerCatalogue.resolveName(player.getUniqueId(), input);

        if (!PlayerCatalogue.exists(player.getUniqueId(), name)) {
            Messages.send(player, "catalogue.unknown-name", Ph.raw("name", args[1]));
            return 1;
        }

        List<PlayerCatalogue.Entry> entries = PlayerCatalogue.entries(player.getUniqueId(), name);

        if (entries.isEmpty()) {
            Messages.send(player, "catalogue.empty", Ph.raw("name", displayName(player, name)));
            return 1;
        }

        ModUtils.CheckPlayerGroup check = ModUtils.checkPlayerGroup(player);
        if (check == null) {
            return 1;
        }

        GroupManager group = MusicManager.getInstance().getGroup(check.group(), check.source().getServer());
        String display = displayName(player, name);

        Messages.send(player, "catalogue.playing",
                Ph.raw("name", display), Ph.num("count", entries.size()));

        Bukkit.getScheduler().runTaskAsynchronously(SimpleVoiceChatMusic.get(), () -> {
            int queued = CataloguePlayer.play(group, entries);

            Bukkit.getScheduler().runTask(SimpleVoiceChatMusic.get(), () -> {
                if (player.isOnline()) {
                    Messages.send(player, "catalogue.played",
                            Ph.raw("name", display), Ph.num("count", queued));
                }
            });
        });

        return 0;
    }

    private int add(Player player, String[] args) {
        if (args.length < 2) {
            Messages.send(player, "catalogue.usage");
            return 1;
        }

        String name = PlayerCatalogue.resolveName(player.getUniqueId(), args[1]);

        if (!PlayerCatalogue.exists(player.getUniqueId(), name)) {
            Messages.send(player, "catalogue.unknown-name", Ph.raw("name", args[1]));
            return 1;
        }

        String identifier;
        String title;
        String author = "";

        if (args.length >= 3) {
            // 从聊天栏的「收藏到此」按钮进来：add <目录> <标识> <标题...>
            identifier = args[2];
            title = args.length > 3
                    ? String.join(" ", java.util.Arrays.copyOfRange(args, 3, args.length))
                    : identifier;
        } else {
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

            TrackMetadata.Meta meta = TrackMetadata.get(track.getIdentifier());

            if (meta == null) {
                meta = TrackMetadata.get(track.getInfo().uri);
            }

            identifier = track.getIdentifier();
            title = meta != null ? meta.title() : track.getInfo().title;
            author = meta != null ? meta.author() : track.getInfo().author;
        }

        PlayerCatalogue.AddResult result = PlayerCatalogue.add(player.getUniqueId(), name,
                new PlayerCatalogue.Entry(identifier, title,
                        author == null ? "" : author, System.currentTimeMillis()));

        return switch (result) {
            case ADDED -> {
                Messages.send(player, "catalogue.added",
                        Ph.raw("name", displayName(player, name)), Ph.raw("title", title));
                yield 0;
            }
            case DUPLICATE -> {
                Messages.send(player, "catalogue.duplicate", Ph.raw("title", title));
                yield 0;
            }
            case UNKNOWN_CATALOGUE -> {
                Messages.send(player, "catalogue.unknown-name", Ph.raw("name", args[1]));
                yield 1;
            }
        };
    }

    private int remove(Player player, String[] args) {
        if (args.length < 3) {
            Messages.send(player, "catalogue.usage");
            return 1;
        }

        String input = args[1];
        String name = PlayerCatalogue.resolveName(player.getUniqueId(), input);
        int index;

        try {
            index = Integer.parseInt(args[2]) - 1;
        } catch (NumberFormatException e) {
            Messages.send(player, "catalogue.usage");
            return 1;
        }

        if (PlayerCatalogue.remove(player.getUniqueId(), name, index)) {
            Messages.send(player, "catalogue.removed", Ph.raw("name", displayName(player, name)));
            return 0;
        }

        Messages.send(player, "catalogue.remove-failed");
        return 1;
    }

    private int listEntries(Player player, String[] args) {
        if (args.length < 2) {
            Messages.send(player, "catalogue.usage");
            return 1;
        }

        String input = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length));
        String name = PlayerCatalogue.resolveName(player.getUniqueId(), input);

        if (!PlayerCatalogue.exists(player.getUniqueId(), name)) {
            Messages.send(player, "catalogue.unknown-name", Ph.raw("name", args[1]));
            return 1;
        }

        List<PlayerCatalogue.Entry> entries = PlayerCatalogue.entries(player.getUniqueId(), name);

        Messages.send(player, "catalogue.entries-header",
                Ph.raw("name", displayName(player, name)), Ph.num("count", entries.size()));

        int index = 1;

        for (PlayerCatalogue.Entry entry : entries) {
            Component play = Messages.component(player, "catalogue.play-label")
                    .clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand(
                            "/music play " + entry.identifier()))
                    .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(
                            Messages.component(player, "catalogue.play-hover")));

            Component delete = Messages.component(player, "catalogue.delete-label")
                    .clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand(
                            "/music catalogue remove " + name + " " + index))
                    .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(
                            Messages.component(player, "catalogue.delete-hover")));

            Component line = Messages.component(player, "catalogue.entry-line",
                    Ph.num("index", index++),
                    Ph.raw("title", entry.displayName()))
                    .append(Component.space()).append(play)
                    .append(Component.space()).append(delete);

            player.sendMessage(line);
        }

        return 0;
    }

    private static boolean isFavoritesInput(Player player, String input) {
        return PlayerCatalogue.resolveName(player.getUniqueId(), input).equals(PlayerCatalogue.FAVORITES);
    }

    private static String displayName(Player player, String key) {
        if (PlayerCatalogue.isFavorites(key)) {
            return Messages.plain(player, "catalogue.favorites");
        }

        return key;
    }
}
