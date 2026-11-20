package dev.klash.simpleVoiceChatMusic.audio;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.player.event.AudioEventAdapter;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason;
import dev.klash.simpleVoiceChatMusic.SimpleVoiceChatMusic;
import dev.klash.simpleVoiceChatMusic.i18n.Ph;
import dev.klash.simpleVoiceChatMusic.util.Errors;
import dev.klash.simpleVoiceChatMusic.util.ModUtils;

import java.util.logging.Level;

public class TrackScheduler extends AudioEventAdapter {
    private final GroupManager group;

    TrackScheduler(GroupManager groupManager) {
        super();
        this.group = groupManager;
    }

    @Override
    public void onTrackStart(AudioPlayer player, AudioTrack track) {
        // 循环重播不重复播报
        if (this.group.consumeLoopReplay()) {
            return;
        }

        this.group.rememberTrack(track);
        this.group.broadcast("track.now-playing", Ph.comp("track", ModUtils.trackInfo(track.getInfo())));
    }

    @Override
    public void onTrackEnd(AudioPlayer player, AudioTrack track, AudioTrackEndReason endReason) {
        // only start next if applicable
        if (endReason == null || !endReason.mayStartNext) {
            return;
        }

        GroupSettingsManager.LoopMode loop = this.group.loopMode();

        // 单曲循环：重播当前歌曲
        if (loop == GroupSettingsManager.LoopMode.ONE) {
            this.group.replay(track);
            return;
        }

        // 列表循环：队列空了就按顺序重播本次播放过的歌曲
        if (loop == GroupSettingsManager.LoopMode.ALL && this.group.getQueue().isEmpty()) {
            if (!this.group.replayHistory()) {
                this.group.replay(track);
            }
            return;
        }

        this.group.nextTrack();
    }

    @Override
    public void onTrackException(AudioPlayer player, AudioTrack track, FriendlyException exception) {
        if (this.group.isClosed()) {
            return;
        }

        String title = track == null ? "unknown track" : track.getInfo().title;
        String reason = exception == null ? "unknown error" : String.valueOf(exception.getMessage());

        // 网络类错误：一行日志 + 一句人话，不打印整条栈
        String networkKey = Errors.playerMessageKey(exception);
        if (networkKey != null) {
            SimpleVoiceChatMusic.LOGGER.warning("Failed to play " + title + ": " + reason);
            this.group.broadcast(networkKey);
            this.group.nextTrack();
            return;
        }

        if (exception != null && exception.severity == FriendlyException.Severity.COMMON) {
            // 注意：java.util.logging 不支持 {} 占位符，异常必须作为 throwable 传入才会打印栈
            SimpleVoiceChatMusic.LOGGER.log(Level.WARNING,
                    "Failed to play " + title + " due to error: " + reason);
            this.group.broadcast("track.failed", Ph.raw("reason", reason));
        } else {
            SimpleVoiceChatMusic.LOGGER.log(Level.SEVERE,
                    "Failed to play " + title + " due to an internal error: " + reason, exception);
            this.group.broadcast("track.internal-error");
        }

        this.group.nextTrack();
    }

    @Override
    public void onTrackStuck(AudioPlayer player, AudioTrack track, long thresholdMs) {
        this.group.broadcast("track.stuck");
        this.group.nextTrack();
    }
}
