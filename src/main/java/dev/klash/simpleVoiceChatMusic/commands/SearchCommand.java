package dev.klash.simpleVoiceChatMusic.commands;

import dev.klash.simpleVoiceChatMusic.SimpleVoiceChatMusic;
import dev.klash.simpleVoiceChatMusic.audio.AllSourceSearch;
import dev.klash.simpleVoiceChatMusic.audio.BilibiliApi;
import dev.klash.simpleVoiceChatMusic.audio.GroupManager;
import dev.klash.simpleVoiceChatMusic.audio.MusicManager;
import dev.klash.simpleVoiceChatMusic.audio.NetEaseApi;
import dev.klash.simpleVoiceChatMusic.audio.SearchLoadHandler;
import dev.klash.simpleVoiceChatMusic.config.PlayerPreferences;
import dev.klash.simpleVoiceChatMusic.config.PluginSettings;
import dev.klash.simpleVoiceChatMusic.i18n.Messages;
import dev.klash.simpleVoiceChatMusic.i18n.Ph;
import dev.klash.simpleVoiceChatMusic.util.Cooldowns;
import dev.klash.simpleVoiceChatMusic.util.LinkResolver;
import dev.klash.simpleVoiceChatMusic.util.ModUtils;
import dev.klash.simpleVoiceChatMusic.util.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.List;

import static dev.klash.simpleVoiceChatMusic.util.ModUtils.checkPlayerGroup;
import static dev.klash.simpleVoiceChatMusic.util.ModUtils.parseTrackId;

public class SearchCommand implements Command {

    @Override
    public int execute(Player context, String[] args) {
        String raw = LinkResolver.normalize(String.join(" ", args)).trim();
        int page = 1;

        int pageFlag = raw.lastIndexOf(" --page ");
        if (pageFlag > 0) {
            try {
                page = Math.max(1, Integer.parseInt(raw.substring(pageFlag + 8).trim()));
            } catch (NumberFormatException ignored) {
                page = 1;
            }
            raw = raw.substring(0, pageFlag).trim();
        }

        if (raw.isEmpty()) {
            Messages.send(context, "search.usage");
            return 1;
        }

        long waitMs = Cooldowns.tryAcquire(context.getUniqueId(), "load",
                SimpleVoiceChatMusic.get().settings().playCooldownMs());
        if (waitMs > 0) {
            Messages.send(context, "general.cooldown",
                    Ph.raw("seconds", String.format(java.util.Locale.ROOT, "%.1f", waitMs / 1000.0)));
            return 1;
        }

        ModUtils.CheckPlayerGroup result = checkPlayerGroup(context);
        if (result == null) {
            return 1;
        }

        boolean prefixed = raw.startsWith("ytsearch:")
                || raw.startsWith("ytmsearch:")
                || raw.startsWith("scsearch:");

        String lower = raw.toLowerCase(java.util.Locale.ROOT);

        // 网易云搜索：/music search netease:关键词
        if (lower.startsWith("netease:") || lower.startsWith("wyy:")) {
            String keyword = raw.substring(raw.indexOf(':') + 1).trim();

            if (keyword.isEmpty()) {
                Messages.send(context, "netease.search-usage");
                return 1;
            }

            return searchNetEase(context, keyword, page);
        }

        // 普通关键词：按玩家自己设置的默认音源搜索
        String query = raw;

        if (!prefixed && LinkResolver.typeOf(raw) == LinkResolver.Type.SEARCH) {
            switch (PlayerPreferences.sourceFor(context)) {
                case ALL -> {
                    return searchAll(context, raw, page);
                }
                case NETEASE -> {
                    return searchNetEase(context, raw, page);
                }
                case BILIBILI -> {
                    if (PluginSettings.get().bilibili().enabled()) {
                        return searchBilibili(context, raw, page);
                    }
                }
                case YOUTUBE -> query = "ytsearch:" + raw;
                case SOUNDCLOUD -> query = "scsearch:" + raw;
            }
        }

        final String finalQuery = parseTrackId(query);

        SimpleVoiceChatMusic.LOGGER.info("Searching for " + finalQuery);

        GroupManager gm = MusicManager.getInstance().getGroup(result.group(), result.source().getServer());
        Messages.send(result.source(), "search.loading");

        Bukkit.getScheduler().runTaskAsynchronously(SimpleVoiceChatMusic.get(), () ->
                MusicManager.getInstance().playerManager.loadItemOrdered(
                        gm.getPlayer(),
                        finalQuery,
                        new SearchLoadHandler(result.source(), gm, finalQuery)
                ));

        return 0;
    }

    /**
     * 「全部音源」：并发搜索所有音源，按音源分组展示。
     */
    private int searchAll(Player context, String keyword, int page) {
        SimpleVoiceChatMusic plugin = SimpleVoiceChatMusic.getOrNull();
        if (plugin == null || !plugin.isEnabled()) {
            return 1;
        }

        Messages.send(context, "search.all-loading");

        Bukkit.getScheduler().runTask(plugin, () -> {
            ModUtils.CheckPlayerGroup result = ModUtils.checkPlayerGroup(context);
            if (result == null) {
                return;
            }

            GroupManager group = MusicManager.getInstance().getGroup(result.group(), result.source().getServer());
            int limit = PluginSettings.get().maxSearchResults();

            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                List<AllSourceSearch.Hit> hits = AllSourceSearch.search(group, keyword, limit, 6000);

                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!context.isOnline()) {
                        return;
                    }

                    if (hits.isEmpty()) {
                        Messages.send(context, "search.all-empty");
                        return;
                    }

                    context.sendMessage(renderAllResults(context, hits));
                });
            });
        });

        return 0;
    }

    private Component renderAllResults(Player viewer, List<AllSourceSearch.Hit> hits) {
        Component text = Messages.render(viewer, "search.all-header", Ph.num("count", hits.size()));
        Component bullet = Messages.component(viewer, "search.bullet");
        Component label = styledLabel(Messages.component(viewer, "search.click-to-queue"));
        Component hover = Messages.component(viewer, "search.click-hover");

        for (AllSourceSearch.Hit hit : hits) {
            Component clickable = label
                    .clickEvent(ClickEvent.runCommand("/" + hit.command()))
                    .hoverEvent(HoverEvent.showText(hover));

            Component line = Text.literal("[").color(NamedTextColor.DARK_GRAY)
                    .append(Messages.component(viewer, hit.sourceLangKey()))
                    .append(Text.literal("] ").color(NamedTextColor.DARK_GRAY))
                    .append(Text.literal(hit.display()).color(NamedTextColor.WHITE));

            if (hit.durationMs() > 0) {
                line = line.append(Text.literal(" [" + ModUtils.formatMMSS(hit.durationMs()) + "]")
                        .color(NamedTextColor.DARK_GRAY));
            }

            if (hit.vip()) {
                line = line.append(Text.literal(" ")).append(Messages.component(viewer, "netease.vip-badge"));
            }

            text = text.append(Text.literal("\n"))
                    .append(bullet)
                    .append(line)
                    .append(Text.literal(" "))
                    .append(clickable)
                    .append(Text.literal(" "))
                    .append(collectLabel(viewer, identifierOf(hit.command()),
                            hit.display()));
        }

        return text;
    }

    /** hit.command() 形如 "music play <identifier>" */
    private static String identifierOf(String playCommand) {
        int space = playCommand.lastIndexOf(' ');

        return space < 0 ? playCommand : playCommand.substring(space + 1);
    }

    /**
     * 用 B 站搜索并展示结果（点击条目执行 /music play bilibili://BVxxx）。
     */
    private int searchBilibili(Player context, String keyword, int page) {
        Messages.send(context, "search.loading");

        Bukkit.getScheduler().runTaskAsynchronously(SimpleVoiceChatMusic.get(), () -> {
            try {
                List<BilibiliApi.Video> videos =
                        BilibiliApi.searchWithRetry(keyword, PluginSettings.get().maxSearchResults(), page);

                Bukkit.getScheduler().runTask(SimpleVoiceChatMusic.get(), () -> {
                    if (!context.isOnline()) {
                        return;
                    }

                    if (videos.isEmpty()) {
                        Messages.send(context, "bilibili.no-results");
                        return;
                    }

                    context.sendMessage(renderResults(context, videos));

                    if (videos.size() >= PluginSettings.get().maxSearchResults()) {
                        context.sendMessage(nextPage(context, keyword, page));
                    }
                });
            } catch (Exception e) {
                SimpleVoiceChatMusic.LOGGER.warning("Bilibili search failed: " + e.getMessage());

                Bukkit.getScheduler().runTask(SimpleVoiceChatMusic.get(), () -> {
                    if (context.isOnline()) {
                        Messages.send(context, "bilibili.error", Ph.raw("reason", String.valueOf(e.getMessage())));
                    }
                });
            }
        });

        return 0;
    }

    /**
     * 网易云搜索并展示结果（点击条目执行 /music play netease://id）。
     */
    private int searchNetEase(Player context, String keyword, int page) {
        Messages.send(context, "search.loading");

        Bukkit.getScheduler().runTaskAsynchronously(SimpleVoiceChatMusic.get(), () -> {
            try {
                List<NetEaseApi.Song> songs = NetEaseApi.search(keyword, PluginSettings.get().maxSearchResults(), (page - 1) * PluginSettings.get().maxSearchResults());

                Bukkit.getScheduler().runTask(SimpleVoiceChatMusic.get(), () -> {
                    if (!context.isOnline()) {
                        return;
                    }

                    if (songs.isEmpty()) {
                        Messages.send(context, "netease.no-results");
                        return;
                    }

                    context.sendMessage(renderNetEaseResults(context, songs));

                    if (songs.size() >= PluginSettings.get().maxSearchResults()) {
                        context.sendMessage(nextPage(context, keyword, page));
                    }
                });
            } catch (Exception e) {
                SimpleVoiceChatMusic.LOGGER.warning("NetEase search failed: " + e.getMessage());

                Bukkit.getScheduler().runTask(SimpleVoiceChatMusic.get(), () -> {
                    if (context.isOnline()) {
                        Messages.send(context, "netease.error", Ph.raw("reason", String.valueOf(e.getMessage())));
                    }
                });
            }
        });

        return 0;
    }

    /** 搜索结果里的「收藏歌曲」按钮（写入玩家的"我的喜欢"） */
    private Component collectLabel(Player viewer, String identifier, String title) {
        Component label = styledLabel(Messages.component(viewer, "search.collect"));

        return label
                .clickEvent(ClickEvent.runCommand("/music collect " + identifier + " " + title))
                .hoverEvent(HoverEvent.showText(Messages.component(viewer, "search.collect-hover")));
    }

    private Component nextPage(Player viewer, String keyword, int page) {
        return Messages.component(viewer, "search.next-page", Ph.num("page", page + 1))
                .clickEvent(ClickEvent.runCommand("/music search " + keyword + " --page " + (page + 1)))
                .hoverEvent(HoverEvent.showText(Messages.component(viewer, "search.next-page-hover")));
    }

    private Component renderNetEaseResults(Player viewer, List<NetEaseApi.Song> songs) {
        Component text = Messages.render(viewer, "search.header", Ph.num("count", songs.size()));
        Component bullet = Messages.component(viewer, "search.bullet");
        Component label = styledLabel(Messages.component(viewer, "search.click-to-queue"));
        Component hover = Messages.component(viewer, "search.click-hover");

        for (NetEaseApi.Song song : songs) {
            Component clickable = label
                    .clickEvent(ClickEvent.runCommand("/music play " + song.identifier()))
                    .hoverEvent(HoverEvent.showText(hover));

            Component line = Text.literal(song.displayName()).color(NamedTextColor.WHITE);

            if (song.durationMs() > 0) {
                line = line.append(Text.literal(" [" + ModUtils.formatMMSS(song.durationMs()) + "]")
                        .color(NamedTextColor.DARK_GRAY));
            }

            if (song.vip()) {
                line = line.append(Text.literal(" ")).append(Messages.component(viewer, "netease.vip-badge"));
            }

            text = text.append(Text.literal("\n"))
                    .append(bullet)
                    .append(line)
                    .append(Text.literal(" "))
                    .append(clickable)
                    .append(Text.literal(" "))
                    .append(collectLabel(viewer, song.identifier(), song.displayName()));
        }

        return text;
    }

    private Component renderResults(Player viewer, List<BilibiliApi.Video> videos) {
        Component text = Messages.render(viewer, "search.header", Ph.num("count", videos.size()));
        Component bullet = Messages.component(viewer, "search.bullet");
        Component label = styledLabel(Messages.component(viewer, "search.click-to-queue"));
        Component hover = Messages.component(viewer, "search.click-hover");

        for (BilibiliApi.Video video : videos) {
            Component clickable = label
                    .clickEvent(ClickEvent.runCommand("/music play " + video.identifier()))
                    .hoverEvent(HoverEvent.showText(hover));

            Component line = Text.literal(video.displayName()).color(NamedTextColor.WHITE);

            if (video.durationMs() > 0) {
                line = line.append(Text.literal(" [" + ModUtils.formatMMSS(video.durationMs()) + "]")
                        .color(NamedTextColor.DARK_GRAY));
            }

            text = text.append(Text.literal("\n"))
                    .append(bullet)
                    .append(line)
                    .append(Text.literal(" "))
                    .append(clickable)
                    .append(Text.literal(" "))
                    .append(collectLabel(viewer, video.identifier(), video.displayName()));
        }

        return text;
    }

    /**
     * 按 config.yml 的 {@code music.search.click-color / click-bold} 给「[点击加入队列]」上色。
     * 配置留空时保留语言文件里的样式。
     */
    private Component styledLabel(Component label) {
        PluginSettings settings = PluginSettings.get();
        Component result = label;

        TextColor color = Text.parseColor(settings.searchClickColor());
        if (color != null) {
            result = result.color(color);
        }

        if (settings.searchClickBold()) {
            result = result.decorate(TextDecoration.BOLD);
        }

        return result;
    }
}
