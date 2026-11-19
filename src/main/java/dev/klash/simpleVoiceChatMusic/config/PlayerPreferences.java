package dev.klash.simpleVoiceChatMusic.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.klash.simpleVoiceChatMusic.SimpleVoiceChatMusic;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 每个玩家自己的偏好：
 * <ul>
 *   <li>默认搜索/播放音源（B 站 / 网易云 / YouTube / SoundCloud）</li>
 *   <li>是否隐藏 ActionBar 歌词</li>
 *   <li>是否隐藏 Boss 栏进度</li>
 * </ul>
 *
 * <p>持久化在 {@code plugins/SimpleVoiceChatMusic/player-preferences.json}。</p>
 */
public final class PlayerPreferences {

    public enum Source {
        BILIBILI("bilibili", "source.bilibili"),
        NETEASE("netease", "source.netease"),
        YOUTUBE("youtube", "source.youtube"),
        SOUNDCLOUD("soundcloud", "source.soundcloud"),
        ALL("all", "source.all");

        private final String id;
        private final String langKey;

        Source(String id, String langKey) {
            this.id = id;
            this.langKey = langKey;
        }

        public String id() {
            return id;
        }

        public String langKey() {
            return langKey;
        }

        /** 解析玩家输入，识别不了返回 null */
        @Nullable
        public static Source fromInput(@Nullable String raw) {
            if (raw == null) {
                return null;
            }

            String value = raw.trim().toLowerCase(Locale.ROOT);

            return switch (value) {
                case "bilibili", "b站", "哔哩哔哩", "bili" -> BILIBILI;
                case "netease", "网易云", "网易云音乐", "wyy" -> NETEASE;
                case "youtube", "yt", "油管" -> YOUTUBE;
                case "soundcloud", "sc" -> SOUNDCLOUD;
                case "all", "any", "全部", "全部音源" -> ALL;
                default -> null;
            };
        }
    }

    private record Pref(@Nullable Source source, boolean hideLyrics, boolean hideProgress) {
        static final Pref EMPTY = new Pref(null, false, false);
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final Map<UUID, Pref> CACHE = new ConcurrentHashMap<>();

    private PlayerPreferences() {
    }

    public static void load() {
        CACHE.clear();

        File file = file();
        if (file == null || !file.isFile()) {
            return;
        }

        try {
            String json = Files.readString(file.toPath(), StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();

            for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
                UUID uuid;

                try {
                    uuid = UUID.fromString(entry.getKey());
                } catch (IllegalArgumentException e) {
                    continue;
                }

                JsonElement value = entry.getValue();

                if (value.isJsonPrimitive()) {
                    // 旧格式：uuid -> "netease"
                    CACHE.put(uuid, new Pref(Source.fromInput(value.getAsString()), false, false));
                    continue;
                }

                if (!value.isJsonObject()) {
                    continue;
                }

                JsonObject object = value.getAsJsonObject();
                CACHE.put(uuid, new Pref(
                        Source.fromInput(stringOf(object, "source")),
                        boolOf(object, "hideLyrics"),
                        boolOf(object, "hideProgress")));
            }
        } catch (Exception e) {
            SimpleVoiceChatMusic.LOGGER.warning("Could not load player preferences: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ source

    /**
     * 玩家当前使用的音源；没设置过时用配置里的默认值。
     */
    public static Source sourceFor(Player player) {
        Pref pref = CACHE.get(player.getUniqueId());

        if (pref != null && pref.source() != null) {
            return pref.source();
        }

        Source fallback = Source.fromInput(PluginSettings.get().defaultSource());
        return fallback == null ? Source.BILIBILI : fallback;
    }

    public static void setSource(Player player, Source source) {
        update(player, pref -> new Pref(source, pref.hideLyrics(), pref.hideProgress()));
    }

    public static void clearSource(Player player) {
        update(player, pref -> new Pref(null, pref.hideLyrics(), pref.hideProgress()));
    }

    // ------------------------------------------------------------ action bar

    public static boolean hideLyrics(Player player) {
        Pref pref = CACHE.get(player.getUniqueId());
        return pref != null && pref.hideLyrics();
    }

    public static void setHideLyrics(Player player, boolean hidden) {
        update(player, pref -> new Pref(pref.source(), hidden, pref.hideProgress()));
    }

    // -------------------------------------------------------------- boss bar

    public static boolean hideProgress(Player player) {
        Pref pref = CACHE.get(player.getUniqueId());
        return pref != null && pref.hideProgress();
    }

    public static void setHideProgress(Player player, boolean hidden) {
        update(player, pref -> new Pref(pref.source(), pref.hideLyrics(), hidden));
    }

    // ---------------------------------------------------------------- storage

    private static void update(Player player, java.util.function.UnaryOperator<Pref> operator) {
        CACHE.compute(player.getUniqueId(),
                (uuid, current) -> operator.apply(current == null ? Pref.EMPTY : current));
        save();
    }

    private static void save() {
        File file = file();
        if (file == null) {
            return;
        }

        try {
            if (file.getParentFile() != null && !file.getParentFile().isDirectory()) {
                file.getParentFile().mkdirs();
            }

            JsonObject root = new JsonObject();

            CACHE.forEach((uuid, pref) -> {
                JsonObject object = new JsonObject();

                if (pref.source() != null) {
                    object.addProperty("source", pref.source().id());
                }

                object.addProperty("hideLyrics", pref.hideLyrics());
                object.addProperty("hideProgress", pref.hideProgress());

                root.add(uuid.toString(), object);
            });

            Files.writeString(file.toPath(), GSON.toJson(root), StandardCharsets.UTF_8);
        } catch (Exception e) {
            SimpleVoiceChatMusic.LOGGER.warning("Could not save player preferences: " + e.getMessage());
        }
    }

    @Nullable
    private static File file() {
        try {
            return new File(SimpleVoiceChatMusic.get().getDataFolder(), "player-preferences.json");
        } catch (Throwable t) {
            return null;
        }
    }

    private static String stringOf(JsonObject object, String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
            return "";
        }
        try {
            return object.get(key).getAsString();
        } catch (Exception e) {
            return "";
        }
    }

    private static boolean boolOf(JsonObject object, String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
            return false;
        }
        try {
            return object.get(key).getAsBoolean();
        } catch (Exception e) {
            return false;
        }
    }
}
