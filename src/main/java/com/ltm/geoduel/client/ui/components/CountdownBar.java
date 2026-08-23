package com.ltm.geoduel.client.ui.components;

import com.ltm.geoduel.client.ui.Theme;

import javax.swing.JComponent;
import javax.swing.Timer;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;

/**
 * Thanh đếm ngược của lượt chơi (chỉ để hiển thị — thời gian chuẩn do server quản lý).
 * Chuyển dần từ hổ phách sang đỏ khi còn dưới 10 giây.
 */
public class CountdownBar extends JComponent {
    private long deadlineMs = 0;
    private int durationSec = 30;
    private final Timer ticker = new Timer(100, e -> repaint());

    public CountdownBar() {
        setPreferredSize(new Dimension(220, 30));
    }

    public void start(int durationSec) {
        this.durationSec = durationSec;
        this.deadlineMs = System.currentTimeMillis() + durationSec * 1000L;
        ticker.start();
        repaint();
    }

    public void stop() {
        ticker.stop();
        deadlineMs = 0;
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = Theme.prep(g);
        int w = getWidth(), h = getHeight();
        int barH = 6;
        int textH = h - barH - 4;

        double remainMs = deadlineMs == 0 ? 0 : Math.max(0, deadlineMs - System.currentTimeMillis());
        double frac = durationSec <= 0 ? 0 : Math.min(1.0, remainMs / (durationSec * 1000.0));
        int remainSec = (int) Math.ceil(remainMs / 1000.0);

        // chữ mm:ss
        String label = String.format("00:%02d", Math.min(99, remainSec));
        g2.setFont(Theme.monoBold(16));
        FontMetrics fm = g2.getFontMetrics();
        boolean urgent = remainSec <= 10 && deadlineMs != 0;
        g2.setColor(deadlineMs == 0 ? Theme.TEXT_FAINT : urgent ? Theme.RED : Theme.TEXT);
        g2.drawString(label, (w - fm.stringWidth(label)) / 2, textH / 2 + fm.getAscent() / 2);

        // thanh
        g2.setColor(Theme.BORDER_SOFT);
        g2.fillRoundRect(0, h - barH, w, barH, barH, barH);
        if (deadlineMs != 0) {
            g2.setColor(urgent ? Theme.RED : Theme.mix(Theme.RED, Theme.AMBER, (float) frac));
            g2.fillRoundRect(0, h - barH, (int) (w * frac), barH, barH, barH);
        }
    }
}
