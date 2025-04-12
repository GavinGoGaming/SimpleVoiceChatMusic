package dev.klash.simpleVoiceChatMusic.audio;

import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import dev.klash.simpleVoiceChatMusic.SimpleVoiceChatMusic;
import dev.klash.simpleVoiceChatMusic.util.ModUtils;
import dev.klash.simpleVoiceChatMusic.util.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.Style;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Objects;
import java.util.logging.Level;

public class SearchLoadHandler implements AudioLoadResultHandler {

    protected final Player source;
    protected final GroupManager group;

    public SearchLoadHandler(Player source, GroupManager group) {
        this.source = source;
        this.group = group;
    }

    @Override
    public void trackLoaded(AudioTrack track) {
        group.enqueueSong(track);

        if (source != null) {
            this.group.broadcast(
                    Text.literal(Objects.requireNonNull(source).getName())
                            .append(Text.literal(" queued "))
                            .append(ModUtils.trackInfo(track.getInfo(), true))
            );
        }
    }

    @Override
    public void playlistLoaded(AudioPlaylist playlist) {
        // if over 10, trim
        List<AudioTrack> loaded = playlist.getTracks().subList(0, 5);

        if (source != null) {
            // get all titles and create one large string
            Component text = Text.literal("Found " + loaded.size() + " results: \n");

            for (AudioTrack track : loaded) {
                 text = text.append(Text.literal("  - "))
                    .append(ModUtils.trackInfo(track.getInfo(), true))
                    .append(Text.literal("\n"))
                    .append(Text.literal("    "))
                    .append(Text.literal("[Click to add to queue]").style(
                        Style.empty().clickEvent(ClickEvent.runCommand("/music play \"" + track.getIdentifier() + "\""))
                    ))
                    .append(Text.literal("\n\n"));
            }

            source.sendMessage(text);
        }
    }

    @Override
    public void noMatches() {
        if (source != null) {
            source.sendMessage(() -> Text.literal("No matches found!"));
        }
    }

    @Override
    public void loadFailed(FriendlyException exception) {
        if (!exception.severity.equals(FriendlyException.Severity.COMMON)) {
            SimpleVoiceChatMusic.LOGGER.log(Level.WARNING, "Failed to load track from query", exception);
        }

        if (source != null) {
            source.sendMessage(() -> Text.literal(exception.severity == FriendlyException.Severity.COMMON ? "Failed to load track: " + exception.getMessage() : "Track failed to load! Check server logs for more information"));
        }
    }
}
