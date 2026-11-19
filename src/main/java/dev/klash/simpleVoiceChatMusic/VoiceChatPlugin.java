package dev.klash.simpleVoiceChatMusic;

import de.maxhenkel.voicechat.api.Group;
import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.VolumeCategory;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.VoicechatServerStartedEvent;
import dev.klash.simpleVoiceChatMusic.config.PluginSettings;
import dev.klash.simpleVoiceChatMusic.i18n.Messages;

public class VoiceChatPlugin implements VoicechatPlugin {

    public static final String MUSIC_CATEGORY = "streamed_music";

    public static VoicechatApi voicechatApi;
    public static volatile VoicechatServerApi voicechatServerApi;
    public static VolumeCategory musicVolumeCategory;

    @Override
    public String getPluginId() {
        return "simplevoice_paper_chat_music";
    }

    @Override
    public void initialize(VoicechatApi api) {
        SimpleVoiceChatMusic.LOGGER.info("Voicechat API initialized!");
        voicechatApi = api;
        SimpleVoiceChatMusic.vcRegistered = true;
    }

    @Override
    public void registerEvents(EventRegistration registration) {
        registration.registerEvent(VoicechatServerStartedEvent.class, this::onServerStart);
    }

    private void onServerStart(VoicechatServerStartedEvent event) {
        voicechatServerApi = event.getVoicechat();

        // 这个分类名会显示在 Simple Voice Chat 客户端的设置界面里，所以也走语言文件
        String name = Messages.plain(null, "category.name");
        String description = Messages.plain(null, "category.description");

        if (name.isBlank() || name.equals("category.name")) {
            name = "Music";
        }
        if (description.isBlank() || description.equals("category.description")) {
            description = "The volume of streamed music.";
        }

        VolumeCategory.Builder builder = voicechatServerApi.volumeCategoryBuilder()
                .setId(MUSIC_CATEGORY)
                .setName(name)
                .setDescription(description);

        // SVC 2.6+ 支持翻译键：客户端会用 nameTranslationKey 去本地语言文件里查，
        // 查不到再回退到上面的 name/description。用原版 soundCategory.music，
        // 这样音量界面里的分类名和提示会跟随玩家客户端语言（旧版 SVC 没有这两个方法，反射失败就跳过）。
        applyTranslationKey(builder, "setNameTranslationKey", "soundCategory.music");
        applyTranslationKey(builder, "setDescriptionTranslationKey", "soundCategory.music");

        musicVolumeCategory = builder.build();

        voicechatServerApi.registerVolumeCategory(musicVolumeCategory);

        createMusicGroup(voicechatServerApi);
    }

    /**
     * 服务器启动完成后自动创建一个音乐群组（Simple Voice Chat 的组）。
     * 已有同名群组（例如上一次启动创建、且 persistent=true 被保存下来）时不会重复创建。
     */
    private void createMusicGroup(VoicechatServerApi api) {
        PluginSettings settings = PluginSettings.get();

        if (!settings.autoGroupEnabled()) {
            return;
        }

        String name = settings.autoGroupName();

        if (name == null || name.isBlank()) {
            SimpleVoiceChatMusic.LOGGER.warning("music.auto-group.name is empty, skipping group creation.");
            return;
        }

        try {
            for (Group group : api.getGroups()) {
                if (group.getName() != null && group.getName().equalsIgnoreCase(name)) {
                    SimpleVoiceChatMusic.LOGGER.info("Music group '" + name + "' already exists ("
                            + group.getId() + "), not creating another one.");
                    return;
                }
            }

            String password = settings.autoGroupPassword();
            Group created = api.createGroup(
                    name,
                    password == null || password.isBlank() ? null : password,
                    settings.autoGroupPersistent());

            if (created == null) {
                SimpleVoiceChatMusic.LOGGER.warning("Could not create the music group '" + name + "'.");
                return;
            }

            SimpleVoiceChatMusic.LOGGER.info("Created music group '" + name + "' (id=" + created.getId()
                    + ", persistent=" + created.isPersistent()
                    + (created.hasPassword() ? ", password protected" : "") + ").");
        } catch (Throwable t) {
            SimpleVoiceChatMusic.LOGGER.warning("Failed to create the music group '" + name + "': " + t.getMessage());
        }
    }

    /**
     * 反射调用 VolumeCategory.Builder 的翻译键 setter（SVC 2.6+ 才有）。
     * 低版本 SVC 上找不到方法就静默跳过，行为与之前一致。
     */
    private static void applyTranslationKey(Object builder, String methodName, String translationKey) {
        Class<?>[] types = {builder.getClass(), VolumeCategory.Builder.class};

        for (Class<?> type : types) {
            try {
                java.lang.reflect.Method method = type.getMethod(methodName, String.class);
                method.invoke(builder, translationKey);
                return;
            } catch (NoSuchMethodException ignored) {
                // 这个类型上没有，试下一个
            } catch (ReflectiveOperationException e) {
                SimpleVoiceChatMusic.LOGGER.warning("Could not apply volume category translation key: " + e.getMessage());
                return;
            }
        }
    }
}
