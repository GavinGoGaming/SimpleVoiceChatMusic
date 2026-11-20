package dev.klash.simpleVoiceChatMusic.audio;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.klash.simpleVoiceChatMusic.SimpleVoiceChatMusic;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * B 站登录凭证存储（与 {@link NetEaseLogin} 同一套方式）。
 *
 * <p>{@code /music login bilibili} 扫码成功后把 Cookie 写进
 * {@code plugins/SimpleVoiceChatMusic/bilibililogin.json}，插件启动时自动加载。
 * 该文件等同于账号凭据，不要发给任何人。</p>
 */
public final class BilibiliLogin {

    public record Account(String cookie, String nickname, long savedAt) {
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    @Nullable
    private static volatile Account account;

    private BilibiliLogin() {
    }

    public static File file() {
        return new File(SimpleVoiceChatMusic.get().getDataFolder(), "bilibililogin.json");
    }

    public static void load() {
        File file = file();

        if (!file.isFile()) {
            return;
        }

        try {
            String json = Files.readString(file.toPath(), StandardCharsets.UTF_8);
            Account loaded = GSON.fromJson(json, Account.class);

            if (loaded != null && loaded.cookie() != null && loaded.cookie().contains("SESSDATA=")) {
                account = loaded;
                BilibiliApi.setCookie(loaded.cookie());
                SimpleVoiceChatMusic.LOGGER.info("Loaded Bilibili login for " + displayName() + ".");
            }
        } catch (Exception e) {
            SimpleVoiceChatMusic.LOGGER.warning("Could not load Bilibili login: " + e.getMessage());
        }
    }

    public static void save(String cookie, @Nullable String nickname) {
        Account newAccount = new Account(cookie, nickname == null ? "" : nickname, System.currentTimeMillis());
        account = newAccount;
        BilibiliApi.setCookie(cookie);

        try {
            File file = file();
            if (file.getParentFile() != null && !file.getParentFile().isDirectory()
                    && !file.getParentFile().mkdirs()) {
                SimpleVoiceChatMusic.LOGGER.warning("Could not create the plugin data folder for Bilibili login.");
            }
            Files.writeString(file.toPath(), GSON.toJson(newAccount), StandardCharsets.UTF_8);
            SimpleVoiceChatMusic.LOGGER.info("Saved Bilibili login"
                    + (newAccount.nickname().isBlank() ? "" : " for " + newAccount.nickname())
                    + " to " + file.getName() + ".");
        } catch (IOException e) {
            SimpleVoiceChatMusic.LOGGER.warning("Could not save Bilibili login: " + e.getMessage());
        }
    }

    public static void clear() {
        account = null;
        BilibiliApi.setCookie("");

        try {
            Files.deleteIfExists(file().toPath());
        } catch (IOException e) {
            SimpleVoiceChatMusic.LOGGER.warning("Could not delete the Bilibili login file: " + e.getMessage());
        }
    }

    public static boolean isLoggedIn() {
        return account != null && BilibiliApi.isLoggedIn();
    }

    public static String displayName() {
        Account current = account;
        if (current == null || current.nickname().isBlank()) {
            return "B 站账号";
        }
        return current.nickname();
    }
}
