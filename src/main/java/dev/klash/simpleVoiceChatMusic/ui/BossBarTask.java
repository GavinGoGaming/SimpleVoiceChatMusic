package dev.klash.simpleVoiceChatMusic.ui;

import dev.klash.simpleVoiceChatMusic.SimpleVoiceChatMusic;
import dev.klash.simpleVoiceChatMusic.audio.GroupManager;
import dev.klash.simpleVoiceChatMusic.audio.MusicManager;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;

/**
 * 全局的 BOSS 栏刷新任务。
 *
 * <p>每 {@code bossbar.update-interval-ticks} 个 tick 在主线程跑一次，遍历所有语音组，
 * 让每个组的 {@link GroupManager#tickBossBar()} 更新进度和文字。
 * 放在主线程是因为 Adventure 的 {@code BossBar} 会直接给玩家发包，不属于线程安全操作。</p>
 */
public final class BossBarTask {

    private BukkitTask task;

    public void start(SimpleVoiceChatMusic plugin, int intervalTicks) {
        stop();

        int period = Math.max(5, intervalTicks);
        this.task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (GroupManager group : MusicManager.getInstance().groups()) {
                try {
                    group.tickBossBar();
                } catch (Exception e) {
                    SimpleVoiceChatMusic.LOGGER.warning("Failed to update a boss bar: " + e.getMessage());
                }
            }
        }, period, period);
    }

    public void stop() {
        if (this.task != null) {
            this.task.cancel();
            this.task = null;
        }
    }
}
