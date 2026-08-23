package com.ltm.geoduel.client.ui;

import javax.swing.SwingUtilities;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/**
 * Vẽ bản đồ thế giới "chìm" làm nền trang trí (đăng nhập, sảnh):
 * tile Carto zoom 3 phủ kín chiều ngang, bên trên tráng một lớp màu giấy
 * để bản đồ chỉ thấp thoáng, không tranh chú ý với nội dung chính.
 * Dùng như một hàm vẽ tĩnh gọi từ paintComponent của panel chứa.
 */
public final class WorldBackdrop {
    private static final int Z = 3;      // 8×8 tile = toàn thế giới
    private static final int N = 1 << Z;

    private WorldBackdrop() {}

    /**
     * Vẽ backdrop lên vùng (0,0,w,h) của {@code host}.
     * @param washAlpha độ đậm lớp tráng màu giấy 0-255 (đăng nhập ~200, sảnh ~232)
     */
    public static void paint(java.awt.Component host, Graphics2D g2, int w, int h, int washAlpha) {
        if (w <= 0 || h <= 0) return;
        double tileScreen = w / (double) N; // phủ kín chiều ngang
        double mapHeight = tileScreen * N;
        double yOff = (h - mapHeight) / 2;  // canh giữa theo chiều dọc (thừa thì cắt)

        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        for (int ty = 0; ty < N; ty++) {
            double sy = yOff + ty * tileScreen;
            if (sy + tileScreen < 0 || sy > h) continue;
            for (int tx = 0; tx < N; tx++) {
                BufferedImage tile = TileStore.get(Z, tx, ty,
                        () -> SwingUtilities.invokeLater(host::repaint));
                if (tile != null) {
                    g2.drawImage(tile, (int) Math.round(tx * tileScreen), (int) Math.round(sy),
                            (int) Math.ceil(tileScreen) + 1, (int) Math.ceil(tileScreen) + 1, null);
                }
            }
        }
        // lớp tráng để bản đồ "chìm" xuống dưới nội dung
        g2.setColor(Theme.withAlpha(Theme.BG, washAlpha));
        g2.fillRect(0, 0, w, h);
    }
}
