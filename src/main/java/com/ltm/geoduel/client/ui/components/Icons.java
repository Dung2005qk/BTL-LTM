package com.ltm.geoduel.client.ui.components;

import com.ltm.geoduel.client.ui.Theme;

import javax.swing.Icon;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.geom.GeneralPath;

/** Icon vẽ bằng Java2D — không dùng emoji, không dùng file ảnh ngoài. */
public final class Icons {
    private Icons() {}

    /** La bàn — biểu tượng của game. */
    public static Icon compass(int size, Color ring, Color needle) {
        return new Icon() {
            public int getIconWidth() { return size; }
            public int getIconHeight() { return size; }
            public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2 = Theme.prep(g.create());
                float s = size;
                g2.translate(x, y);
                g2.setStroke(new BasicStroke(Math.max(1.6f, s / 16f)));
                g2.setColor(ring);
                g2.drawOval((int) (s * .06), (int) (s * .06), (int) (s * .88), (int) (s * .88));
                // kim la bàn: nửa sáng + nửa tối, xoay nhẹ 45 độ
                GeneralPath north = new GeneralPath();
                north.moveTo(s * .70, s * .30);
                north.lineTo(s * .44, s * .52);
                north.lineTo(s * .55, s * .56);
                north.closePath();
                GeneralPath south = new GeneralPath();
                south.moveTo(s * .30, s * .70);
                south.lineTo(s * .56, s * .48);
                south.lineTo(s * .45, s * .44);
                south.closePath();
                g2.setColor(needle);
                g2.fill(north);
                g2.setColor(Theme.mix(needle, Theme.BG, 0.45f));
                g2.fill(south);
                g2.dispose();
            }
        };
    }

    /** Ghim vị trí kiểu giọt nước. */
    public static Icon pin(int size, Color color) {
        return new Icon() {
            public int getIconWidth() { return size; }
            public int getIconHeight() { return size; }
            public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2 = Theme.prep(g.create());
                g2.translate(x, y);
                float s = size;
                GeneralPath p = new GeneralPath();
                p.moveTo(s * .5, s * .95);
                p.curveTo(s * .18, s * .55, s * .18, s * .12, s * .5, s * .12);
                p.curveTo(s * .82, s * .12, s * .82, s * .55, s * .5, s * .95);
                g2.setColor(color);
                g2.fill(p);
                g2.setColor(Theme.BG);
                g2.fillOval((int) (s * .38), (int) (s * .26), (int) (s * .24), (int) (s * .24));
                g2.dispose();
            }
        };
    }

    /** Chấm trạng thái rảnh/bận. */
    public static Icon dot(int size, Color color) {
        return new Icon() {
            public int getIconWidth() { return size; }
            public int getIconHeight() { return size; }
            public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2 = Theme.prep(g.create());
                g2.setColor(Theme.withAlpha(color, 70));
                g2.fillOval(x, y, size, size);
                g2.setColor(color);
                int inner = size - 6;
                g2.fillOval(x + 3, y + 3, inner, inner);
                g2.dispose();
            }
        };
    }

    /** Loa bật/tắt tiếng (gạch chéo khi tắt). */
    public static Icon speaker(int size, Color color, boolean muted) {
        return new Icon() {
            public int getIconWidth() { return size; }
            public int getIconHeight() { return size; }
            public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2 = Theme.prep(g.create());
                g2.translate(x, y);
                float s = size;
                GeneralPath body = new GeneralPath();
                body.moveTo(s * .18, s * .38);
                body.lineTo(s * .34, s * .38);
                body.lineTo(s * .52, s * .22);
                body.lineTo(s * .52, s * .78);
                body.lineTo(s * .34, s * .62);
                body.lineTo(s * .18, s * .62);
                body.closePath();
                g2.setColor(color);
                g2.fill(body);
                g2.setStroke(new BasicStroke(Math.max(1.6f, s / 14f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                if (!muted) {
                    g2.drawArc((int) (s * .48), (int) (s * .3), (int) (s * .26), (int) (s * .4), -55, 110);
                } else {
                    g2.setColor(Theme.RED);
                    g2.drawLine((int) (s * .2), (int) (s * .82), (int) (s * .82), (int) (s * .18));
                }
                g2.dispose();
            }
        };
    }

    /** Mũi tên trái/phải cho trình xem ảnh. */
    public static Icon arrow(int size, boolean right, Color color) {
        return new Icon() {
            public int getIconWidth() { return size; }
            public int getIconHeight() { return size; }
            public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2 = Theme.prep(g.create());
                g2.translate(x, y);
                float s = size;
                GeneralPath p = new GeneralPath();
                if (right) {
                    p.moveTo(s * .35, s * .2);
                    p.lineTo(s * .68, s * .5);
                    p.lineTo(s * .35, s * .8);
                } else {
                    p.moveTo(s * .65, s * .2);
                    p.lineTo(s * .32, s * .5);
                    p.lineTo(s * .65, s * .8);
                }
                g2.setStroke(new BasicStroke(Math.max(2f, s / 10f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g2.setColor(color);
                g2.draw(p);
                g2.dispose();
            }
        };
    }
}
