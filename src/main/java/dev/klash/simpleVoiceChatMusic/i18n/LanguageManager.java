package dev.klash.simpleVoiceChatMusic.i18n;

import dev.klash.simpleVoiceChatMusic.SimpleVoiceChatMusic;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.Nullable;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.logging.Level;

/**
 * 语言文件管理器。
 *
 * <p>语言文件位于 jar 内的 {@code lang/<code>.yml}（例如 {@code lang/en_us.yml}、{@code lang/zh_cn.yml}），
 * 使用 MiniMessage 语法，支持嵌套的 YAML 结构（会被展开成 {@code general.usage} 这样的点分键）。</p>
 *
 * <p>语言选择顺序：玩家的 {@code /music lang} 手动选择 → 客户端语言（若开启） → config.yml 的默认语言 →
 * en_us → 直接返回 key（便于发现漏翻译）。</p>
 */
public final class LanguageManager {

    public static final String FALLBACK_LANGUAGE = "en_us";

    private final JavaPlugin plugin;

    /** code -> (key -> MiniMessage 文本) */
    private final Map<String, Map<String, String>> languages = new ConcurrentHashMap<>();
    private final List<String> available = Collections.synchronizedList(new ArrayList<>());
    /** 玩家手动选择的语言（仅本次会话有效） */
    private final Map<UUID, String> overrides = new ConcurrentHashMap<>();

    private volatile String fallback = FALLBACK_LANGUAGE;
    private volatile boolean useClientLocale = true;

    public LanguageManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * 重新扫描并加载所有语言文件。
     */
    public void reload(String defaultLanguage, boolean useClientLocale) {
        this.fallback = normalize(defaultLanguage);
        this.useClientLocale = useClientLocale;

        this.languages.clear();
        this.available.clear();

        discoverAndLoad();

        if (this.languages.isEmpty()) {
            log(Level.WARNING, "No language files were loaded, using the message keys as-is.");
            return;
        }

        Collections.sort(this.available);

        if (!this.languages.containsKey(this.fallback)) {
            log(Level.WARNING, "Language '" + this.fallback + "' is missing, falling back to " + FALLBACK_LANGUAGE);
        }

        log(Level.INFO, "Loaded " + this.languages.size() + " language(s): " + String.join(", ", this.available));
    }

    private void discoverAndLoad() {
        File jarFile = locatePluginJar();

        if (jarFile != null && jarFile.isFile()) {
            try (JarFile jar = new JarFile(jarFile)) {
                Enumeration<JarEntry> entries = jar.entries();
                while (entries.hasMoreElements()) {
                    String name = entries.nextElement().getName();
                    if (!name.startsWith("lang/") || !name.endsWith(".yml")) {
                        continue;
                    }

                    String code = name.substring("lang/".length(), name.length() - ".yml".length());
                    if (code.indexOf('/') >= 0) {
                        continue;
                    }

                    load(code);
                }
                return;
            } catch (Exception e) {
                log(Level.WARNING, "Could not scan the plugin jar for languages: " + e.getMessage());
            }
        }

        // 开发环境（直接跑 classes 目录）或扫描失败：尝试已知的内置语言
        for (String code : new String[]{FALLBACK_LANGUAGE, "zh_cn"}) {
            load(code);
        }
    }

    @Nullable
    private File locatePluginJar() {
        try {
            URL url = this.plugin.getClass().getProtectionDomain().getCodeSource().getLocation();
            return new File(url.toURI());
        } catch (Exception e) {
            return null;
        }
    }

    private void load(String code) {
        String normalized = normalize(code);
        String path = "lang/" + normalized + ".yml";

        try (InputStream in = this.plugin.getResource(path)) {
            if (in == null) {
                return;
            }

            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(in, StandardCharsets.UTF_8));

            Map<String, String> messages = new HashMap<>();
            for (String key : yaml.getKeys(true)) {
                if (yaml.isString(key)) {
                    messages.put(key, yaml.getString(key, ""));
                }
            }

            if (messages.isEmpty()) {
                return;
            }

            this.languages.put(normalized, messages);
            if (!this.available.contains(normalized)) {
                this.available.add(normalized);
            }
        } catch (Exception e) {
            log(Level.WARNING, "Failed to load language file " + path + ": " + e.getMessage());
        }
    }

    /**
     * @return 已加载的语言代码（排序后）
     */
    public List<String> available() {
        synchronized (this.available) {
            return List.copyOf(this.available);
        }
    }

    public boolean useClientLocale() {
        return this.useClientLocale;
    }

    /**
     * 手动设置某个玩家的语言，{@code null} 表示恢复自动。
     */
    public void setOverride(UUID playerId, @Nullable String languageCode) {
        if (languageCode == null) {
            this.overrides.remove(playerId);
        } else {
            this.overrides.put(playerId, normalize(languageCode));
        }
    }

    @Nullable
    public String overrideOf(UUID playerId) {
        return this.overrides.get(playerId);
    }

    /**
     * 解析该接收者最终使用的语言代码。
     */
    public String resolve(@Nullable CommandSender viewer) {
        if (viewer instanceof Player player) {
            String override = this.overrides.get(player.getUniqueId());
            if (override != null && this.languages.containsKey(override)) {
                return override;
            }

            if (this.useClientLocale) {
                Locale locale = player.locale();
                if (locale != null) {
                    String full = normalize(locale.toString());
                    if (this.languages.containsKey(full)) {
                        return full;
                    }

                    String language = normalize(locale.getLanguage());
                    if (this.languages.containsKey(language)) {
                        return language;
                    }
                }
            }
        }

        if (this.languages.containsKey(this.fallback)) {
            return this.fallback;
        }

        synchronized (this.available) {
            return this.available.isEmpty() ? FALLBACK_LANGUAGE : this.available.get(0);
        }
    }

    /**
     * 取原始 MiniMessage 文本（找不到时返回 key 本身）。
     */
    public String raw(@Nullable CommandSender viewer, String key) {
        String code = resolve(viewer);

        String value = lookup(code, key);
        if (value == null) {
            value = lookup(this.fallback, key);
        }
        if (value == null) {
            value = lookup(FALLBACK_LANGUAGE, key);
        }

        return value == null ? key : value;
    }

    @Nullable
    private String lookup(String code, String key) {
        Map<String, String> messages = this.languages.get(code);
        return messages == null ? null : messages.get(key);
    }

    public static String normalize(@Nullable String code) {
        if (code == null) {
            return FALLBACK_LANGUAGE;
        }
        return code.trim().toLowerCase(Locale.ROOT).replace('-', '_');
    }

    private static void log(Level level, String message) {
        if (SimpleVoiceChatMusic.LOGGER != null) {
            SimpleVoiceChatMusic.LOGGER.log(level, message);
        }
    }
}
