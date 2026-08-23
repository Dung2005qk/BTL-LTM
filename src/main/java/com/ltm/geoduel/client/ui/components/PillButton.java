package com.ltm.geoduel.client.ui.components;

import com.ltm.geoduel.client.ui.Theme;

import javax.swing.JButton;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;

/**
 * Nút dạng viên thuốc tự vẽ, KHÔNG viền kẻ (viền 1 px bị nhoè trên màn hình
 * scale 125/150%) — thay bằng nền mềm: PRIMARY (đặc hổ phách), GHOST (nền xám ấm nhạt),
 * DANGER (nền đỏ nhạt), TAB (trong suốt, chọn thì nền hổ phách nhạt — dùng setSelected).
 * Có trạng thái hover/pressed/disabled và vòng focus nhìn thấy được.
 */
public class PillButton extends JButton {
    public enum Kind { PRIMARY, GHOST, DANGER, TAB }

    private final Kind kind;

    public PillButton(String text, Kind kind) {
        super(text);
        this.kind = kind;
        setOpaque(false);
        setContentAreaFilled(false);
        setBorderPainted(false);
        setFocusPainted(false);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        setFont(Theme.bold(14));
        setRolloverEnabled(true);
    }

    @Override
    public Dimension getPreferredSize() {
        FontMetrics fm = getFontMetrics(getFont());
        return new Dimension(fm.stringWidth(getText()) + 44, fm.getHeight() + 18);
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = Theme.prep(g);
        int w = getWidth(), h = getHeight();
        int arc = h - 2;
        boolean hover = getModel().isRollover();
        boolean pressed = getModel().isPressed();
        boolean enabled = isEnabled();

        Color bg = null, fg;
        switch (kind) {
            case PRIMARY -> {
                bg = !enabled ? Theme.mix(Theme.AMBER, Theme.SURFACE, 0.6f)
                        : pressed ? Theme.AMBER_DARK
                        : hover ? Theme.mix(Theme.AMBER, Color.WHITE, 0.14f)
                        : Theme.AMBER;
                fg = !enabled ? Theme.withAlpha(Theme.INK, 130) : Color.WHITE;
            }
            case DANGER -> {
                bg = Theme.withAlpha(Theme.RED, !enabled ? 14 : pressed ? 60 : hover ? 44 : 26);
                fg = !enabled ? Theme.withAlpha(Theme.RED, 120) : Theme.mix(Theme.RED, Theme.INK, 0.15f);
            }
            case TAB -> {
                if (isSelected()) {
                    bg = Theme.withAlpha(Theme.AMBER, pressed ? 90 : 70);
                    fg = Theme.mix(Theme.AMBER_DARK, Theme.INK, 0.35f);
                } else {
                    if (hover && enabled) bg = Theme.withAlpha(Theme.INK, 12);
                    fg = Theme.TEXT_MUTED;
                }
            }
            default -> {
                bg = Theme.withAlpha(Theme.INK, !enabled ? 6 : pressed ? 30 : hover ? 22 : 13);
                fg = !enabled ? Theme.TEXT_FAINT : Theme.TEXT;
            }
        }
        if (bg != null) {
            g2.setColor(bg);
            g2.fillRoundRect(0, 0, w, h, arc, arc);
        }
        if (isFocusOwner()) {
            g2.setColor(Theme.withAlpha(Theme.AMBER_DARK, 170));
            g2.drawRoundRect(1, 1, w - 3, h - 3, arc - 2, arc - 2);
        }
        FontMetrics fm = g2.getFontMetrics(getFont());
        g2.setFont(getFont());
        g2.setColor(fg);
        g2.drawString(getText(), (w - fm.stringWidth(getText())) / 2,
                (h - fm.getHeight()) / 2 + fm.getAscent());
    }
}
