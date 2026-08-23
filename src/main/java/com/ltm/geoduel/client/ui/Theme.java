package com.ltm.geoduel.client.ui;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.GraphicsEnvironment;
import java.util.Arrays;

/**
 * Hệ màu & chữ của GeoDuel — chủ đề "bản đồ thám hiểm ban ngày":
 * nền giấy ấm sáng (đồng bộ với bản đồ Carto Voyager), thẻ trắng, chữ mực đậm,
 * nhấn hổ phách đồng (mình) và xanh ngọc (đối thủ).
 * Mọi component tự vẽ đều lấy hằng số từ đây, không hard-code màu rải rác.
 */
public final class Theme {
    private Theme() {}

    // Nền sáng
    public static final Color BG        = new Color(0xF2EDE3); // giấy ấm
    public static final Color SURFACE   = new Color(0xFBF8F1); // thẻ
    public static final Color ELEVATED  = new Color(0xFFFFFF); // ô nhập, hàng nổi
    public static final Color BORDER    = new Color(0xD8CFBC);
    public static final Color BORDER_SOFT = new Color(0xE6DFCE);

    // Chữ mực
    public static final Color TEXT      = new Color(0x2A3138);
    public static final Color TEXT_MUTED = new Color(0x66707D);
    public static final Color TEXT_FAINT = new Color(0x9AA2AC);

    // Màu nhận diện
    public static final Color AMBER     = new Color(0xD9913B); // mình / hành động chính
    public static final Color AMBER_DARK = new Color(0xB9772A);
    public static final Color TEAL      = new Color(0x2F8B8E); // đối thủ
    public static final Color RED       = new Color(0xC0524A); // nguy hiểm / thoát
    public static final Color GREEN     = new Color(0x4F8B47); // thắng / trạng thái rảnh
    public static final Color INK       = new Color(0x10151C); // chữ trên nền hổ phách

    // Vùng ảnh (letterbox quanh ảnh manh mối luôn tối cho nổi ảnh)
    public static final Color PHOTO_BG  = new Color(0x14181E);
    public static final Color ON_PHOTO  = new Color(0xC9CDD3); // chữ đặt trên nền ảnh tối

    public static final int RADIUS = 12;

    private static final String UI_FONT = pickFont("Segoe UI", "SansSerif");
    private static final String UI_FONT_SEMIBOLD = pickFont("Segoe UI Semibold", UI_FONT);
    private static final String MONO_FONT = pickFont("Consolas", "Monospaced");

    public static Font font(int size)      { return new Font(UI_FONT, Font.PLAIN, size); }
    public static Font bold(int size)      { return new Font(UI_FONT_SEMIBOLD, Font.PLAIN, size); }
    public static Font heavy(int size)     { return new Font(UI_FONT, Font.BOLD, size); }
    public static Font mono(int size)      { return new Font(MONO_FONT, Font.PLAIN, size); }
    public static Font monoBold(int size)  { return new Font(MONO_FONT, Font.BOLD, size); }

    private static String pickFont(String preferred, String fallback) {
        String[] available = GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getAvailableFontFamilyNames();
        return Arrays.asList(available).contains(preferred) ? preferred : fallback;
    }

    /** Bật khử răng cưa cho cả hình và chữ. */
    public static Graphics2D prep(java.awt.Graphics g) {
        Graphics2D g2 = (Graphics2D) g;
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
        return g2;
    }

    /** Trộn hai màu theo tỉ lệ t (0 → a, 1 → b). */
    public static Color mix(Color a, Color b, float t) {
        t = Math.max(0f, Math.min(1f, t));
        return new Color(
                (int) (a.getRed() + (b.getRed() - a.getRed()) * t),
                (int) (a.getGreen() + (b.getGreen() - a.getGreen()) * t),
                (int) (a.getBlue() + (b.getBlue() - a.getBlue()) * t));
    }

    public static Color withAlpha(Color c, int alpha) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), alpha);
    }
}
