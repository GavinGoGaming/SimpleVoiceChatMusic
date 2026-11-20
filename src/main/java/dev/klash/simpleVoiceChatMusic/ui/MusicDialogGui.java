package dev.klash.simpleVoiceChatMusic.ui;

import de.maxhenkel.voicechat.api.Group;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import dev.klash.simpleVoiceChatMusic.SimpleVoiceChatMusic;
import dev.klash.simpleVoiceChatMusic.VoiceChatPlugin;
import dev.klash.simpleVoiceChatMusic.audio.AllSourceSearch;
import dev.klash.simpleVoiceChatMusic.audio.BilibiliApi;
import dev.klash.simpleVoiceChatMusic.audio.GroupManager;
import dev.klash.simpleVoiceChatMusic.audio.GroupSettingsManager;
import dev.klash.simpleVoiceChatMusic.audio.MusicManager;
import dev.klash.simpleVoiceChatMusic.audio.NetEaseApi;
import dev.klash.simpleVoiceChatMusic.audio.PlayerCatalogue;
import dev.klash.simpleVoiceChatMusic.config.PlayerPreferences;
import dev.klash.simpleVoiceChatMusic.config.PluginSettings;
import dev.klash.simpleVoiceChatMusic.i18n.Messages;
import dev.klash.simpleVoiceChatMusic.i18n.Ph;
import dev.klash.simpleVoiceChatMusic.util.ModUtils;
import dev.klash.simpleVoiceChatMusic.util.Text;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.input.SingleOptionDialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 音乐播放器主界面（Minecraft 1.21.7+ Dialog API）。
 *
 * <p>主页面就是"播放器"：正在播放 + 进度条 + 传输控制 + 搜索框；
 * 搜索、队列、设置、高级各自是二级页面，设置页里可以按玩家自己切换默认音源。</p>
 */
public final class MusicDialogGui {

    private static final int BUTTON_WIDTH = 140;
    private static final int WIDE_BUTTON_WIDTH = 320;
    private static final int BODY_WIDTH = 320;
    private static final int PROGRESS_SEGMENTS = 16;

    private MusicDialogGui() {
    }

    public static void open(Player player) {
        player.showDialog(buildMain(player));
    }

    // ------------------------------------------------------------------ main

    private static Dialog buildMain(Player player) {
        GroupManager group = resolveGroup(player);

        List<DialogBody> body = new ArrayList<>();
        body.add(body(nowPlayingText(player, group)));
        body.add(body(statusLine(player, group)));

        Component preview = queuePreview(player, group);
        if (preview != null) {
            body.add(body(preview));
        }

        body.add(body(Messages.component(player, "menu.body.hint",
                Ph.raw("source", Messages.plain(player, PlayerPreferences.sourceFor(player).langKey())))));

        List<DialogInput> inputs = List.of(
                DialogInput.text("query", Messages.component(player, "menu.input.query"))
                        .width(BODY_WIDTH)
                        .maxLength(512)
                        .build()
        );

        boolean paused = group != null && group.getPlayer().isPaused();

        List<ActionButton> actions = List.of(
                button(player, "menu.button.play", "menu.button.play-tip",
                        click(view -> runCommand(player, "music play " + safe(view.getText("query"))))),
                button(player, "menu.button.search", "menu.button.search-tip",
                        click(view -> search(player, safe(view.getText("query"))))),
                button(player, paused ? "menu.button.resume" : "menu.button.pause",
                        paused ? "menu.button.resume-tip" : "menu.button.pause-tip",
                        click(view -> runCommand(player, paused ? "music resume" : "music pause"))),
                button(player, "menu.button.skip", "menu.button.skip-tip",
                        click(view -> runCommand(player, "music skip"))),
                button(player, "menu.button.stop", "menu.button.stop-tip",
                        click(view -> runCommand(player, "music stop"))),
                nav(player, "menu.button.queue", "menu.button.queue-tip", () -> buildQueue(player)),
                nav(player, "menu.button.volume", "menu.button.volume-tip", () -> buildVolume(player)),
                nav(player, "menu.button.library", "menu.button.library-tip", () -> buildLibrary(player)),
                nav(player, "menu.button.settings", "menu.button.settings-tip", () -> buildSettings(player)),
                nav(player, "menu.button.advanced", "menu.button.advanced-tip", () -> buildAdvanced(player))
        );

        return dialog(player, Messages.component(player, "menu.title"),
                body, inputs, actions, 3, closeButton(player));
    }

    // --------------------------------------------------------------- search

    private static void search(Player player, String query) {
        if (query.isBlank()) {
            Messages.send(player, "menu.results.empty");
            return;
        }

        PlayerPreferences.Source source = PlayerPreferences.sourceFor(player);

        // YouTube / SoundCloud 没有结构化结果，直接走聊天栏搜索结果
        if (source == PlayerPreferences.Source.YOUTUBE || source == PlayerPreferences.Source.SOUNDCLOUD) {
            runCommand(player, "music search " + query);
            player.closeDialog();
            return;
        }

        SimpleVoiceChatMusic plugin = SimpleVoiceChatMusic.getOrNull();
        if (plugin == null || !plugin.isEnabled()) {
            return;
        }

        // 与聊天栏搜索保持一致：条数完全由配置决定
        PluginSettings settings = PluginSettings.get();
        final int limit = Math.max(1, settings.menuSearchResults());

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            List<ResultEntry> entries = new ArrayList<>();
            String failure = null;

            try {
                if (source == PlayerPreferences.Source.NETEASE) {
                    for (NetEaseApi.Song song : NetEaseApi.search(query, limit)) {
                        entries.add(new ResultEntry(resultLabel(player, song.displayName(),
                                song.durationMs(), song.vip()),
                                "music play " + song.identifier(), song.identifier(), song.displayName()));
                    }
                } else if (source == PlayerPreferences.Source.ALL) {
                    GroupManager group = resolveGroup(player);
                    int perSource = Math.max(1, Math.min(limit, 10));

                    for (AllSourceSearch.Hit hit : AllSourceSearch.search(group, query, perSource, 6000)) {
                        if (entries.size() >= limit) {
                            break;
                        }

                        String tag = Messages.plain(player, hit.sourceLangKey());
                        entries.add(new ResultEntry(resultLabel(player, "[" + tag + "] " + hit.display(),
                                hit.durationMs(), hit.vip()), hit.command(),
                                identifierOf(hit.command()), hit.display()));
                    }
                } else {
                    for (BilibiliApi.Video video : BilibiliApi.searchWithRetry(query, limit)) {
                        entries.add(new ResultEntry(resultLabel(player, video.displayName(),
                                video.durationMs(), false),
                                "music play " + video.identifier(), video.identifier(), video.displayName()));
                    }
                }
            } catch (Exception e) {
                failure = String.valueOf(e.getMessage());
                SimpleVoiceChatMusic.LOGGER.warning("Dialog search failed (" + source.id() + ", query="
                        + query + "): " + e.getMessage());
            }

            SimpleVoiceChatMusic.LOGGER.info("Dialog search: source=" + source.id() + ", query=" + query
                    + ", results=" + entries.size() + (failure == null ? "" : ", error=" + failure));

            List<ResultEntry> finalEntries = List.copyOf(entries);
            final String error = failure;

            runOnMainThread(player, () -> {
                if (finalEntries.isEmpty()) {
                    // 兜底：直接走和指令完全相同的搜索（结果会发到聊天栏）
                    runCommand(player, "music search " + query);

                    if (error != null) {
                        Messages.send(player, source == PlayerPreferences.Source.NETEASE
                                ? "netease.error" : "bilibili.error", Ph.raw("reason", error));
                    }
                    return;
                }

                try {
                    player.showDialog(buildResults(player, source, finalEntries));
                } catch (Throwable t) {
                    // 结果页打不开时退回聊天栏列表，保证能选歌
                    SimpleVoiceChatMusic.LOGGER.warning("Could not open the search results dialog: "
                            + t.getMessage());
                    runCommand(player, "music search " + query);
                }
            });
        });
    }

    private record ResultEntry(Component label, String command, String identifier, String plainTitle) {
    }

    private static Dialog buildResults(Player player, PlayerPreferences.Source source, List<ResultEntry> entries) {
        List<ActionButton> actions = new ArrayList<>();

        int shown = 0;

        for (ResultEntry entry : entries) {
            // Dialog 的动作按钮过多会渲染失败（进而退化成聊天栏结果），这里限制一页按钮数
            if (++shown > 14) {
                break;
            }

            actions.add(ActionButton.builder(entry.label())
                    .width(WIDE_BUTTON_WIDTH)
                    .action(click(view -> player.showDialog(buildResultActions(player, source, entry))))
                    .build());
        }

        List<DialogBody> body = new ArrayList<>();
        body.add(body(Messages.component(player, "menu.results.hint")));

        if (entries.size() > 14) {
            body.add(body(Messages.component(player, "menu.results.more",
                    Ph.num("total", entries.size()))));
        }

        return dialog(player,
                Messages.component(player, "menu.results.title",
                        Ph.raw("source", Messages.plain(player, source.langKey()))),
                body,
                List.of(), actions, 1, backButton(player));
    }

    // -------------------------------------------------------------- library

    private static Dialog buildResultActions(Player player, PlayerPreferences.Source source, ResultEntry entry) {
        List<ActionButton> actions = new ArrayList<>();

        actions.add(button(player, "menu.button.play-song", "menu.button.play-song-tip",
                click(view -> runCommand(player, entry.command()))));

        actions.add(button(player, "menu.button.collect", "menu.button.collect-tip",
                click(view -> player.showDialog(buildCollectTargets(player, entry.identifier(),
                        entry.plainTitle())))));

        return dialog(player, Messages.component(player, "menu.results.song-title"),
                List.of(body(entry.label()), body(Messages.component(player, "menu.results.song-hint"))),
                List.of(), actions, 1, backButton(player));
    }

    private static Dialog buildLibrary(Player player) {
        Map<String, List<PlayerCatalogue.Entry>> catalogues =
                PlayerCatalogue.catalogues(player.getUniqueId());

        List<DialogBody> body = new ArrayList<>();
        body.add(body(Messages.component(player, "menu.library.hint",
                Ph.num("count", catalogues.size()))));

        List<ActionButton> actions = new ArrayList<>();

        for (Map.Entry<String, List<PlayerCatalogue.Entry>> entry : catalogues.entrySet()) {
            String key = entry.getKey();
            String name = PlayerCatalogue.isFavorites(key)
                    ? Messages.plain(player, "catalogue.favorites")
                    : key;

            actions.add(ActionButton.builder(Messages.component(player, "menu.library.line",
                            Ph.raw("name", name), Ph.num("count", entry.getValue().size())))
                    .width(WIDE_BUTTON_WIDTH)
                    .tooltip(Messages.component(player, "menu.library.open-tip"))
                    .action(click(view -> player.showDialog(buildCatalogue(player, key))))
                    .build());
        }

        List<DialogInput> inputs = List.of(
                DialogInput.text("catalogue_name", Messages.component(player, "menu.library.create-name"))
                        .width(BODY_WIDTH)
                        .maxLength(32)
                        .build()
        );

        actions.add(button(player, "menu.library.create", "menu.library.create-tip",
                click(view -> {
                    String name = safe(view.getText("catalogue_name"));

                    if (name.isEmpty() || !PlayerCatalogue.create(player.getUniqueId(), name)) {
                        Messages.send(player, "catalogue.create-failed", Ph.raw("name", name));
                        return;
                    }

                    Messages.send(player, "catalogue.created", Ph.raw("name", name));
                    player.showDialog(buildLibrary(player));
                })));

        return dialog(player, Messages.component(player, "menu.library.title"),
                body, inputs, actions, 1, backButton(player));
    }

    private static Dialog buildCollectTargets(Player player, String identifier, String title) {
        Map<String, List<PlayerCatalogue.Entry>> catalogues =
                PlayerCatalogue.catalogues(player.getUniqueId());

        List<ActionButton> actions = new ArrayList<>();

        for (Map.Entry<String, List<PlayerCatalogue.Entry>> entry : catalogues.entrySet()) {
            String key = entry.getKey();
            String name = PlayerCatalogue.isFavorites(key)
                    ? Messages.plain(player, "catalogue.favorites")
                    : key;

            actions.add(ActionButton.builder(Messages.component(player, "menu.collect.line",
                            Ph.raw("name", name), Ph.num("count", entry.getValue().size())))
                    .width(WIDE_BUTTON_WIDTH)
                    .action(click(view -> {
                        runCommand(player, "music catalogue add " + key + " " + identifier + " " + title);
                        player.showDialog(buildMain(player));
                    }))
                    .build());
        }

        return dialog(player, Messages.component(player, "menu.collect.title"),
                List.of(body(Messages.component(player, "menu.collect.hint", Ph.raw("title", title)))),
                List.of(), actions, 1, backButton(player));
    }

    private static Dialog buildCatalogueSong(Player player, String key, String catalogueName, int index) {
        List<PlayerCatalogue.Entry> entries = PlayerCatalogue.entries(player.getUniqueId(), key);

        if (index < 0 || index >= entries.size()) {
            return buildCatalogue(player, key);
        }

        PlayerCatalogue.Entry entry = entries.get(index);

        List<ActionButton> actions = new ArrayList<>();

        actions.add(button(player, "menu.button.play-song", "menu.button.play-song-tip",
                click(view -> runCommand(player, "music play " + entry.identifier()))));

        actions.add(button(player, "menu.button.remove-song", "menu.button.remove-song-tip",
                click(view -> {
                    PlayerCatalogue.remove(player.getUniqueId(), key, index);
                    Messages.send(player, "catalogue.removed", Ph.raw("name", catalogueName));
                    player.showDialog(buildCatalogue(player, key));
                })));

        return dialog(player, Messages.component(player, "menu.catalogue.song-title"),
                List.of(body(Messages.component(player, "menu.catalogue.song-line",
                                Ph.raw("title", entry.displayName()))),
                        body(Messages.component(player, "menu.catalogue.song-hint"))),
                List.of(), actions, 1, backButton(player));
    }

    private static Dialog buildCatalogue(Player player, String key) {
        List<PlayerCatalogue.Entry> entries = PlayerCatalogue.entries(player.getUniqueId(), key);
        String name = PlayerCatalogue.isFavorites(key)
                ? Messages.plain(player, "catalogue.favorites")
                : key;

        List<DialogBody> body = new ArrayList<>();
        body.add(body(Messages.component(player, "menu.catalogue.hint", Ph.num("count", entries.size()))));

        List<ActionButton> actions = new ArrayList<>();
        int index = 1;

        for (PlayerCatalogue.Entry entry : entries) {
            if (index > 20) {
                break;
            }

            final int songIndex = index - 1;
            actions.add(ActionButton.builder(Messages.component(player, "menu.catalogue.line",
                            Ph.num("index", index++), Ph.raw("title", entry.displayName())))
                    .width(WIDE_BUTTON_WIDTH)
                    .tooltip(Messages.component(player, "menu.catalogue.song-tip"))
                    .action(click(view -> player.showDialog(
                            buildCatalogueSong(player, key, name, songIndex))))
                    .build());
        }

        actions.add(button(player, "menu.catalogue.play-all", "menu.catalogue.play-all-tip",
                click(view -> {
                    player.closeDialog();
                    runCommand(player, "music catalogue play " + key);
                })));

        if (!PlayerCatalogue.isFavorites(key)) {
            actions.add(button(player, "menu.catalogue.delete", "menu.catalogue.delete-tip",
                    click(view -> {
                        PlayerCatalogue.delete(player.getUniqueId(), key);
                        Messages.send(player, "catalogue.deleted", Ph.raw("name", name));
                        player.showDialog(buildLibrary(player));
                    })));
        }

        return dialog(player, Messages.component(player, "menu.catalogue.title", Ph.raw("name", name)),
                body, List.of(), actions, 1, backButton(player));
    }

    /** hit.command() 形如 "music play &lt;identifier&gt;" */
    private static String identifierOf(String playCommand) {
        int space = playCommand.lastIndexOf(' ');

        return space < 0 ? playCommand : playCommand.substring(space + 1);
    }

    // ---------------------------------------------------------------- queue

    private static Dialog buildQueue(Player player) {
        GroupManager group = resolveGroup(player);
        List<DialogBody> body = new ArrayList<>();
        List<ActionButton> actions = new ArrayList<>();

        if (group == null) {
            body.add(body(Messages.component(player, "voicechat.no-group")));
        } else {
            var playing = group.getPlayer().getPlayingTrack();

            if (playing != null) {
                body.add(body(Messages.component(player, "menu.queue.current",
                        Ph.raw("title", ModUtils.displayTitle(playing)))));
            }

            if (group.getQueue().isEmpty()) {
                body.add(body(Messages.component(player, "menu.queue.empty")));
            } else {
                int index = 1;
                for (var track : group.getQueue()) {
                    body.add(body(Messages.component(player, "menu.queue.line",
                            Ph.raw("index", Integer.toString(index++)),
                            Ph.raw("title", ModUtils.displayTitle(track)),
                            Ph.raw("duration", ModUtils.formatMMSS(track.getDuration())))));

                    if (index > PluginSettings.get().menuQueuePageSize()) {
                        break;
                    }
                }

                actions.add(button(player, "menu.button.clear-queue", "menu.button.clear-queue-tip",
                        click(view -> {
                            int cleared = group.clearQueue();
                            Messages.send(player, "menu.queue.cleared", Ph.num("count", cleared));
                            player.showDialog(buildQueue(player));
                        })));
            }
        }

        return dialog(player, Messages.component(player, "menu.queue.title"),
                body, List.of(), actions, 1, backButton(player));
    }

    // ------------------------------------------------------------- settings

    private static Dialog buildSettings(Player player) {
        GroupManager group = resolveGroup(player);
        float bass = group == null ? 0f : group.getSettingsStore().bassboost;

        PlayerPreferences.Source current = PlayerPreferences.sourceFor(player);

        List<DialogInput> inputs = List.of(
                DialogInput.numberRange("bassboost", Messages.component(player, "menu.input.bassboost"), 0f, 200f)
                        .initial(bass)
                        .step(5f)
                        .width(BODY_WIDTH)
                        .build(),
                DialogInput.singleOption("language", Messages.component(player, "menu.input.language"),
                                languageOptions(player))
                        .width(BODY_WIDTH)
                        .build(),
                DialogInput.singleOption("source", Messages.component(player, "menu.input.source"),
                                sourceOptions(player, current))
                        .width(BODY_WIDTH)
                        .build(),
                DialogInput.singleOption("loop", Messages.component(player, "menu.input.loop"),
                                loopOptions(player, group))
                        .width(BODY_WIDTH)
                        .build(),
                DialogInput.bool("hide_lyrics", Messages.component(player, "menu.input.hide-lyrics"))
                        .initial(PlayerPreferences.hideLyrics(player))
                        .build(),
                DialogInput.bool("hide_progress", Messages.component(player, "menu.input.hide-progress"))
                        .initial(PlayerPreferences.hideProgress(player))
                        .build()
        );

        List<ActionButton> actions = List.of(
                button(player, "menu.button.apply-settings", "menu.button.apply-settings-tip",
                        click(view -> {
                            Float newBass = view.getFloat("bassboost");

                            if (newBass != null) {
                                runCommand(player, "music bassboost " + Math.round(newBass));
                            }

                            String language = safe(view.getText("language"));
                            if (!language.isEmpty()) {
                                runCommand(player, "music lang " + language);
                            }

                            PlayerPreferences.Source chosen = PlayerPreferences.Source.fromInput(safe(view.getText("source")));
                            if (chosen != null) {
                                PlayerPreferences.setSource(player, chosen);
                                Messages.send(player, "source.set",
                                        Ph.raw("source", Messages.plain(player, chosen.langKey())));
                            }

                            GroupSettingsManager.LoopMode loop = GroupSettingsManager.LoopMode.fromInput(
                                    safe(view.getText("loop")));

                            if (loop != null) {
                                runCommand(player, "music loop " + loop.id());
                            }

                            Boolean hideLyrics = view.getBoolean("hide_lyrics");
                            if (hideLyrics != null) {
                                PlayerPreferences.setHideLyrics(player, hideLyrics);
                            }

                            Boolean hideProgress = view.getBoolean("hide_progress");
                            if (hideProgress != null) {
                                PlayerPreferences.setHideProgress(player, hideProgress);
                            }

                            player.showDialog(buildMain(player));
                        }))
        );

        return dialog(player, Messages.component(player, "menu.settings.title"),
                List.of(body(Messages.component(player, "menu.settings.hint"))),
                inputs, actions, 1, backButton(player));
    }

    // --------------------------------------------------------------- volume

    private static Dialog buildVolume(Player player) {
        GroupManager group = resolveGroup(player);
        int volume = group == null ? 100 : group.getSettingsStore().volume;

        List<DialogInput> inputs = List.of(
                DialogInput.numberRange("volume", Messages.component(player, "menu.input.volume"), 0f, 100f)
                        .initial((float) volume)
                        .step(1f)
                        .width(BODY_WIDTH)
                        .build()
        );

        List<ActionButton> actions = List.of(
                button(player, "menu.button.apply-volume", "menu.button.apply-volume-tip",
                        click(view -> {
                            Float newVolume = view.getFloat("volume");
                            if (newVolume != null) {
                                runCommand(player, "music volume " + Math.round(newVolume));
                            }
                            player.showDialog(buildVolume(player));
                        }))
        );

        return dialog(player, Messages.component(player, "menu.volume.title"),
                List.of(body(Messages.component(player, "menu.volume.hint"))),
                inputs, actions, 1, backButton(player));
    }

    // ------------------------------------------------------------- advanced

    private static Dialog buildAdvanced(Player player) {
        List<ActionButton> actions = new ArrayList<>();

        actions.add(button(player, "menu.button.now-playing", "menu.button.now-playing-tip",
                click(view -> player.showDialog(buildNowPlaying(player)))));

        actions.add(button(player, "menu.button.kill", "menu.button.kill-tip",
                click(view -> player.showDialog(buildKillConfirm(player)))));

        if (player.hasPermission(SimpleVoiceChatMusic.PERMISSION_ADMIN)) {
            actions.add(button(player, "menu.button.login-netease", "menu.button.login-netease-tip",
                    click(view -> {
                        player.closeDialog();
                        runCommand(player, "music login netease");
                    })));

            actions.add(button(player, "menu.button.login-bilibili", "menu.button.login-bilibili-tip",
                    click(view -> {
                        player.closeDialog();
                        runCommand(player, "music login bilibili");
                    })));

            actions.add(button(player, "menu.button.logout", "menu.button.logout-tip",
                    click(view -> runCommand(player, "music login logout"))));

            actions.add(button(player, "menu.button.register-service", "menu.button.register-service-tip",
                    click(view -> {
                        runCommand(player, "music_register_service");
                        player.showDialog(buildAdvanced(player));
                    })));
        }

        return dialog(player, Messages.component(player, "menu.advanced.title"),
                List.of(body(Messages.component(player, "menu.advanced.hint"))),
                List.of(), actions, 2, backButton(player));
    }

    // ---------------------------------------------------------- now playing

    private static Dialog buildNowPlaying(Player player) {
        GroupManager group = resolveGroup(player);

        List<DialogBody> body = new ArrayList<>();
        body.add(body(nowPlayingText(player, group)));
        body.add(body(statusLine(player, group)));

        return dialog(player, Messages.component(player, "menu.now-playing.title"),
                body, List.of(), List.of(), 1, backButton(player));
    }

    // --------------------------------------------------------------- kill

    private static Dialog buildKillConfirm(Player player) {
        DialogBase base = DialogBase.builder(Messages.component(player, "menu.kill.title"))
                .canCloseWithEscape(true)
                .body(List.of(body(Messages.component(player, "menu.kill.body"))))
                .build();

        ActionButton confirm = ActionButton.builder(Messages.component(player, "menu.kill.confirm"))
                .width(BUTTON_WIDTH)
                .action(click(view -> {
                    player.closeDialog();
                    runCommand(player, "music kill");
                    runCommand(player, "music kill");
                }))
                .build();

        ActionButton cancel = ActionButton.builder(Messages.component(player, "menu.kill.cancel"))
                .width(BUTTON_WIDTH)
                .action(click(view -> player.showDialog(buildAdvanced(player))))
                .build();

        return Dialog.create(builder -> builder.empty().base(base)
                .type(DialogType.confirmation(confirm, cancel)));
    }

    // ------------------------------------------------------------- helpers

    private static Dialog dialog(Player player, Component title, List<DialogBody> body, List<DialogInput> inputs,
                                 List<ActionButton> actions, int columns, ActionButton exitAction) {
        DialogBase base = DialogBase.builder(title)
                .canCloseWithEscape(true)
                .body(body)
                .inputs(inputs)
                .build();

        DialogType type = actions.isEmpty()
                ? DialogType.notice(exitAction)
                : DialogType.multiAction(actions, exitAction, Math.max(1, columns));

        return Dialog.create(builder -> builder.empty().base(base).type(type));
    }

    private static Component nowPlayingText(Player player, GroupManager group) {
        if (group == null || group.getPlayer().getPlayingTrack() == null) {
            return Messages.component(player, "menu.body.idle");
        }

        var track = group.getPlayer().getPlayingTrack();

        return Messages.component(player, "menu.body.now-playing",
                Ph.raw("title", ModUtils.displayTitle(track)));
    }

    private static Component statusLine(Player player, GroupManager group) {
        if (group == null) {
            return Messages.component(player, "menu.body.no-group");
        }

        GroupSettingsManager settings = group.getSettingsStore();

        return Messages.component(player, "menu.body.status",
                Ph.raw("count", Integer.toString(group.getQueue().size())),
                Ph.raw("volume", Integer.toString(settings.volume)),
                Ph.raw("bass", Integer.toString(Math.round(settings.bassboost))),
                Ph.raw("loop", Messages.plain(player, settings.loop.langKey())));
    }

    private static Component queuePreview(Player player, GroupManager group) {
        if (group == null || group.getQueue().isEmpty()) {
            return null;
        }

        int limit = PluginSettings.get().menuPreviewCount();
        StringBuilder titles = new StringBuilder();
        int shown = 0;

        for (var track : group.getQueue()) {
            if (shown >= limit) {
                break;
            }
            if (shown > 0) {
                titles.append(" / ");
            }
            titles.append(ModUtils.displayTitle(track));
            shown++;
        }

        if (group.getQueue().size() > shown) {
            titles.append(" …");
        }

        return Messages.component(player, "menu.body.queue-preview",
                Ph.raw("tracks", titles.toString()));
    }

    private static Component resultLabel(Player player, String title, long durationMs, boolean vip) {
        Component label = Text.literal(title).color(NamedTextColor.WHITE);

        if (durationMs > 0) {
            label = label.append(Text.literal(" [" + ModUtils.formatMMSS(durationMs) + "]")
                    .color(NamedTextColor.DARK_GRAY));
        }

        if (vip) {
            label = label.append(Component.space())
                    .append(Messages.component(player, "netease.vip-badge"));
        }

        return label;
    }

    private static GroupManager resolveGroup(Player player) {
        try {
            if (VoiceChatPlugin.voicechatServerApi == null) {
                return null;
            }

            VoicechatConnection connection =
                    VoiceChatPlugin.voicechatServerApi.getConnectionOf(player.getUniqueId());

            if (connection == null || !connection.isConnected()) {
                return null;
            }

            Group group = connection.getGroup();
            if (group == null) {
                return null;
            }

            SimpleVoiceChatMusic plugin = SimpleVoiceChatMusic.getOrNull();
            if (plugin == null) {
                return null;
            }

            return MusicManager.getInstance().getGroup(group, player.getServer());
        } catch (Throwable t) {
            return null;
        }
    }

    private static List<SingleOptionDialogInput.OptionEntry> languageOptions(Player player) {
        List<SingleOptionDialogInput.OptionEntry> options = new ArrayList<>();

        SimpleVoiceChatMusic plugin = SimpleVoiceChatMusic.getOrNull();
        List<String> available = plugin == null ? List.of("en_us") : plugin.languages().available();
        String current = plugin == null ? "en_us" : plugin.languages().resolve(player);

        boolean override = plugin != null && plugin.languages().overrideOf(player.getUniqueId()) != null;

        options.add(SingleOptionDialogInput.OptionEntry.create(
                "auto",
                Messages.component(player, "menu.language.auto"),
                !override));

        for (String code : available) {
            options.add(SingleOptionDialogInput.OptionEntry.create(
                    code,
                    Component.text(code),
                    override && code.equalsIgnoreCase(current)));
        }

        return options;
    }

    private static List<SingleOptionDialogInput.OptionEntry> loopOptions(Player player, GroupManager group) {
        GroupSettingsManager.LoopMode current = group == null
                ? GroupSettingsManager.LoopMode.OFF
                : group.loopMode();

        List<SingleOptionDialogInput.OptionEntry> options = new ArrayList<>();

        for (GroupSettingsManager.LoopMode mode : GroupSettingsManager.LoopMode.values()) {
            options.add(SingleOptionDialogInput.OptionEntry.create(
                    mode.id(),
                    Messages.component(player, mode.langKey()),
                    mode == current));
        }

        return options;
    }

    private static List<SingleOptionDialogInput.OptionEntry> sourceOptions(Player player,
                                                                          PlayerPreferences.Source current) {
        List<SingleOptionDialogInput.OptionEntry> options = new ArrayList<>();

        for (PlayerPreferences.Source source : PlayerPreferences.Source.values()) {
            options.add(SingleOptionDialogInput.OptionEntry.create(
                    source.id(),
                    Messages.component(player, source.langKey()),
                    source == current));
        }

        return options;
    }

    private static ActionButton nav(Player player, String labelKey, String tooltipKey, Supplier<Dialog> target) {
        return button(player, labelKey, tooltipKey, click(view -> player.showDialog(target.get())));
    }

    private static ActionButton button(Player player, String labelKey, String tooltipKey, DialogAction action) {
        return ActionButton.builder(Messages.component(player, labelKey))
                .tooltip(Messages.component(player, tooltipKey))
                .width(BUTTON_WIDTH)
                .action(action)
                .build();
    }

    private static ActionButton backButton(Player player) {
        return ActionButton.builder(Messages.component(player, "menu.button.back"))
                .width(BUTTON_WIDTH)
                .action(click(view -> player.showDialog(buildMain(player))))
                .build();
    }

    private static ActionButton closeButton(Player player) {
        return ActionButton.builder(Messages.component(player, "menu.button.close"))
                .width(BUTTON_WIDTH)
                .build();
    }

    private static DialogAction click(Consumer<DialogResponseView> handler) {
        return DialogAction.customClick(
                (view, audience) -> handler.accept(view),
                ClickCallback.Options.builder()
                        .uses(1)
                        .lifetime(Duration.ofMinutes(10))
                        .build());
    }

    private static DialogBody body(Component text) {
        return DialogBody.plainMessage(text, BODY_WIDTH);
    }

    private static void runCommand(Player player, String command) {
        try {
            player.performCommand(command);
        } catch (Throwable t) {
            SimpleVoiceChatMusic.LOGGER.warning("Dialog action failed (" + command + "): " + t.getMessage());
        }
    }

    private static void runOnMainThread(Player player, Runnable task) {
        SimpleVoiceChatMusic plugin = SimpleVoiceChatMusic.getOrNull();
        if (plugin == null || !plugin.isEnabled()) {
            return;
        }

        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                task.run();
            }
        });
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
