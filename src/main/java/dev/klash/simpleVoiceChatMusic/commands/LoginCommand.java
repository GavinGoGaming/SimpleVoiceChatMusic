package dev.klash.simpleVoiceChatMusic.commands;

import dev.klash.simpleVoiceChatMusic.SimpleVoiceChatMusic;
import dev.klash.simpleVoiceChatMusic.audio.BilibiliApi;
import dev.klash.simpleVoiceChatMusic.audio.BilibiliLogin;
import dev.klash.simpleVoiceChatMusic.audio.NetEaseApi;
import dev.klash.simpleVoiceChatMusic.audio.NetEaseLogin;
import dev.klash.simpleVoiceChatMusic.i18n.Messages;
import dev.klash.simpleVoiceChatMusic.i18n.Ph;
import dev.klash.simpleVoiceChatMusic.ui.QrMapFactory;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 统一的账号登录（管理员）。
 *
 * <pre>
 * /music login                      查看所有账号状态
 * /music login netease              网易云：二维码地图 → 扫码 → 保存
 * /music login bilibili             B 站：二维码地图 → 扫码 → 保存
 * /music login &lt;平台&gt; status       查看某个平台
 * /music login &lt;平台&gt; logout       退出某个平台
 * /music login &lt;平台&gt; cookie &lt;值&gt; 用浏览器 Cookie 登录
 * </pre>
 *
 * <p>所有平台的流程完全一致：二维码地图 → 轮询 → 保存凭证；
 * 地图在流程结束时自动回收。</p>
 */
public class LoginCommand implements Command {

    private static final AtomicBoolean LOGIN_IN_PROGRESS = new AtomicBoolean(false);

    private static final int POLL_INTERVAL_MS = 2000;
    private static final int POLL_ATTEMPTS = 90; // 约 3 分钟

    private enum Platform {
        NETEASE("netease", "platform.netease"),
        BILIBILI("bilibili", "platform.bilibili");

        private final String id;
        private final String langKey;

        Platform(String id, String langKey) {
            this.id = id;
            this.langKey = langKey;
        }

        @Nullable
        static Platform parse(String raw) {
            String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);

            return switch (value) {
                case "netease", "网易云", "网易云音乐", "wyy" -> NETEASE;
                case "bilibili", "b站", "哔哩哔哩", "bili" -> BILIBILI;
                default -> null;
            };
        }
    }

    private record Poll(int code, boolean waiting, boolean scanned, boolean success, boolean expired,
                        @Nullable String cookie, @Nullable String nickname, @Nullable String detail) {
        boolean failed() {
            return !waiting && !scanned && !success && !expired;
        }
    }

    @Override
    public int execute(Player player, String[] args) {
        if (!player.hasPermission(SimpleVoiceChatMusic.PERMISSION_ADMIN)) {
            Messages.send(player, "general.no-permission");
            return 1;
        }

        if (args.length == 0 || args[0].equalsIgnoreCase("status")) {
            statusAll(player);
            return 0;
        }

        if (args[0].equalsIgnoreCase("logout")) {
            NetEaseLogin.clear();
            BilibiliLogin.clear();
            Messages.send(player, "login.logout-all");
            return 0;
        }

        Platform platform = Platform.parse(args[0]);

        if (platform == null) {
            Messages.send(player, "login.unknown-platform", Ph.raw("input", args[0]));
            return 1;
        }

        String sub = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "";

        return switch (sub) {
            case "status" -> {
                status(player, platform);
                yield 0;
            }
            case "logout" -> {
                logout(player, platform);
                yield 0;
            }
            case "cookie" -> cookieLogin(player, platform, args);
            case "" -> startQr(player, platform);
            default -> {
                Messages.send(player, "login.usage");
                yield 1;
            }
        };
    }

    // ------------------------------------------------------------------ status

    private void statusAll(Player player) {
        for (Platform platform : Platform.values()) {
            String name = platformName(player, platform);

            if (isLoggedIn(platform)) {
                Messages.send(player, "login.status-line",
                        Ph.raw("platform", name), Ph.raw("nickname", displayName(platform)));
            } else {
                Messages.send(player, "login.status-line-none", Ph.raw("platform", name));
            }
        }
    }

    private void status(Player player, Platform platform) {
        String name = platformName(player, platform);

        if (isLoggedIn(platform)) {
            Messages.send(player, "login.status-line",
                    Ph.raw("platform", name), Ph.raw("nickname", displayName(platform)));
        } else {
            Messages.send(player, "login.status-line-none", Ph.raw("platform", name));
        }
    }

    private void logout(Player player, Platform platform) {
        if (platform == Platform.NETEASE) {
            NetEaseLogin.clear();
        } else {
            BilibiliLogin.clear();
        }

        Messages.send(player, "login.logout-done", Ph.raw("platform", platformName(player, platform)));
    }

    private static boolean isLoggedIn(Platform platform) {
        return platform == Platform.NETEASE ? NetEaseLogin.isLoggedIn() : BilibiliLogin.isLoggedIn();
    }

    private static String displayName(Platform platform) {
        return platform == Platform.NETEASE ? NetEaseLogin.displayName() : BilibiliLogin.displayName();
    }

    // -------------------------------------------------------------- cookie login

    private int cookieLogin(Player player, Platform platform, String[] args) {
        String cookieValue = args.length > 2
                ? String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length)).trim()
                : "";

        String required = platform == Platform.NETEASE ? "MUSIC_U=" : "SESSDATA=";

        if (cookieValue.isEmpty() || !cookieValue.contains(required)) {
            Messages.send(player, "login.cookie-usage",
                    Ph.raw("platform", platformName(player, platform)));
            return 1;
        }

        Messages.send(player, "login.cookie-checking");

        SimpleVoiceChatMusic plugin = SimpleVoiceChatMusic.getOrNull();
        if (plugin == null || !plugin.isEnabled()) {
            return 1;
        }

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            String nickname;

            if (platform == Platform.NETEASE) {
                nickname = NetEaseApi.loginWithCookie(cookieValue);

                if (nickname == null) {
                    SimpleVoiceChatMusic.LOGGER.warning("NetEase cookie login failed (invalid MUSIC_U)");
                    runOnMainThread(() -> Messages.send(player, "login.cookie-invalid"));
                    return;
                }

                NetEaseLogin.save(cookieValue, nickname);
            } else {
                nickname = BilibiliApi.nickname(cookieValue);

                if (nickname == null) {
                    SimpleVoiceChatMusic.LOGGER.warning("Bilibili cookie login failed (invalid SESSDATA)");
                    runOnMainThread(() -> Messages.send(player, "login.cookie-invalid"));
                    return;
                }

                BilibiliLogin.save(cookieValue, nickname);
            }

            final String display = nickname.isBlank() ? displayName(platform) : nickname;
            SimpleVoiceChatMusic.LOGGER.info(platform.id + " cookie login succeeded for " + display);

            runOnMainThread(() -> Messages.send(player, "login.cookie-ok",
                    Ph.raw("platform", platformName(player, platform)),
                    Ph.raw("nickname", display)));
        });

        return 0;
    }

    // ------------------------------------------------------------------ QR flow

    private int startQr(Player player, Platform platform) {
        String name = platformName(player, platform);

        if (isLoggedIn(platform)) {
            Messages.send(player, "login.already-logged-in",
                    Ph.raw("platform", name), Ph.raw("nickname", displayName(platform)));
        }

        if (!LOGIN_IN_PROGRESS.compareAndSet(false, true)) {
            Messages.send(player, "login.in-progress");
            return 1;
        }

        Messages.send(player, "login.starting", Ph.raw("platform", name));

        SimpleVoiceChatMusic plugin = SimpleVoiceChatMusic.getOrNull();
        if (plugin == null || !plugin.isEnabled()) {
            LOGIN_IN_PROGRESS.set(false);
            return 1;
        }

        // 新会话：网易云的扫码流程依赖会话 Cookie，必须每次重新开始
        if (platform == Platform.NETEASE) {
            NetEaseApi.resetQrSession();
        }

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            String key;
            String content;

            try {
                if (platform == Platform.NETEASE) {
                    key = NetEaseApi.qrKey();
                    content = NetEaseApi.qrContent(key);
                } else {
                    BilibiliApi.Qr qr = BilibiliApi.qrGenerate();
                    key = qr.key();
                    content = qr.url();
                }
            } catch (Exception e) {
                SimpleVoiceChatMusic.LOGGER.warning(platform.id + " qrKey failed: " + e.getMessage());
                LOGIN_IN_PROGRESS.set(false);
                runOnMainThread(() -> Messages.send(player, "login.failed",
                        Ph.raw("platform", name),
                        Ph.raw("reason", String.valueOf(e.getMessage()))));
                return;
            }

            SimpleVoiceChatMusic.LOGGER.info(platform.id + " login: got QR key for " + player.getName());

            runOnMainThread(() -> {
                Integer mapId = QrMapFactory.giveQrMap(player, content);

                if (mapId == null) {
                    SimpleVoiceChatMusic.LOGGER.warning(platform.id + " login: could not create the QR map for "
                            + player.getName());
                    Messages.send(player, "login.failed",
                            Ph.raw("platform", name),
                            Ph.raw("reason", "could not create the QR map"));
                    LOGIN_IN_PROGRESS.set(false);
                    return;
                }

                Messages.send(player, "login.scan", Ph.raw("platform", name));
                SimpleVoiceChatMusic.LOGGER.info(platform.id + " login: QR map given to " + player.getName()
                        + ", waiting for scan...");

                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> pollLogin(player, platform, key, mapId));
            });
        });

        return 0;
    }

    private void pollLogin(Player player, Platform platform, String key, Integer mapId) {
        boolean settled = false;
        String name = platformName(player, platform);

        try {
            int lastCode = -1;
            boolean scannedNotified = false;

            for (int attempt = 0; attempt < POLL_ATTEMPTS; attempt++) {
                SimpleVoiceChatMusic plugin = SimpleVoiceChatMusic.getOrNull();
                if (plugin == null || !plugin.isEnabled()) {
                    return;
                }

                try {
                    Thread.sleep(scannedNotified ? 1000L : POLL_INTERVAL_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }

                Poll poll;

                try {
                    poll = platform == Platform.NETEASE
                            ? pollNetEase(key)
                            : pollBilibili(key);
                } catch (Exception e) {
                    SimpleVoiceChatMusic.LOGGER.warning(platform.id + " qrPoll failed: " + e.getMessage());
                    continue;
                }

                if (poll.code() != lastCode) {
                    SimpleVoiceChatMusic.LOGGER.info(platform.id + " login poll: code=" + poll.code()
                            + (poll.detail() == null ? "" : " body=" + poll.detail()));
                    lastCode = poll.code();
                }

                if (poll.scanned() && !scannedNotified) {
                    scannedNotified = true;
                    runOnMainThread(() -> Messages.send(player, "login.scanned", Ph.raw("platform", name)));
                }

                if (attempt == 15 && !scannedNotified && poll.waiting()) {
                    runOnMainThread(() -> Messages.send(player, "login.waiting", Ph.raw("platform", name)));
                }

                if (poll.success()) {
                    settled = true;
                    handleSuccess(player, platform, poll);
                    return;
                }

                if (poll.failed()) {
                    settled = true;
                    SimpleVoiceChatMusic.LOGGER.warning(platform.id + " login blocked/unknown response: code="
                            + poll.code() + " body=" + poll.detail());
                    final String detail = poll.detail() == null ? String.valueOf(poll.code()) : poll.detail();
                    runOnMainThread(() -> Messages.send(player, "login.risk",
                            Ph.raw("platform", name), Ph.raw("detail", detail)));
                    return;
                }

                if (poll.expired()) {
                    settled = true;
                    SimpleVoiceChatMusic.LOGGER.info(platform.id + " login: QR code expired for " + player.getName());
                    runOnMainThread(() -> Messages.send(player, "login.expired", Ph.raw("platform", name)));
                    return;
                }
            }

            if (!settled) {
                SimpleVoiceChatMusic.LOGGER.info(platform.id + " login: timed out for " + player.getName());
                runOnMainThread(() -> Messages.send(player, "login.timeout", Ph.raw("platform", name)));
            }
        } finally {
            LOGIN_IN_PROGRESS.set(false);

            // 登录流程结束（成功/过期/超时/风控）：把二维码地图收回来
            runOnMainThread(() -> QrMapFactory.removeMapItem(player, mapId));
        }
    }

    private static Poll pollNetEase(String key) throws Exception {
        NetEaseApi.QrPoll poll = NetEaseApi.qrPoll(key);

        return new Poll(poll.code(),
                poll.code() == 801,
                poll.code() == 802,
                poll.code() == 803,
                poll.code() == 800,
                poll.cookie(),
                poll.nickname(),
                poll.detail());
    }

    private static Poll pollBilibili(String key) throws Exception {
        BilibiliApi.QrPoll poll = BilibiliApi.qrPoll(key);

        return new Poll(poll.code(),
                poll.waiting(),
                poll.scanned(),
                poll.success(),
                poll.expired(),
                poll.cookie(),
                poll.nickname(),
                poll.detail());
    }

    private void handleSuccess(Player player, Platform platform, Poll poll) {
        String name = platformName(player, platform);
        String cookie = poll.cookie();
        String required = platform == Platform.NETEASE ? "MUSIC_U=" : "SESSDATA=";

        if (cookie == null || !cookie.contains(required)) {
            SimpleVoiceChatMusic.LOGGER.warning(platform.id + " login succeeded without the required cookie"
                    + " (got cookies: " + cookieNames(cookie) + ")");
            runOnMainThread(() -> Messages.send(player, "login.failed",
                    Ph.raw("platform", name),
                    Ph.raw("reason", "login succeeded but " + required + " cookie was not returned")));
            return;
        }

        String nickname;

        if (platform == Platform.NETEASE) {
            NetEaseApi.setCookie(cookie);
            nickname = poll.nickname();
            if (nickname == null || nickname.isBlank()) {
                nickname = NetEaseApi.nickname();
            }
            NetEaseLogin.save(cookie, nickname);
        } else {
            BilibiliApi.setCookie(cookie);
            nickname = poll.nickname();
            if (nickname == null || nickname.isBlank()) {
                nickname = BilibiliApi.nickname(cookie);
            }
            BilibiliLogin.save(cookie, nickname);
        }

        final String display = nickname == null ? "" : nickname;
        SimpleVoiceChatMusic.LOGGER.info(platform.id + " login succeeded"
                + (display.isEmpty() ? "" : " for " + display));

        runOnMainThread(() -> Messages.send(player, "login.ok",
                Ph.raw("platform", name), Ph.raw("nickname", display)));
    }

    /** 只输出 Cookie 名称，避免把凭据写进日志 */
    private static String cookieNames(String cookie) {
        if (cookie == null || cookie.isBlank()) {
            return "<none>";
        }

        StringBuilder builder = new StringBuilder();
        for (String part : cookie.split(";")) {
            int equals = part.indexOf('=');
            if (equals <= 0) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(", ");
            }
            builder.append(part.substring(0, equals).trim());
        }
        return builder.length() == 0 ? "<none>" : builder.toString();
    }

    private static String platformName(Player player, Platform platform) {
        return Messages.plain(player, platform.langKey);
    }

    private static void runOnMainThread(Runnable task) {
        SimpleVoiceChatMusic plugin = SimpleVoiceChatMusic.getOrNull();
        if (plugin == null || !plugin.isEnabled()) {
            return;
        }

        Bukkit.getScheduler().runTask(plugin, task);
    }
}
