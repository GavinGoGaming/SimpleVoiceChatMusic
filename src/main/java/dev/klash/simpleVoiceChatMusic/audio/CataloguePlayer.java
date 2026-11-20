package dev.klash.simpleVoiceChatMusic.audio;

import com.sedmelluq.discord.lavaplayer.track.AudioItem;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import dev.klash.simpleVoiceChatMusic.SimpleVoiceChatMusic;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 播放玩家收藏目录：把目录里的歌按顺序解析后入队。
 */
public final class CataloguePlayer {

    private static final Pattern NETEASE_ID = Pattern.compile("netease://(\\d+)");

    private CataloguePlayer() {
    }

    /**
     * 在异步线程里把目录中的歌曲依次入队（第一首会立即开始播放）。
     *
     * @return 成功入队的数量
     */
    public static int play(GroupManager group, List<PlayerCatalogue.Entry> entries) {
        if (group == null || entries.isEmpty()) {
            return 0;
        }

        int queued = 0;

        for (PlayerCatalogue.Entry entry : entries) {
            try {
                String query = resolve(entry.identifier());

                if (query == null || query.isBlank()) {
                    continue;
                }

                AudioItem item = MusicManager.getInstance().playerManager.loadItemSync(query);

                if (item instanceof AudioTrack track) {
                    if (group.enqueueSong(track)) {
                        queued++;
                    }
                } else if (item instanceof AudioPlaylist playlist && !playlist.getTracks().isEmpty()) {
                    AudioTrack track = playlist.getTracks().get(0);

                    if (group.enqueueSong(track)) {
                        queued++;
                    }
                }
            } catch (Exception e) {
                SimpleVoiceChatMusic.LOGGER.warning("Could not queue catalogue entry "
                        + entry.identifier() + ": " + e.getMessage());
            }
        }

        return queued;
    }

    /**
     * 把收藏里的标识转换成 lavaplayer 能加载的东西。
     */
    private static String resolve(String identifier) throws Exception {
        if (identifier == null || identifier.isBlank()) {
            return null;
        }

        if (identifier.startsWith("bilibili://")) {
            BilibiliApi.Audio audio = BilibiliApi.resolveFromInput(identifier);
            return audio == null ? null : audio.url();
        }

        Matcher matcher = NETEASE_ID.matcher(identifier);

        if (matcher.find()) {
            long songId = Long.parseLong(matcher.group(1));
            NetEaseApi.Song song = NetEaseApi.metaFor(identifier);

            String url = NetEaseApi.songUrl(songId);

            if (url != null && song != null) {
                NetEaseApi.remember(url, song);
            }

            return url;
        }

        return identifier;
    }
}
