package dev.klash.simpleVoiceChatMusic.commands;

import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import dev.klash.simpleVoiceChatMusic.audio.GroupManager;
import dev.klash.simpleVoiceChatMusic.audio.GroupSettingsManager;
import dev.klash.simpleVoiceChatMusic.audio.MusicManager;
import dev.klash.simpleVoiceChatMusic.i18n.Messages;
import dev.klash.simpleVoiceChatMusic.i18n.Ph;
import dev.klash.simpleVoiceChatMusic.util.ModUtils;
import org.bukkit.entity.Player;

import static dev.klash.simpleVoiceChatMusic.util.ModUtils.checkPlayerGroup;

public class NowPlayingCommand implements Command {

    @Override
    public int execute(Player context, String[] args) {
        ModUtils.CheckPlayerGroup result = checkPlayerGroup(context);
        if (result == null) {
            return 1;
        }

        GroupManager gm = MusicManager.getInstance().getGroup(result.group(), result.source().getServer());
        AudioTrack track = gm.getPlayer().getPlayingTrack();
        GroupSettingsManager settings = gm.getSettingsStore();

        if (track == null) {
            Messages.send(result.source(), "now-playing.nothing");
            return 0;
        }

        Messages.send(result.source(), "now-playing.line",
                Ph.comp("track", ModUtils.trackInfo(track.getInfo())),
                Ph.raw("position", ModUtils.formatMMSS(track.getPosition())),
                Ph.raw("duration", ModUtils.formatMMSS(track.getDuration())),
                Ph.num("volume", settings.volume),
                Ph.raw("bass", formatBass(settings.bassboost)));

        return 0;
    }

    private static String formatBass(float bassboost) {
        if (Math.abs(bassboost - Math.round(bassboost)) < 0.001f) {
            return Integer.toString(Math.round(bassboost));
        }
        return String.format("%.1f", bassboost);
    }
}
