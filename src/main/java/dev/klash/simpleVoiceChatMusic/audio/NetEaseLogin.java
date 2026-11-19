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
 * 网易云登录凭证存储。
 *
 * <p>{@code /music login 网易云} 扫码成功后把 Cookie 写进
 * {@code plugins/SimpleVoiceChatMusic/neteaselogin.json}，插件启动时自动加载。
 * 该文件等同于账号凭据，不要发给任何人。</p>
 */
public final class NetEaseLogin {

    public record Account(String cookie, String nickname, long savedAt) {
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    @Nullable
    private static volatile Account account;

    private NetEaseLogin() {
    }

    public static File file() {
        return new File(SimpleVoiceChatMusic.get().getDataFolder(), "neteaselogin.json");
    }

    /**
     * 启动时调用：加载已保存的凭证并注入 NetEaseApi。
     */
    public static void load() {
        File file = file();

        if (!file.isFile()) {
            return;
        }

        try {
            String json = Files.readString(file.toPath(), StandardCharsets.UTF_8);
            Account loaded = GSON.fromJson(json, Account.class);

            if (loaded != null && loaded.cookie() != null && loaded.cookie().contains("MUSIC_U=")) {
                account = loaded;
                NetEaseApi.setCookie(loaded.cookie());
                SimpleVoiceChatMusic.LOGGER.info("Loaded NetEase login for " + displayName() + ".");
            }
        } catch (Exception e) {
            SimpleVoiceChatMusic.LOGGER.warning("Could not load NetEase login: " + e.getMessage());
        }
    }

    public static void save(String cookie, @Nullable String nickname) {
        Account newAccount = new Account(cookie, nickname == null ? "" : nickname, System.currentTimeMillis());
        account = newAccount;
        NetEaseApi.setCookie(cookie);

        try {
            File file = file();
            if (file.getParentFile() != null && !file.getParentFile().isDirectory() && !file.getParentFile().mkdirs()) {
                SimpleVoiceChatMusic.LOGGER.warning("Could not create the plugin data folder for NetEase login.");
            }
            Files.writeString(file.toPath(), GSON.toJson(newAccount), StandardCharsets.UTF_8);
            SimpleVoiceChatMusic.LOGGER.info("Saved NetEase login"
                    + (newAccount.nickname().isBlank() ? "" : " for " + newAccount.nickname())
                    + " to " + file.getName() + ".");
        } catch (IOException e) {
            SimpleVoiceChatMusic.LOGGER.warning("Could not save NetEase login: " + e.getMessage());
        }
    }

    public static void clear() {
        account = null;
        NetEaseApi.setCookie("");

        try {
            Files.deleteIfExists(file().toPath());
        } catch (IOException e) {
            SimpleVoiceChatMusic.LOGGER.warning("Could not delete the NetEase login file: " + e.getMessage());
        }
    }

    public static boolean isLoggedIn() {
        return account != null && NetEaseApi.isLoggedIn();
    }

    public static String displayName() {
        Account current = account;
        if (current == null || current.nickname().isBlank()) {
            return "网易云账号";
        }
        return current.nickname();
    }
}
