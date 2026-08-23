package com.ltm.geoduel.client.ui.components;

import com.ltm.geoduel.client.ui.Theme;

import javax.swing.JPanel;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.LayoutManager;

/**
 * Panel nền bo góc — khối xây dựng của mọi màn hình. Viền là tuỳ chọn;
 * có thể bật bóng đổ mềm (vài lớp mờ) cho thẻ nổi trên nền.
 */
public class RoundedPanel extends JPanel {
    private final Color fill;
    private final Color border;
    private final int radius;
    private final boolean shadow;

    public RoundedPanel(LayoutManager layout) {
        this(layout, Theme.SURFACE, Theme.BORDER_SOFT);
    }

    public RoundedPanel(LayoutManager layout, Color fill, Color border) {
        this(layout, fill, border, Theme.RADIUS, false);
    }

    public RoundedPanel(LayoutManager layout, Color fill, Color border, int radius, boolean shadow) {
        super(layout);
        this.fill = fill;
        this.border = border;
        this.radius = radius;
        this.shadow = shadow;
        setOpaque(false);
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = Theme.prep(g);
        int w = getWidth(), h = getHeight();
        int inset = shadow ? 6 : 0;
        if (shadow) {
            for (int i = 0; i < 5; i++) {
                g2.setColor(Theme.withAlpha(Theme.INK, 9 - i));
                g2.fillRoundRect(inset - i, inset - i + 2, w - 2 * inset + 2 * i,
                        h - 2 * inset + 2 * i, radius + i * 2, radius + i * 2);
            }
        }
        g2.setColor(fill);
        g2.fillRoundRect(inset, inset, w - 2 * inset - (shadow ? 0 : 1),
                h - 2 * inset - (shadow ? 2 : 1), radius, radius);
        if (border != null) {
            g2.setColor(border);
            g2.drawRoundRect(inset, inset, w - 2 * inset - (shadow ? 0 : 1),
                    h - 2 * inset - (shadow ? 2 : 1), radius, radius);
        }
        super.paintComponent(g);
    }
}
