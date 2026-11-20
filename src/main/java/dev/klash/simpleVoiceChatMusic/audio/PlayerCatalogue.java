package dev.klash.simpleVoiceChatMusic.audio;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.klash.simpleVoiceChatMusic.SimpleVoiceChatMusic;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 每个玩家的歌曲收藏目录（歌单）。
 *
 * <p>默认目录固定为 {@code favorites}（显示为"我的喜欢"），不可删除；
 * 其余目录由玩家自行创建/删除。数据保存在
 * {@code plugins/PlayerMusicCatalogue/<uuid>.json}。</p>
 */
public final class PlayerCatalogue {

    /** 默认目录的键（显示名走语言文件） */
    public static final String FAVORITES = "favorites";

    public record Entry(String identifier, String title, String author, long addedAt) {
        public String displayName() {
            if (author == null || author.isBlank()) {
                return title;
            }
            return author + " - " + title;
        }
    }

    public enum AddResult {
        ADDED,
        DUPLICATE,
        UNKNOWN_CATALOGUE
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final Map<UUID, LinkedHashMap<String, List<Entry>>> CACHE = new ConcurrentHashMap<>();

    private PlayerCatalogue() {
    }

    @Nullable
    public static File folder() {
        try {
            return new File(SimpleVoiceChatMusic.get().getDataFolder(), "PlayerMusicCatalogue");
        } catch (Throwable t) {
            return null; // 独立运行（测试）时没有插件实例
        }
    }

    @Nullable
    public static File fileFor(UUID uuid) {
        File folder = folder();
        return folder == null ? null : new File(folder, uuid + ".json");
    }

    // ------------------------------------------------------------------ read

    /** 所有目录（含默认目录），保持创建顺序 */
    public static Map<String, List<Entry>> catalogues(UUID uuid) {
        return new LinkedHashMap<>(data(uuid));
    }

    public static List<Entry> entries(UUID uuid, String catalogue) {
        List<Entry> list = data(uuid).get(catalogue);
        return list == null ? List.of() : List.copyOf(list);
    }

    public static boolean exists(UUID uuid, String catalogue) {
        return data(uuid).containsKey(catalogue);
    }

    public static boolean isFavorites(String catalogue) {
        return FAVORITES.equals(catalogue);
    }

    // ----------------------------------------------------------------- write

    /** 新建目录；名字非法/重复返回 false */
    public static boolean create(UUID uuid, String rawName) {
        String name = normalizeName(rawName);

        if (name.isEmpty() || name.length() > 32) {
            return false;
        }

        LinkedHashMap<String, List<Entry>> map = data(uuid);

        if (FAVORITES.equals(name) || map.containsKey(name)) {
            return false;
        }

        map.put(name, new ArrayList<>());
        save(uuid);
        return true;
    }

    /** 删除目录；默认目录不可删 */
    public static boolean delete(UUID uuid, String catalogue) {
        if (isFavorites(catalogue)) {
            return false;
        }

        LinkedHashMap<String, List<Entry>> map = data(uuid);

        if (map.remove(catalogue) == null) {
            return false;
        }

        save(uuid);
        return true;
    }

    public static AddResult add(UUID uuid, String catalogue, Entry entry) {
        LinkedHashMap<String, List<Entry>> map = data(uuid);
        List<Entry> list = map.get(catalogue);

        if (list == null) {
            return AddResult.UNKNOWN_CATALOGUE;
        }

        for (Entry existing : list) {
            if (existing.identifier().equals(entry.identifier())) {
                return AddResult.DUPLICATE;
            }
        }

        list.add(entry);
        save(uuid);
        return AddResult.ADDED;
    }

    public static boolean remove(UUID uuid, String catalogue, int index) {
        LinkedHashMap<String, List<Entry>> map = data(uuid);
        List<Entry> list = map.get(catalogue);

        if (list == null || index < 0 || index >= list.size()) {
            return false;
        }

        list.remove(index);
        save(uuid);
        return true;
    }

    // ---------------------------------------------------------------- storage

    private static LinkedHashMap<String, List<Entry>> data(UUID uuid) {
        return CACHE.computeIfAbsent(uuid, PlayerCatalogue::load);
    }

    private static LinkedHashMap<String, List<Entry>> load(UUID uuid) {
        LinkedHashMap<String, List<Entry>> map = new LinkedHashMap<>();
        map.put(FAVORITES, new ArrayList<>());

        File file = fileFor(uuid);

        if (file == null || !file.isFile()) {
            return map;
        }

        try {
            String json = Files.readString(file.toPath(), StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            JsonObject catalogues = root.has("catalogues") && root.get("catalogues").isJsonObject()
                    ? root.getAsJsonObject("catalogues")
                    : new JsonObject();

            for (Map.Entry<String, JsonElement> entry : catalogues.entrySet()) {
                if (!entry.getValue().isJsonArray()) {
                    continue;
                }

                String name = entry.getKey();
                List<Entry> list = new ArrayList<>();

                for (JsonElement element : entry.getValue().getAsJsonArray()) {
                    if (!element.isJsonObject()) {
                        continue;
                    }

                    JsonObject object = element.getAsJsonObject();
                    String identifier = stringOf(object, "identifier");
                    String title = stringOf(object, "title");

                    if (identifier.isEmpty() || title.isEmpty()) {
                        continue;
                    }

                    list.add(new Entry(identifier, title, stringOf(object, "author"),
                            numberOf(object, "addedAt")));
                }

                map.put(name, list);
            }

            map.putIfAbsent(FAVORITES, new ArrayList<>());
        } catch (Exception e) {
            SimpleVoiceChatMusic.LOGGER.warning("Could not load catalogue for " + uuid + ": " + e.getMessage());
        }

        return map;
    }

    private static void save(UUID uuid) {
        try {
            File folder = folder();

            if (folder == null) {
                return;
            }

            if (!folder.isDirectory() && !folder.mkdirs()) {
                SimpleVoiceChatMusic.LOGGER.warning("Could not create the PlayerMusicCatalogue folder.");
                return;
            }

            JsonObject root = new JsonObject();
            JsonObject catalogues = new JsonObject();

            data(uuid).forEach((name, list) -> {
                JsonArray array = new JsonArray();

                for (Entry entry : list) {
                    JsonObject object = new JsonObject();
                    object.addProperty("identifier", entry.identifier());
                    object.addProperty("title", entry.title());
                    object.addProperty("author", entry.author());
                    object.addProperty("addedAt", entry.addedAt());
                    array.add(object);
                }

                catalogues.add(name, array);
            });

            root.add("catalogues", catalogues);
            File file = fileFor(uuid);

            if (file != null) {
                Files.writeString(file.toPath(), GSON.toJson(root), StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            SimpleVoiceChatMusic.LOGGER.warning("Could not save catalogue for " + uuid + ": " + e.getMessage());
        }
    }

    /** 目录名去掉首尾空白，长度截断到 32 */
    public static String normalizeName(@Nullable String raw) {
        if (raw == null) {
            return "";
        }

        String name = raw.trim().replaceAll("\\s+", "_");

        if (name.length() > 32) {
            name = name.substring(0, 32);
        }

        return name;
    }

    /** 供命令解析：把玩家输入的目录名映射到实际存储键（favorites 别名） */
    public static String resolveName(UUID uuid, String input) {
        String name = normalizeName(input);

        if (name.isEmpty()) {
            return "";
        }

        String lower = name.toLowerCase(Locale.ROOT);

        if (lower.equals("favorites") || lower.equals("favourite") || lower.equals("fav")
                || name.equals("我的喜欢") || name.equals("喜欢")) {
            return FAVORITES;
        }

        for (String key : data(uuid).keySet()) {
            if (key.equalsIgnoreCase(name)) {
                return key;
            }
        }

        return name;
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

    private static long numberOf(JsonObject object, String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
            return 0L;
        }
        try {
            return object.get(key).getAsLong();
        } catch (Exception e) {
            return 0L;
        }
    }
}
