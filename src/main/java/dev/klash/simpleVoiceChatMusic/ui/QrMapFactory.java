package dev.klash.simpleVoiceChatMusic.ui;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapPalette;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;

import java.awt.Color;
import java.util.HashMap;
import java.util.Map;

/**
 * 把二维码画到一张地图上（128x128，最清晰缩放）。
 *
 * <p>用于 {@code /music login 网易云}：管理员拿到地图后手机扫码登录。
 * 地图在物品展示框里全尺寸渲染时分辨率足够扫码。</p>
 */
public final class QrMapFactory {

    private static final int MAP_SIZE = 128;
    private static final int QUIET_ZONE = 4;

    /** 地图调色板里最接近纯白/纯黑的颜色 */
    @SuppressWarnings("deprecation")
    private static final byte WHITE = MapPalette.matchColor(Color.WHITE);
    @SuppressWarnings("deprecation")
    private static final byte BLACK = MapPalette.matchColor(Color.BLACK);

    private QrMapFactory() {
    }

    /**
     * 给玩家一张印着指定内容二维码的地图。
     *
     * @return 成功时返回地图 ID（用于登录后回收），失败返回 null
     */
    public static Integer giveQrMap(Player player, String content) {
        try {
            World world = player.getWorld();
            MapView view = Bukkit.createMap(world);

            view.setScale(MapView.Scale.CLOSEST);
            view.setTrackingPosition(false);
            view.setUnlimitedTracking(false);

            for (MapRenderer renderer : view.getRenderers()) {
                view.removeRenderer(renderer);
            }

            BitMatrix matrix = encodeQr(content);

            view.addRenderer(new MapRenderer(true) {
                @Override
                @SuppressWarnings("deprecation")
                public void render(MapView mapView, MapCanvas canvas, Player viewer) {
                    // 白底
                    for (int x = 0; x < MAP_SIZE; x++) {
                        for (int y = 0; y < MAP_SIZE; y++) {
                            canvas.setPixel(x, y, WHITE);
                        }
                    }

                    // ZXing 的矩阵已经包含静区，这里按模块数取整数倍缩放：
                    // 41 模块的登录二维码可以得到 3px/模块，扫码更容易
                    int modules = matrix.getWidth();
                    int scale = MAP_SIZE / modules;
                    if (scale < 1) {
                        scale = 1;
                    }

                    int offset = (MAP_SIZE - modules * scale) / 2;

                    for (int x = 0; x < modules; x++) {
                        for (int y = 0; y < modules; y++) {
                            if (!matrix.get(x, y)) {
                                continue;
                            }

                            for (int dx = 0; dx < scale; dx++) {
                                for (int dy = 0; dy < scale; dy++) {
                                    canvas.setPixel(offset + x * scale + dx, offset + y * scale + dy, BLACK);
                                }
                            }
                        }
                    }
                }
            });

            ItemStack mapItem = new ItemStack(Material.FILLED_MAP);
            MapMeta meta = (MapMeta) mapItem.getItemMeta();
            if (meta == null) {
                return null;
            }

            meta.setMapView(view);
            mapItem.setItemMeta(meta);

            Map<Integer, ItemStack> leftover = player.getInventory().addItem(mapItem);

            // 背包满时直接掉在脚下，别让二维码"消失"
            if (!leftover.isEmpty()) {
                player.getWorld().dropItem(player.getLocation(), mapItem);
            }

            return view.getId();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 登录结束后把二维码地图从玩家背包/副手里删掉。
     */
    public static void removeMapItem(Player player, Integer mapId) {
        if (player == null || mapId == null) {
            return;
        }

        var inventory = player.getInventory();

        for (int slot = 0; slot < inventory.getSize(); slot++) {
            if (isQrMap(inventory.getItem(slot), mapId)) {
                inventory.setItem(slot, null);
            }
        }

        if (isQrMap(inventory.getItemInOffHand(), mapId)) {
            inventory.setItemInOffHand(null);
        }
    }

    private static boolean isQrMap(ItemStack item, int mapId) {
        if (item == null || item.getType() != Material.FILLED_MAP) {
            return false;
        }

        if (!(item.getItemMeta() instanceof MapMeta meta)) {
            return false;
        }

        MapView view = meta.getMapView();
        return view != null && view.getId() == mapId;
    }

    private static BitMatrix encodeQr(String content) throws Exception {
        Map<EncodeHintType, Object> hints = new HashMap<>();
        hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
        hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.L);
        hints.put(EncodeHintType.MARGIN, QUIET_ZONE);

        return new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 0, 0, hints);
    }
}
