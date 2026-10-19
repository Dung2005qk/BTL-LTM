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
import java.awt.Paint;
import java.awt.Point;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
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
    private static final double MIN_ZOOM = 4.5, MAX_ZOOM = 17.0;
    private static final String ATTRIBUTION = "© OpenStreetMap contributors © CARTO";

    // ---- khung nhìn: tâm (lat/lng) + zoom liên tục ----
    private double centerLat = 16.0, centerLng = 108.0; // tâm cân đối giữa dải đất liền và Hoàng Sa, Trường Sa
    private double zoom = 5.2;
    private boolean autoFit = true; // chưa ai đụng vào → tự về khung nhìn Việt Nam

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
                    Area vn = buildVietnamShape();
                    if (isInsideVietnam(e.getX(), e.getY(), vn)) {
                        double[] ll = screenToLatLng(e.getX(), e.getY());
                        pinLat = ll[0];
                        pinLng = ll[1];
                        repaint();
                        pinListener.onPinPlaced(ll[0], ll[1]);
                    }
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
        return Math.max(6.5, Math.min(25.0, lat));
    }

    private static double clampLng(double lng) {
        return Math.max(100.0, Math.min(118.5, lng));
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
        centerLat = 16.0;
        centerLng = 108.0; // Tâm dải đất Việt Nam và biển Đông
        double zh = Math.log(getHeight() / (0.048 * TILE)) / Math.log(2);
        zoom = Math.max(MIN_ZOOM, Math.min(zh, 7.5));
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

        // ================= HIỆU ỨNG SƯƠNG MÙ MỜ ẢO (ETHEREAL FOG OF WAR) =================
        // Ngoại trừ Việt Nam (đất liền + đảo + biển chủ quyền), phủ sương mù mờ ảo đa tầng
        Area vnArea = buildVietnamArea();
        Path2D.Double mainlandBorder = buildPolygonPath(VN_MAINLAND_BORDER);
        Path2D.Double phuQuocBorder = buildPolygonPath(PHU_QUOC);
        Path2D.Double conDaoBorder = buildPolygonPath(CON_DAO);

        Area fogArea = new Area(new Rectangle2D.Double(0, 0, w, h));
        fogArea.subtract(vnArea);

        // 1. Tầng sương mù ngọc trai bán trong suốt (~72% che phủ):
        // Giúp các nước láng giềng và biển xa mờ ảo tự nhiên, không che cụt ngủn như màng phẳng
        g2.setColor(new Color(246, 249, 252, 185));
        g2.fill(fogArea);

        // 2. Tầng chiều sâu khí quyển (Radial Depth Mist):
        // Càng ra xa bờ cõi Việt Nam, sương mù càng dày đặc, tạo cảm giác mây trời bồng bềnh
        Point2D.Double centerVn = latLngToScreen(16.0, 108.0);
        float maxRadius = (float) (Math.max(w, h) * 0.95);
        if (maxRadius > 10) {
            float[] dist = {0.0f, 0.35f, 0.70f, 1.0f};
            Color[] colors = {
                new Color(248, 250, 253, 20),   // Gần Việt Nam: không khí trong lành, sáng rõ
                new Color(244, 248, 252, 65),   // Vùng đệm sương mỏng
                new Color(236, 242, 249, 125),  // Sương dày hơn ở khu vực lân cận
                new Color(225, 235, 245, 175)   // Chân trời xa xôi sương mờ mịt
            };
            RadialGradientPaint mistDepth = new RadialGradientPaint(
                (float) centerVn.x, (float) centerVn.y, maxRadius, dist, colors
            );
            Paint oldPaint = g2.getPaint();
            g2.setPaint(mistDepth);
            g2.fill(fogArea);
            g2.setPaint(oldPaint);
        }

        // 3. Tầng tán xạ sương mù viền (Feathering Gaussian Blur quanh đường biên giới)
        // Tạo đường viền mờ ảo tự nhiên như mây mù lượn quanh núi đồi, xóa bỏ cảm giác vết cắt sắc
        float[] featherWidths = {26.0f, 18.0f, 12.0f, 7.0f, 3.0f};
        int[] featherAlphas  = {18, 32, 50, 75, 115};
        for (int i = 0; i < featherWidths.length; i++) {
            g2.setStroke(new BasicStroke(featherWidths[i], BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.setColor(new Color(246, 249, 252, featherAlphas[i]));
            g2.draw(mainlandBorder);
            g2.draw(phuQuocBorder);
            g2.draw(conDaoBorder);
        }

        // 4. Tầng hào quang biên giới ánh vàng hổ phách (Sunlit Rim Light)
        g2.setColor(new Color(0xD9, 0x91, 0x3B, 45));
        g2.setStroke(new BasicStroke(3.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g2.draw(mainlandBorder);
        g2.draw(phuQuocBorder);
        g2.draw(conDaoBorder);

        g2.setColor(new Color(0xD9, 0x91, 0x3B, 180));
        g2.setStroke(new BasicStroke(1.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g2.draw(mainlandBorder);
        g2.draw(phuQuocBorder);
        g2.draw(conDaoBorder);

        // 5. Vùng biển và hào quang chủ quyền các quần đảo (Hoàng Sa & Trường Sa)
        drawArchipelagoHalo(g2, 16.5, 112.0, 0.85); // Hoàng Sa
        drawArchipelagoHalo(g2, 9.8, 114.2, 1.80);  // Trường Sa

        // Thẻ định vị chủ quyền biển đảo phong cách kính mờ
        drawIslandBadge(g2, 16.5, 112.0, "Quần đảo Hoàng Sa (Việt Nam)");
        drawIslandBadge(g2, 9.8, 114.2, "Quần đảo Trường Sa (Việt Nam)");
        drawIslandBadge(g2, 10.22, 103.96, "Đảo Phú Quốc");
        drawIslandBadge(g2, 8.70, 106.60, "Côn Đảo");

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

    // ================= Bản đồ Việt Nam: Giữ biển & đất nước trong lành, phủ sương mờ ảo xung quanh =================

    private Area buildVietnamShape() {
        return buildVietnamArea();
    }

    private boolean isInsideVietnam(int sx, int sy, Area vnArea) {
        if (vnArea.contains(sx, sy)) return true;
        // Bấm gần bờ biển hoặc đảo (trong phạm vi 16px) vẫn được ghi nhận thuận tiện
        return vnArea.intersects(sx - 8, sy - 8, 16, 16);
    }

    private Area buildVietnamArea() {
        Area area = new Area(buildPolygonPath(VN_MAINLAND_BORDER));
        area.add(new Area(buildPolygonPath(PHU_QUOC)));
        area.add(new Area(buildPolygonPath(CON_DAO)));

        // Vùng biển chủ quyền trong trẻo của Quần đảo Hoàng Sa (Việt Nam)
        Point2D.Double hs = latLngToScreen(16.5, 112.0);
        Point2D.Double hsEdge = latLngToScreen(16.5, 112.85);
        double hsR = Math.max(24.0, Math.abs(hsEdge.x - hs.x));
        area.add(new Area(new Ellipse2D.Double(hs.x - hsR, hs.y - hsR * 0.9, hsR * 2, hsR * 1.8)));

        // Vùng biển chủ quyền trong trẻo của Quần đảo Trường Sa (Việt Nam)
        Point2D.Double ts = latLngToScreen(9.8, 114.2);
        Point2D.Double tsEdge = latLngToScreen(9.8, 116.0);
        double tsRx = Math.max(34.0, Math.abs(tsEdge.x - ts.x));
        double tsRy = tsRx * 0.75;
        area.add(new Area(new Ellipse2D.Double(ts.x - tsRx, ts.y - tsRy, tsRx * 2, tsRy * 2)));

        return area;
    }

    private Path2D.Double buildPolygonPath(double[][] coords) {
        Path2D.Double path = new Path2D.Double();
        Point2D.Double p0 = latLngToScreen(coords[0][0], coords[0][1]);
        path.moveTo(p0.x, p0.y);
        for (int i = 1; i < coords.length; i++) {
            Point2D.Double p = latLngToScreen(coords[i][0], coords[i][1]);
            path.lineTo(p.x, p.y);
        }
        path.closePath();
        return path;
    }

    private void drawArchipelagoHalo(Graphics2D g2, double lat, double lng, double degRadius) {
        Point2D.Double c = latLngToScreen(lat, lng);
        Point2D.Double edge = latLngToScreen(lat, lng + degRadius);
        double rx = Math.max(24.0, Math.abs(edge.x - c.x));
        double ry = rx * 0.85;

        // Quầng sáng sương mờ êm dịu bao quanh vùng đảo
        g2.setColor(new Color(246, 249, 252, 45));
        g2.setStroke(new BasicStroke(10.0f));
        g2.draw(new Ellipse2D.Double(c.x - rx, c.y - ry, rx * 2, ry * 2));

        // Đường chỉ giới phát sáng hổ phách nét đứt tinh tế
        g2.setColor(new Color(217, 145, 59, 130));
        g2.setStroke(new BasicStroke(1.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND,
                1f, new float[]{5f, 5f}, 0f));
        g2.draw(new Ellipse2D.Double(c.x - rx, c.y - ry, rx * 2, ry * 2));
    }

    private void drawIslandBadge(Graphics2D g2, double lat, double lng, String label) {
        Point2D.Double p = latLngToScreen(lat, lng);
        if (p.x < -150 || p.x > getWidth() + 150 || p.y < -50 || p.y > getHeight() + 50) return;

        g2.setFont(Theme.bold(11));
        FontMetrics fm = g2.getFontMetrics();
        int tw = fm.stringWidth(label);
        int padX = 8, padY = 4;
        int dotSize = 6;
        int bw = tw + padX * 2 + dotSize + 6;
        int bh = fm.getHeight() + padY * 2;
        int bx = (int) Math.round(p.x - bw / 2.0);
        int by = (int) Math.round(p.y - bh / 2.0);

        // Đổ bóng mờ nhẹ
        g2.setColor(new Color(0, 0, 0, 35));
        g2.fillRoundRect(bx + 1, by + 2, bw, bh, 12, 12);

        // Nền thẻ kính mờ trắng ngà sang trọng
        g2.setColor(new Color(255, 255, 255, 230));
        g2.fillRoundRect(bx, by, bw, bh, 12, 12);

        // Viền thẻ màu hổ phách tinh tế
        g2.setColor(new Color(217, 145, 59, 190));
        g2.setStroke(new BasicStroke(1.0f));
        g2.drawRoundRect(bx, by, bw, bh, 12, 12);

        // Chấm tròn định vị chủ quyền
        int dotX = bx + padX;
        int dotY = by + (bh - dotSize) / 2;
        g2.setColor(Theme.AMBER);
        g2.fillOval(dotX, dotY, dotSize, dotSize);

        // Chữ nhãn
        g2.setColor(Theme.TEXT);
        g2.drawString(label, dotX + dotSize + 5, by + padY + fm.getAscent() - 1);
    }

    // Biên giới và duyên hải trọn vẹn dải đất hình chữ S Việt Nam (từ Móng Cái khép kín vòng quanh)
    private static final double[][] VN_MAINLAND_BORDER = {
        // --- 1. Biên giới phía Bắc giáp Trung Quốc (Móng Cái -> A Pa Chải) ---
        {21.53, 107.97}, // Móng Cái / Trà Cổ (Quảng Ninh)
        {21.55, 107.75}, // Hải Hà
        {21.53, 107.38}, // Bình Liêu
        {21.55, 107.12}, // Đình Lập (Lạng Sơn)
        {21.72, 106.95}, // Lộc Bình
        {21.95, 106.72}, // Cao Lộc
        {22.08, 106.60}, // Đồng Đăng / Hữu Nghị
        {22.35, 106.46}, // Tràng Định
        {22.50, 106.52}, // Thạch An (Cao Bằng)
        {22.65, 106.60}, // Phục Hòa
        {22.86, 106.63}, // Trùng Khánh (Thác Bản Giốc)
        {22.87, 106.32}, // Trà Lĩnh
        {22.98, 106.05}, // Hà Quảng (Pác Bó)
        {22.95, 105.75}, // Bảo Lạc
        {23.00, 105.48}, // Mèo Vạc (Hà Giang)
        {23.22, 105.43}, // Đồng Văn
        {23.39, 105.32}, // Lũng Cú (Cực Bắc)
        {23.15, 105.15}, // Quản Bạ
        {23.08, 104.98}, // Vị Xuyên
        {22.92, 104.85}, // Hoàng Su Phì
        {22.78, 104.55}, // Xín Mần
        {22.75, 104.42}, // Si Ma Cai (Lào Cai)
        {22.72, 104.28}, // Mường Khương
        {22.68, 104.12}, // Bát Xát
        {22.52, 103.96}, // TP Lào Cai
        {22.63, 103.65}, // Phong Thổ (Lai Châu)
        {22.62, 103.58}, // Dào San
        {22.55, 103.35}, // Mường Tè
        {22.45, 103.20}, // Ka Lăng
        {22.35, 102.85}, // Thu Lũm
        {22.56, 102.50}, // Mường Nhé (Điện Biên)
        {22.63, 102.35}, // Sín Thầu
        {22.40, 102.14}, // A Pa Chải (Cực Tây)

        // --- 2. Biên giới phía Tây giáp Lào ---
        {22.15, 102.25}, // Mường Chà (Điện Biên)
        {21.78, 102.75}, // Mường Ảng
        {21.22, 102.98}, // Điện Biên Đông / Tây Trang
        {21.10, 103.25}, // Sốp Cộp (Sơn La)
        {20.95, 103.55}, // Sông Mã
        {21.05, 103.75}, // Chiềng Khương
        {20.90, 104.30}, // Mộc Châu / Lóng Sập
        {20.72, 104.62}, // Mường Lát (Thanh Hóa)
        {20.51, 104.62}, // Quan Sơn / Na Mèo
        {20.28, 104.95}, // Bát Mọt
        {19.98, 105.02}, // Thường Xuân
        {19.82, 104.91}, // Quế Phong (Nghệ An)
        {19.45, 104.55}, // Tương Dương
        {19.42, 104.08}, // Kỳ Sơn (Nậm Cắn)
        {18.95, 104.75}, // Con Cuông
        {18.82, 104.98}, // Anh Sơn
        {18.65, 105.15}, // Thanh Chương
        {18.39, 105.18}, // Hương Sơn (Hà Tĩnh / Cầu Treo)
        {18.25, 105.35}, // Vũ Quang
        {18.05, 105.65}, // Hương Khê
        {17.85, 105.80}, // Tuyên Hóa (Quảng Bình)
        {17.68, 105.76}, // Minh Hóa (Cha Lo)
        {17.45, 106.15}, // Bố Trạch
        {17.20, 106.40}, // Quảng Ninh
        {16.95, 106.65}, // Lệ Thủy
        {16.61, 106.59}, // Hướng Hóa (Quảng Trị / Lao Bảo)
        {16.45, 106.95}, // Đakrông
        {16.27, 107.25}, // A Lưới (Thừa Thiên Huế)
        {16.05, 107.55}, // Nam Đông
        {15.90, 107.45}, // Tây Giang (Quảng Nam)
        {15.58, 107.41}, // Nam Giang
        {15.30, 107.65}, // Đắk Glei (Kon Tum)
        {15.05, 107.72}, // Ngọc Hồi
        {14.71, 107.56}, // Bờ Y (Ngã 3 Đông Dương)

        // --- 3. Biên giới Tây Nam giáp Campuchia ---
        {14.30, 107.52}, // Sa Thầy (Kon Tum)
        {13.98, 107.50}, // Ia Grai (Gia Lai)
        {13.78, 107.54}, // Đức Cơ (Lệ Thanh)
        {13.45, 107.52}, // Chư Prông
        {13.20, 107.53}, // Ea Súp (Đắk Lắk)
        {12.85, 107.52}, // Buôn Đôn
        {12.65, 107.60}, // Cư Jút (Đắk Nông)
        {12.45, 107.55}, // Đắk Mil
        {12.25, 107.48}, // Đắk Song
        {12.10, 107.35}, // Tuy Đức
        {11.98, 107.10}, // Bù Gia Mập (Bình Phước)
        {11.85, 106.59}, // Bù Đốp
        {11.60, 106.25}, // Lộc Ninh (Hoa Lư)
        {11.60, 105.97}, // Tân Châu (Tây Ninh)
        {11.35, 105.95}, // Tân Biên (Xa Mát)
        {11.08, 106.18}, // Châu Thành
        {11.02, 106.32}, // Bến Cầu (Mộc Bài)
        {10.79, 105.93}, // Đức Huệ (Long An)
        {10.88, 105.75}, // Mộc Hóa
        {10.82, 105.28}, // Tân Hưng / Hồng Ngự (Đồng Tháp)
        {10.85, 105.10}, // Tân Châu (An Giang)
        {10.60, 104.94}, // An Phú
        {10.45, 104.60}, // Tịnh Biên
        {10.38, 104.48}, // Hà Tiên (Kiên Giang)

        // --- 4. Bờ biển miền Nam & Đồng bằng Sông Cửu Long ---
        {10.20, 104.55}, // Kiên Lương / Hòn Chông
        {10.00, 105.08}, // Rạch Giá
        {9.60, 104.90},  // An Minh
        {9.20, 104.80},  // U Minh (Cà Mau)
        {8.56, 104.72},  // Mũi Cà Mau (Cực Nam)
        {8.62, 105.15},  // Đầm Dơi
        {9.15, 105.65},  // Bạc Liêu
        {9.28, 105.75},  // Vĩnh Châu
        {9.45, 106.15},  // Cửa Trần Đề (Sóc Trăng)
        {9.70, 106.50},  // Duyên Hải (Trà Vinh)
        {9.95, 106.65},  // Ba Tri (Bến Tre)
        {10.15, 106.78}, // Bình Đại
        {10.30, 106.75}, // Gò Công Đông (Tiền Giang)
        {10.42, 106.92}, // Cần Giờ (TP. Hồ Chí Minh)
        {10.35, 107.08}, // TP. Vũng Tàu
        {10.40, 107.25}, // Long Hải
        {10.50, 107.50}, // Bình Châu / Xuyên Mộc

        // --- 5. Bờ biển Nam Trung Bộ & Duyên hải miền Trung ---
        {10.65, 107.75}, // La Gi (Bình Thuận)
        {10.70, 107.98}, // Mũi Kê Gà
        {10.92, 108.10}, // Phan Thiết
        {10.95, 108.30}, // Mũi Né
        {11.20, 108.60}, // Phan Rí Cửa / Tuy Phong
        {11.35, 109.00}, // Cà Ná (Ninh Thuận)
        {11.55, 109.05}, // Phan Rang
        {11.72, 109.22}, // Vịnh Vĩnh Hy
        {11.90, 109.18}, // Vịnh Cam Ranh (Khánh Hòa)
        {12.25, 109.20}, // TP. Nha Trang
        {12.45, 109.25}, // Ninh Hòa / Vịnh Vân Phong
        {12.65, 109.46}, // Mũi Đôi / Đầm Môn (Cực Đông)
        {12.85, 109.40}, // Vịnh Vũng Rô / Đèo Cả (Phú Yên)
        {13.08, 109.32}, // TP. Tuy Hòa
        {13.45, 109.22}, // Sông Cầu
        {13.77, 109.23}, // TP. Quy Nhơn (Bình Định)
        {14.10, 109.10}, // Phù Cát
        {14.45, 109.05}, // Hoài Nhơn
        {14.80, 108.95}, // Sa Huỳnh (Quảng Ngãi)
        {15.15, 108.85}, // TP. Quảng Ngãi
        {15.35, 108.75}, // Dung Quất
        {15.55, 108.65}, // Tam Kỳ (Quảng Nam)
        {15.88, 108.38}, // Hội An / Cửa Đại
        {16.08, 108.25}, // Bán đảo Sơn Trà (Đà Nẵng)
        {16.15, 108.15}, // Vịnh Đà Nẵng / Đèo Hải Vân
        {16.25, 108.00}, // Lăng Cô (Thừa Thiên Huế)
        {16.55, 107.60}, // Phá Tam Giang / Thuận An
        {16.90, 107.18}, // Cửa Việt (Quảng Trị)
        {17.05, 107.10}, // Cửa Tùng
        {17.50, 106.60}, // TP. Đồng Hới (Quảng Bình)
        {17.92, 106.45}, // Đèo Ngang / Vũng Áng
        {18.10, 106.35}, // Kỳ Anh (Hà Tĩnh)
        {18.35, 106.00}, // Thiên Cầm
        {18.65, 105.80}, // Xuân Thành
        {18.80, 105.72}, // Cửa Lò (Nghệ An)
        {19.05, 105.70}, // Diễn Châu
        {19.25, 105.75}, // Quỳnh Lưu
        {19.45, 105.80}, // Nghi Sơn (Thanh Hóa)
        {19.75, 105.90}, // Sầm Sơn
        {19.98, 106.00}, // Nga Sơn

        // --- 6. Vịnh Bắc Bộ & Duyên hải Bắc Bộ ---
        {20.02, 106.08}, // Kim Sơn (Ninh Bình)
        {20.15, 106.30}, // Hải Hậu (Nam Định)
        {20.25, 106.55}, // Giao Thủy / Vườn Quốc Gia Xuân Thủy
        {20.40, 106.60}, // Tiền Hải (Thái Bình)
        {20.70, 106.80}, // Đồ Sơn (Hải Phòng)
        {20.80, 107.05}, // Quần đảo Cát Bà
        {20.95, 107.10}, // Vịnh Hạ Long (Quảng Ninh)
        {21.00, 107.35}, // Cẩm Phả / Vịnh Bái Tử Long
        {21.15, 107.50}, // Vân Đồn
        {21.35, 107.80}, // Tiên Yên / Đầm Hà
        {21.53, 107.97}  // Khép kín trở lại Móng Cái / Trà Cổ!
    };

    // Đảo Phú Quốc (Kiên Giang)
    private static final double[][] PHU_QUOC = {
        {10.45, 103.95}, {10.38, 104.05}, {10.15, 104.05}, {9.98, 104.02},
        {10.05, 103.90}, {10.25, 103.85}, {10.40, 103.88}
    };

    // Quần đảo Côn Đảo (Bà Rịa - Vũng Tàu)
    private static final double[][] CON_DAO = {
        {8.78, 106.60}, {8.74, 106.68}, {8.65, 106.65}, {8.65, 106.55}, {8.72, 106.55}
    };
}

