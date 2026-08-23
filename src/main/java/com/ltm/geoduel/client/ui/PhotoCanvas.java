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
 * Khung MỘT ảnh phẳng tràn màn hình: lăn chuột để phóng to soi chi tiết
 * (biển hiệu, biển số, kiến trúc), kéo để di chuyển khi đã phóng, nhấp đúp thu về.
 * Bộ ảnh manh mối và việc chuyển ảnh do MatchPanel quản lý (chung với ảnh 360°).
 */
public class PhotoCanvas extends JComponent {
    private BufferedImage image;

    // khung nhìn: scale = px màn hình / px ảnh; (viewX, viewY) = px ảnh tại góc trái trên
    private double scale = 0, viewX = 0, viewY = 0;
    private boolean autoFit = true;

    private Point dragStart;
    private double dragViewX, dragViewY;

    public PhotoCanvas() {
        setOpaque(true);
        MouseAdapter mouse = new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                dragStart = e.getPoint();
                dragViewX = viewX;
                dragViewY = viewY;
            }
            @Override public void mouseDragged(MouseEvent e) {
                if (dragStart == null || image == null) return;
                viewX = dragViewX - (e.getX() - dragStart.x) / scale;
                viewY = dragViewY - (e.getY() - dragStart.y) / scale;
                clampView();
                repaint();
            }
            @Override public void mouseReleased(MouseEvent e) { dragStart = null; }
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) { autoFit = true; repaint(); }
            }
            @Override public void mouseWheelMoved(MouseWheelEvent e) {
                if (image == null) return;
                double fit = fitScale();
                double old = scale <= 0 ? fit : scale;
                double next = Math.max(fit, Math.min(fit * 8, old * (e.getWheelRotation() < 0 ? 1.3 : 1 / 1.3)));
                autoFit = false;
                double ix = viewX + e.getX() / old;
                double iy = viewY + e.getY() / old;
                scale = next;
                viewX = ix - e.getX() / next;
                viewY = iy - e.getY() / next;
                clampView();
                setCursor(Cursor.getPredefinedCursor(
                        next > fit * 1.01 ? Cursor.MOVE_CURSOR : Cursor.DEFAULT_CURSOR));
                repaint();
            }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
        addMouseWheelListener(mouse);
    }

    /** Hiển thị ảnh mới (null = trạng thái đang tải), tự thu về vừa khung. */
    public void setImage(BufferedImage img) {
        this.image = img;
        this.autoFit = true;
        setCursor(Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR));
        repaint();
    }

    private double fitScale() {
        if (getWidth() == 0 || image == null) return 1;
        return Math.min((double) getWidth() / image.getWidth(), (double) getHeight() / image.getHeight());
    }

    private void clampView() {
        if (image == null || scale <= 0) return;
        double vw = getWidth() / scale, vh = getHeight() / scale;
        if (vw >= image.getWidth()) viewX = (image.getWidth() - vw) / 2;
        else viewX = Math.max(0, Math.min(image.getWidth() - vw, viewX));
        if (vh >= image.getHeight()) viewY = (image.getHeight() - vh) / 2;
        else viewY = Math.max(0, Math.min(image.getHeight() - vh, viewY));
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = Theme.prep(g);
        g2.setColor(Theme.PHOTO_BG);
        g2.fillRect(0, 0, getWidth(), getHeight());
        if (image == null) {
            g2.setColor(Theme.ON_PHOTO);
            g2.setFont(Theme.font(15));
            String s = "Đang tải ảnh manh mối...";
            FontMetrics fm = g2.getFontMetrics();
            g2.drawString(s, (getWidth() - fm.stringWidth(s)) / 2, getHeight() / 2);
            return;
        }
        if (autoFit || scale <= 0) {
            scale = fitScale();
            viewX = (image.getWidth() - getWidth() / scale) / 2;
            viewY = (image.getHeight() - getHeight() / scale) / 2;
            clampView();
        }
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        java.awt.geom.AffineTransform tf = new java.awt.geom.AffineTransform();
        tf.scale(scale, scale);
        tf.translate(-viewX, -viewY);
        g2.drawImage(image, tf, null);
    }
}
