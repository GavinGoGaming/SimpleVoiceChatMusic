package dev.klash.simpleVoiceChatMusic.audio;

import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public class PlayLoadHandler extends SearchLoadHandler {
    public PlayLoadHandler(@Nullable Player source, GroupManager group, @Nullable String query) {
        super(source, group, query, 0);
    }

    public PlayLoadHandler(@Nullable Player source, GroupManager group, @Nullable String query, int attempt) {
        super(source, group, query, attempt);
    }

    public PlayLoadHandler(@Nullable Player source, GroupManager group, @Nullable String query, boolean silent) {
        super(source, group, query, 0, silent);
    }

    @Override
    public void playlistLoaded(AudioPlaylist playlist) {
        List<AudioTrack> tracks = playlist == null ? null : playlist.getTracks();

        if (tracks == null || tracks.isEmpty()) {
            this.noMatches();
            return;
        }

        super.trackLoaded(tracks.get(0));
    }
}
