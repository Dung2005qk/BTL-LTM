package com.ltm.geoduel.client.ui;

import javax.swing.JComponent;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.image.BufferedImage;

/**
 * Trình xem ảnh toàn cảnh 360° (equirectangular) kiểu GeoGuessr:
 * kéo chuột để nhìn quanh, lăn chuột để phóng (đổi góc nhìn FOV),
 * dải la bàn ở trên cho biết đang nhìn hướng nào.
 *
 * Kỹ thuật: chiếu phối cảnh ngược — với mỗi pixel màn hình, tính tia nhìn
 * (xoay theo yaw/pitch) rồi lấy mẫu ảnh nguồn theo kinh/vĩ độ của tia.
 * Render vào buffer bằng số học int, đủ mượt cho kéo xoay ở cỡ cửa sổ game.
 */
public class PanoViewer extends JComponent {
    private BufferedImage source;
    private int[] srcPx;
    private int srcW, srcH;

    private double yaw = 0;            // quay ngang, radian
    private double pitch = 0;          // ngẩng/cúi, radian (giới hạn ±80°)
    private double fovDeg = 90;        // góc nhìn ngang
    /** Hướng bắc thật của tâm ảnh (độ, từ dữ liệu nguồn); NaN = không rõ. */
    private double northOffsetDeg = Double.NaN;

    private BufferedImage frame;       // buffer render
    private Point dragStart;
    private double dragYaw, dragPitch;

    public PanoViewer() {
        setOpaque(true);
        setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
        MouseAdapter mouse = new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                dragStart = e.getPoint();
                dragYaw = yaw;
                dragPitch = pitch;
            }
            @Override public void mouseDragged(MouseEvent e) {
                if (dragStart == null) return;
                double perPx = Math.toRadians(fovDeg) / Math.max(1, getWidth());
                yaw = dragYaw - (e.getX() - dragStart.x) * perPx;
                pitch = clampPitch(dragPitch + (e.getY() - dragStart.y) * perPx);
                repaint();
            }
            @Override public void mouseReleased(MouseEvent e) { dragStart = null; }
            @Override public void mouseWheelMoved(MouseWheelEvent e) {
                fovDeg = Math.max(30, Math.min(110, fovDeg + e.getWheelRotation() * 8));
                repaint();
            }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
        addMouseWheelListener(mouse);
    }

    /** Đặt ảnh toàn cảnh mới; compassDeg = hướng bắc của tâm ảnh (NaN nếu không có). */
    public void setImage(BufferedImage img, double compassDeg) {
        this.northOffsetDeg = compassDeg;
        this.yaw = 0;
        this.pitch = 0;
        this.fovDeg = 90;
        if (img == null) {
            source = null;
            srcPx = null;
        } else {
            // chép sang INT_RGB để lấy mảng pixel truy cập nhanh
            BufferedImage rgb = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D g = rgb.createGraphics();
            g.drawImage(img, 0, 0, null);
            g.dispose();
            source = rgb;
            srcW = rgb.getWidth();
            srcH = rgb.getHeight();
            srcPx = ((java.awt.image.DataBufferInt) rgb.getRaster().getDataBuffer()).getData();
        }
        repaint();
    }

    private static double clampPitch(double p) {
        double lim = Math.toRadians(80);
        return Math.max(-lim, Math.min(lim, p));
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = Theme.prep(g);
        int w = getWidth(), h = getHeight();
        g2.setColor(Theme.PHOTO_BG);
        g2.fillRect(0, 0, w, h);
        if (source == null || w == 0 || h == 0) {
            g2.setColor(Theme.ON_PHOTO);
            g2.setFont(Theme.font(15));
            String s = "Đang tải ảnh 360°...";
            FontMetrics fm = g2.getFontMetrics();
            g2.drawString(s, (w - fm.stringWidth(s)) / 2, h / 2);
            return;
        }
        renderFrame(w, h);
        g2.drawImage(frame, 0, 0, null);
        paintCompass(g2, w);
    }

    /** Render phối cảnh vào buffer {@code frame} (tạo lại khi đổi kích thước). */
    private void renderFrame(int w, int h) {
        if (frame == null || frame.getWidth() != w || frame.getHeight() != h) {
            frame = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        }
        int[] out = ((java.awt.image.DataBufferInt) frame.getRaster().getDataBuffer()).getData();

        double fov = Math.toRadians(fovDeg);
        double planeDist = (w / 2.0) / Math.tan(fov / 2);
        double sinYaw = Math.sin(yaw), cosYaw = Math.cos(yaw);
        double sinPitch = Math.sin(pitch), cosPitch = Math.cos(pitch);
        double cx = w / 2.0, cy = h / 2.0;
        double invPi = 1.0 / Math.PI, inv2Pi = 1.0 / (2 * Math.PI);

        for (int y = 0; y < h; y++) {
            double vy = cy - y;
            for (int x = 0; x < w; x++) {
                double vx = x - cx;
                // tia nhìn trước khi xoay: (vx, vy, planeDist), sau đó xoay pitch quanh trục X rồi yaw quanh trục Y
                double ry = vy * cosPitch + planeDist * sinPitch;
                double rz0 = -vy * sinPitch + planeDist * cosPitch;
                double rx = vx * cosYaw + rz0 * sinYaw;
                double rz = -vx * sinYaw + rz0 * cosYaw;

                double lon = Math.atan2(rx, rz);                       // -π..π
                double hyp = Math.sqrt(rx * rx + rz * rz);
                double lat = Math.atan2(ry, hyp);                      // -π/2..π/2

                int sx = (int) ((lon * inv2Pi + 0.5) * srcW);
                int sy = (int) ((0.5 - lat * invPi) * srcH);
                if (sx < 0) sx = 0; else if (sx >= srcW) sx = srcW - 1;
                if (sy < 0) sy = 0; else if (sy >= srcH) sy = srcH - 1;
                out[y * w + x] = srcPx[sy * srcW + sx];
            }
        }
    }

    /** Dải la bàn trên đầu: vạch + chữ N/E/S/W trượt theo hướng nhìn. */
    private void paintCompass(Graphics2D g2, int w) {
        int stripW = Math.min(360, w / 3), stripH = 26;
        int x0 = (w - stripW) / 2, y0 = 10;
        g2.setColor(new Color(10, 14, 20, 200));
        g2.fillRoundRect(x0, y0, stripW, stripH, stripH, stripH);

        double headingDeg = Math.toDegrees(yaw);
        if (!Double.isNaN(northOffsetDeg)) headingDeg += northOffsetDeg;
        headingDeg = ((headingDeg % 360) + 360) % 360;

        String[] names = {"B", "Đ", "N", "T"}; // Bắc Đông Nam Tây
        g2.setFont(Theme.bold(12));
        for (int deg = 0; deg < 360; deg += 15) {
            double rel = ((deg - headingDeg + 540) % 360) - 180; // -180..180
            if (Math.abs(rel) > 30) continue; // dải hiển thị ±30°
            double sx = x0 + stripW / 2.0 + rel / 30.0 * (stripW / 2.0 - 14);
            if (deg % 90 == 0) {
                g2.setColor(deg == 0 ? Theme.AMBER : Theme.ON_PHOTO);
                String nm = names[deg / 90];
                FontMetrics fm = g2.getFontMetrics();
                g2.drawString(nm, (int) sx - fm.stringWidth(nm) / 2, y0 + 18);
            } else {
                g2.setColor(Theme.withAlpha(Theme.ON_PHOTO, 110));
                g2.drawLine((int) sx, y0 + 8, (int) sx, y0 + stripH - 8);
            }
        }
        // kim giữa
        g2.setColor(Theme.AMBER);
        g2.drawLine(x0 + stripW / 2, y0 + 3, x0 + stripW / 2, y0 + stripH - 3);
        if (Double.isNaN(northOffsetDeg)) {
            // không có dữ liệu la bàn thật → ghi chú nhỏ để không đánh lừa người chơi
            g2.setFont(Theme.font(9));
            g2.setColor(Theme.withAlpha(Theme.ON_PHOTO, 140));
            g2.drawString("tương đối", x0 + stripW + 6, y0 + 17);
        }
    }
}
