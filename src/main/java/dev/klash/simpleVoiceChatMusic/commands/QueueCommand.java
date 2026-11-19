package dev.klash.simpleVoiceChatMusic.commands;

import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import dev.klash.simpleVoiceChatMusic.audio.GroupManager;
import dev.klash.simpleVoiceChatMusic.audio.MusicManager;
import dev.klash.simpleVoiceChatMusic.i18n.Messages;
import dev.klash.simpleVoiceChatMusic.i18n.Ph;
import dev.klash.simpleVoiceChatMusic.util.ModUtils;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

import java.util.concurrent.BlockingQueue;

import static dev.klash.simpleVoiceChatMusic.util.ModUtils.checkPlayerGroup;

public class QueueCommand implements Command {

    @Override
    public int execute(Player context, String[] args) {
        ModUtils.CheckPlayerGroup result = checkPlayerGroup(context);
        if (result == null) {
            return 1;
        }

        GroupManager gm = MusicManager.getInstance().getGroup(result.group(), result.source().getServer());

        AudioTrack currentTrack = gm.getPlayer().getPlayingTrack();
        BlockingQueue<AudioTrack> tracks = gm.getQueue();
        AudioTrack[] tracksArr = tracks.toArray(AudioTrack[]::new);

        if (currentTrack == null && tracksArr.length == 0) {
            Messages.send(result.source(), "queue.empty");
            return 0;
        }

        // Adventure 的 Component 是不可变的，append() 返回新对象，必须接住返回值
        Component text = Component.empty();

        if (currentTrack != null) {
            // 第一行带前缀
            text = text.append(Messages.render(result.source(), "queue.current",
                    Ph.comp("track", ModUtils.trackInfo(currentTrack.getInfo()))));
        }

        for (int i = 0; i < tracksArr.length; i++) {
            if (i > 0 || currentTrack != null) {
                text = text.append(Component.newline());
            }

            // 只有整条消息的第一行才需要前缀
            boolean firstLine = currentTrack == null && i == 0;
            Component line = firstLine
                    ? Messages.render(result.source(), "queue.entry",
                        Ph.num("index", i + 1),
                        Ph.comp("track", ModUtils.trackInfo(tracksArr[i].getInfo())))
                    : Messages.component(result.source(), "queue.entry",
                        Ph.num("index", i + 1),
                        Ph.comp("track", ModUtils.trackInfo(tracksArr[i].getInfo())));

            text = text.append(line);
        }

        result.source().sendMessage(text);
        return 0;
    }
}
