package dev.klash.simpleVoiceChatMusic.util;

import com.mojang.brigadier.context.CommandContext;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import de.maxhenkel.voicechat.api.Group;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import dev.klash.simpleVoiceChatMusic.VoiceChatPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.Style;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.concurrent.TimeUnit;

public class ModUtils {

    public static Component hyperlink(String string, String url) {
        return Text.literal(string)
                .clickEvent(ClickEvent.openUrl(url));
    }

    public static Component trackInfo(AudioTrackInfo track) {
        return trackInfo(track, false);
    }

    public static Component trackInfo(AudioTrackInfo track, boolean longFormat) {
       Component text = Text.literal(track.title)
           .style(Style.style(NamedTextColor.AQUA)
                   .clickEvent(ClickEvent.openUrl(track.uri)))
           .append(Text.literal(" by ").style(Style.empty()))
           .append(Text.literal(track.author).style(
               Style.empty().color(NamedTextColor.AQUA))
           );

       // if long format, add more track data
       if (longFormat) {
           text.append(Text.literal(" [" + formatMMSS(track.length) + "]").style(Style.empty()));
       }

       return text;
    }

    public static String formatMMSS(long millis) {
        String seconds = Long.valueOf(TimeUnit.MILLISECONDS.toSeconds(millis) -
                    TimeUnit.MINUTES.toSeconds(TimeUnit.MILLISECONDS.toMinutes(millis))).toString();

        if (seconds.length() == 1) seconds = "0" + seconds;

        return String.format("%d:%s",
            TimeUnit.MILLISECONDS.toMinutes(millis),
            seconds
        );
    }

    public static String parseTrackId(String userInput) {
        if (userInput.startsWith("ytsearch:") || userInput.startsWith("ytmsearch:") || userInput.startsWith("scsearch:")) {
            return userInput;
        }


        // if starts with id:, parse ourselves
        if (userInput.startsWith("id:")) {
            return userInput.substring(3);
        }

        // try and parse as URL
        try {
            new URL(userInput);
        } catch (MalformedURLException e) {
            return "ytsearch:" + userInput;
        }

        return userInput;
    }

    public static CheckPlayerGroup checkPlayerGroup(Player source) {

        if (VoiceChatPlugin.voicechatServerApi == null) {
            source.sendMessage(
                () -> Text.literal("VoiceChat API connection has not been established yet! Please try again later.")
            );
            return null;
        }

        if (source == null) {
            source.sendMessage(
                () -> Text.literal("This command is player only!")
            );
            return null;
        }

        VoicechatConnection connection = VoiceChatPlugin.voicechatServerApi.getConnectionOf(source.getUniqueId());

        if (connection == null) {
            source.sendMessage(
                () -> Text.literal("You are not connected to voice chat!")
            );
            return null;
        }

        Group group = connection.getGroup();

        if (group == null) {
            source.sendMessage(
                () -> Text.literal("You're not in a group! Just use spotify smh..")
            );
            return null;
        }
        CheckPlayerGroup result = new CheckPlayerGroup(source, group);
        return result;
    }

    public record CheckPlayerGroup(Player source, Group group) {
    }
}
