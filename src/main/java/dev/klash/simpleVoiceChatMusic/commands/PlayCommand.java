package dev.klash.simpleVoiceChatMusic.commands;

import dev.klash.simpleVoiceChatMusic.audio.GroupManager;
import dev.klash.simpleVoiceChatMusic.audio.MusicManager;
import dev.klash.simpleVoiceChatMusic.SimpleVoiceChatMusic;
import dev.klash.simpleVoiceChatMusic.audio.PlayLoadHandler;
import dev.klash.simpleVoiceChatMusic.util.ModUtils;
import dev.klash.simpleVoiceChatMusic.util.Text;
import dev.klash.simpleVoiceChatMusic.audio.SpotifyHandler;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import static dev.klash.simpleVoiceChatMusic.util.ModUtils.checkPlayerGroup;

public class PlayCommand implements Command {
    @Override
    public int execute(Player context, String[] args) throws Exception {
        // Join arguments into a single string
        final String input = String.join(" ", args);

        // Check if the input is a Spotify track link
        if (input.contains("open.spotify.com/track/")) {
            // Process Spotify lookup asynchronously
            Bukkit.getScheduler().runTaskAsynchronously(SimpleVoiceChatMusic.get(), () -> {
                try {
                    String trackId = SpotifyHandler.extractTrackId(input);
                    if (trackId == null) {
                        // fallback: use the raw input if extraction fails.
                        runPlayTask(context, input);
                        return;
                    }
                    // fetches track info from Spotify
                    SpotifyHandler.TrackInfo trackInfo = SpotifyHandler.fetchTrackInfo(trackId);
                    String searchQuery = "ytsearch:" + trackInfo.getCombinedQuery();
                    runPlayTask(context, searchQuery);
                } catch (Exception e) {
                    // If there was an error with Spotify integration, inform the user and fallback.
                    context.sendMessage(Text.literal("Failed to fetch Spotify track details: " + e.getMessage()).toString());
                    runPlayTask(context, input);
                }
            });
        } else {
            // Otherwise, process as usual. (Note: ModUtils.parseTrackId may already be doing some formatting.)
            final String query = ModUtils.parseTrackId(input);
            runPlayTask(context, query);
        }
        return 0;
    }

     //Schedules a task on the main thread to load the track using the provided query.
    private void runPlayTask(Player context, String query) {
        Bukkit.getScheduler().runTask(SimpleVoiceChatMusic.get(), () -> {
            ModUtils.CheckPlayerGroup result = checkPlayerGroup(context);
            if (result == null)
                return;
            GroupManager gm = MusicManager.getInstance().getGroup(result.group(), result.source().getServer());
            result.source().sendMessage(() -> Text.literal("Loading songs..."));
            MusicManager.getInstance().playerManager.loadItemOrdered(
                    gm.getPlayer(),
                    query,
                    new PlayLoadHandler(result.source(), gm)
            );
        });
    }
}
