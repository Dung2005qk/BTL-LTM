package com.ltm.geoduel.client.ui;

import com.ltm.geoduel.client.ui.components.Icons;

import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import java.awt.BasicStroke;
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
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.nio.file.Path;

/**
 * Bản đồ thế giới dạng slippy-map (Web Mercator): tải tile Carto Voyager theo
 * nhu cầu ở mọi mức zoom 2–17, cache RAM + đĩa (assets/tilecache).
 * Lăn chuột để zoom quanh con trỏ, kéo để pan, click đặt ghim dự đoán.
 * Chế độ kết quả vẽ ghim hai người chơi, vị trí đích và đường nối.
 *
 * Ghi công bắt buộc: © OpenStreetMap contributors © CARTO (vẽ ở góc bản đồ).
 */
public class MapPanel extends JComponent {
    public interface PinListener { void onPinPlaced(double lat, double lng); }

    private static final int TILE = 256;
    private static final double MIN_ZOOM = 1.6, MAX_ZOOM = 17.0;
    private static final String ATTRIBUTION = "© OpenStreetMap contributors © CARTO";

    // ---- khung nhìn: tâm (lat/lng) + zoom liên tục ----
    private double centerLat = 16.0, centerLng = 106.0;
    private double zoom = 4.2;
    private boolean autoFit = true; // chưa ai đụng vào → tự về khung nhìn thế giới

    private boolean interactive = false;
    private PinListener pinListener;
    private Double pinLat, pinLng;

    // dữ liệu chế độ kết quả
    private boolean resultMode = false;
    private Double myLat, myLng, oppLat, oppLng;
    private double targetLat, targetLng;

    private Point dragStart;
    private double dragLat, dragLng;
    private boolean dragged;

    public MapPanel() {
        setOpaque(true);
        setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
        MouseAdapter mouse = new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                dragStart = e.getPoint();
                dragLat = centerLat;
                dragLng = centerLng;
                dragged = false;
            }
            @Override public void mouseDragged(MouseEvent e) {
                if (dragStart == null) return;
                int dx = e.getX() - dragStart.x, dy = e.getY() - dragStart.y;
                if (Math.abs(dx) > 4 || Math.abs(dy) > 4) {
                    dragged = true;
                    autoFit = false;
                }
                double scale = worldScale();
                Point2D.Double startWorld = latLngToWorld(dragLat, dragLng);
                Point2D.Double moved = new Point2D.Double(
                        startWorld.x - dx / scale, startWorld.y - dy / scale);
                double[] ll = worldToLatLng(moved.x, moved.y);
                centerLat = clampLat(ll[0]);
                centerLng = clampLng(ll[1]);
                repaint();
            }
            @Override public void mouseReleased(MouseEvent e) {
                if (!dragged && interactive && pinListener != null) {
                    double[] ll = screenToLatLng(e.getX(), e.getY());
                    pinLat = ll[0];
                    pinLng = ll[1];
                    repaint();
                    pinListener.onPinPlaced(ll[0], ll[1]);
                }
                dragStart = null;
            }
            @Override public void mouseWheelMoved(MouseWheelEvent e) {
                autoFit = false;
                zoomAround(e.getX(), e.getY(), e.getWheelRotation() < 0 ? 0.65 : -0.65);
            }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
        addMouseWheelListener(mouse);
    }

    /** Đặt thư mục cache tile. @return luôn null (không còn phụ thuộc ảnh bản đồ tĩnh). */
    public String loadMap(Path assetsDir) {
        TileStore.setCacheDir(assetsDir.resolve("tilecache"));
        return null;
    }

    public void setPinListener(PinListener l) { this.pinListener = l; }

    public void startGuessing() {
        interactive = true;
        resultMode = false;
        pinLat = pinLng = null;
        autoFit = true;
        repaint();
    }

    public void lockPin() {
        interactive = false;
        repaint();
    }

    public void showResult(Double myLat, Double myLng, Double oppLat, Double oppLng,
                           double targetLat, double targetLng) {
        this.interactive = false;
        this.resultMode = true;
        this.myLat = myLat; this.myLng = myLng;
        this.oppLat = oppLat; this.oppLng = oppLng;
        this.targetLat = targetLat; this.targetLng = targetLng;
        this.autoFit = false;
        fitToResult();
        repaint();
    }

    public boolean hasPin() { return pinLat != null; }
    public Double pinLat() { return pinLat; }
    public Double pinLng() { return pinLng; }

    public void resetView() {
        autoFit = true;
        repaint();
    }

    // ================= toạ độ Web Mercator =================
    // "world" = toạ độ chuẩn hoá [0,1]² của toàn thế giới ở mọi zoom.

    private static Point2D.Double latLngToWorld(double lat, double lng) {
        double x = (lng + 180.0) / 360.0;
        double rad = Math.toRadians(clampLat(lat));
        double y = (1 - Math.log(Math.tan(rad) + 1 / Math.cos(rad)) / Math.PI) / 2;
        return new Point2D.Double(x, y);
    }

    private static double[] worldToLatLng(double x, double y) {
        double lng = x * 360.0 - 180.0;
        double lat = Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1 - 2 * y))));
        return new double[]{lat, lng};
    }

    /** px màn hình trên 1 đơn vị world ở zoom hiện tại. */
    private double worldScale() {
        return TILE * Math.pow(2, zoom);
    }

    private Point2D.Double latLngToScreen(double lat, double lng) {
        Point2D.Double w = latLngToWorld(lat, lng);
        Point2D.Double c = latLngToWorld(centerLat, centerLng);
        double s = worldScale();
        return new Point2D.Double(
                getWidth() / 2.0 + (w.x - c.x) * s,
                getHeight() / 2.0 + (w.y - c.y) * s);
    }

    private double[] screenToLatLng(int sx, int sy) {
        Point2D.Double c = latLngToWorld(centerLat, centerLng);
        double s = worldScale();
        double wx = c.x + (sx - getWidth() / 2.0) / s;
        double wy = c.y + (sy - getHeight() / 2.0) / s;
        wx = Math.max(0, Math.min(1, wx));
        wy = Math.max(0, Math.min(1, wy));
        return worldToLatLng(wx, wy);
    }

    private static double clampLat(double lat) {
        return Math.max(-85.05, Math.min(85.05, lat));
    }

    private static double clampLng(double lng) {
        return Math.max(-180, Math.min(180, lng));
    }

    private void zoomAround(int sx, int sy, double delta) {
        double[] under = screenToLatLng(sx, sy);
        double newZoom = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, zoom + delta));
        if (newZoom == zoom) return;
        // giữ điểm dưới con trỏ đứng yên: dời tâm tương ứng
        Point2D.Double underW = latLngToWorld(under[0], under[1]);
        double s = TILE * Math.pow(2, newZoom);
        double cx = underW.x - (sx - getWidth() / 2.0) / s;
        double cy = underW.y - (sy - getHeight() / 2.0) / s;
        double[] c = worldToLatLng(cx, cy);
        zoom = newZoom;
        centerLat = clampLat(c[0]);
        centerLng = clampLng(c[1]);
        repaint();
    }

    private void fitWholeWorld() {
        if (getWidth() == 0) return;
        centerLat = 20.0;
        centerLng = 60.0; // tâm Á-Âu, Việt Nam dễ với tới
        double zw = Math.log(getWidth() / (double) TILE) / Math.log(2);
        double zh = Math.log(getHeight() / (double) TILE) / Math.log(2);
        zoom = Math.max(MIN_ZOOM, Math.min(Math.max(zw, zh), 4.5));
    }

    private void fitToResult() {
        if (getWidth() == 0) return;
        double minX = 1, minY = 1, maxX = 0, maxY = 0;
        java.util.List<Point2D.Double> pts = new java.util.ArrayList<>();
        pts.add(latLngToWorld(targetLat, targetLng));
        if (myLat != null) pts.add(latLngToWorld(myLat, myLng));
        if (oppLat != null) pts.add(latLngToWorld(oppLat, oppLng));
        for (Point2D.Double p : pts) {
            minX = Math.min(minX, p.x); maxX = Math.max(maxX, p.x);
            minY = Math.min(minY, p.y); maxY = Math.max(maxY, p.y);
        }
        double spanX = Math.max(maxX - minX, 1e-6), spanY = Math.max(maxY - minY, 1e-6);
        double zx = Math.log(getWidth() * 0.7 / (spanX * TILE)) / Math.log(2);
        double zy = Math.log(getHeight() * 0.7 / (spanY * TILE)) / Math.log(2);
        zoom = Math.max(MIN_ZOOM, Math.min(Math.min(zx, zy), 13));
        double[] c = worldToLatLng((minX + maxX) / 2, (minY + maxY) / 2);
        centerLat = c[0];
        centerLng = c[1];
    }

    // ================= vẽ =================

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = Theme.prep(g);
        int w = getWidth(), h = getHeight();
        g2.setColor(new Color(0xA8CBDD)); // màu biển của Voyager khi tile chưa về
        g2.fillRect(0, 0, w, h);
        if (autoFit) fitWholeWorld();

        int tileZ = (int) Math.max(1, Math.min(MAX_ZOOM, Math.round(zoom)));
        double scaleFactor = Math.pow(2, zoom - tileZ); // tile được vẽ to/nhỏ theo zoom lẻ
        double tileScreen = TILE * scaleFactor;
        int n = 1 << tileZ;

        Point2D.Double c = latLngToWorld(centerLat, centerLng);
        double topLeftWx = c.x - w / 2.0 / worldScale();
        double topLeftWy = c.y - h / 2.0 / worldScale();
        int tx0 = (int) Math.floor(topLeftWx * n);
        int ty0 = (int) Math.floor(topLeftWy * n);
        int tx1 = (int) Math.floor((c.x + w / 2.0 / worldScale()) * n);
        int ty1 = (int) Math.floor((c.y + h / 2.0 / worldScale()) * n);

        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        for (int ty = Math.max(0, ty0); ty <= Math.min(n - 1, ty1); ty++) {
            for (int tx = tx0; tx <= tx1; tx++) {
                int wrappedX = Math.floorMod(tx, n);
                BufferedImage tile = getTile(tileZ, wrappedX, ty);
                double sx = (tx / (double) n - topLeftWx) * worldScale();
                double sy = (ty / (double) n - topLeftWy) * worldScale();
                if (tile != null) {
                    g2.drawImage(tile, (int) Math.round(sx), (int) Math.round(sy),
                            (int) Math.ceil(tileScreen) + 1, (int) Math.ceil(tileScreen) + 1, null);
                } else {
                    g2.setColor(new Color(0x9DBFD2));
                    g2.fillRect((int) sx, (int) sy, (int) tileScreen + 1, (int) tileScreen + 1);
                }
            }
        }

        if (resultMode) {
            paintResult(g2);
        } else if (pinLat != null) {
            paintPin(g2, pinLat, pinLng, Theme.AMBER);
        }

        // ghi công (bắt buộc theo giấy phép dữ liệu bản đồ)
        g2.setFont(Theme.font(11));
        FontMetrics fm = g2.getFontMetrics();
        int tw = fm.stringWidth(ATTRIBUTION);
        g2.setColor(new Color(0, 0, 0, 130));
        g2.fillRoundRect(w - tw - 16, h - 22, tw + 12, 18, 8, 8);
        g2.setColor(new Color(230, 230, 230, 210));
        g2.drawString(ATTRIBUTION, w - tw - 10, h - 9);
    }

    private void paintResult(Graphics2D g2) {
        Point2D.Double target = latLngToScreen(targetLat, targetLng);
        if (myLat != null) {
            drawDashedLine(g2, latLngToScreen(myLat, myLng), target, Theme.AMBER);
            paintPin(g2, myLat, myLng, Theme.AMBER);
        }
        if (oppLat != null) {
            drawDashedLine(g2, latLngToScreen(oppLat, oppLng), target, Theme.TEAL);
            paintPin(g2, oppLat, oppLng, Theme.TEAL);
        }
        int r = 9;
        g2.setColor(Color.WHITE);
        g2.fillOval((int) target.x - r, (int) target.y - r, r * 2, r * 2);
        g2.setColor(Theme.RED);
        g2.fillOval((int) target.x - 4, (int) target.y - 4, 8, 8);
    }

    private void paintPin(Graphics2D g2, double lat, double lng, Color color) {
        Point2D.Double p = latLngToScreen(lat, lng);
        int size = 26;
        Icons.pin(size, color).paintIcon(this, g2, (int) p.x - size / 2, (int) p.y - size + 2);
    }

    private void drawDashedLine(Graphics2D g2, Point2D.Double a, Point2D.Double b, Color color) {
        var old = g2.getStroke();
        g2.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND,
                1f, new float[]{6f, 6f}, 0f));
        g2.setColor(Theme.withAlpha(color, 200));
        Path2D.Double line = new Path2D.Double();
        line.moveTo(a.x, a.y);
        line.lineTo(b.x, b.y);
        g2.draw(line);
        g2.setStroke(old);
    }

    // ================= tile: qua kho dùng chung =================

    private BufferedImage getTile(int z, int x, int y) {
        return TileStore.get(z, x, y, () -> SwingUtilities.invokeLater(this::repaint));
    }
}
