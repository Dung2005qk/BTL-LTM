package com.ltm.geoduel.tools;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

/**
 * Đánh giá nhanh chất lượng ảnh manh mối:
 *  - độ nét: phương sai Laplacian trên ảnh xám thu nhỏ (mờ/rung → giá trị thấp)
 *  - độ sáng trung bình (quá tối/quá cháy → khó suy đoán)
 * Dùng để collector loại ảnh kém trước khi đưa vào game.
 *
 * Chạy trực tiếp để hiệu chỉnh ngưỡng: in điểm số của toàn bộ assets/raw.
 */
public final class ImageQuality {
    /**
     * Ảnh phải đạt tối thiểu các ngưỡng này. Hiệu chỉnh 2026-08-23 trên 103 ảnh thật:
     * nhóm mờ/rung nằm ở 26–53, ảnh dùng được hầu hết ≥ 950 → chọn 100 làm ranh an toàn.
     */
    public static final double MIN_SHARPNESS = 100.0;
    public static final double MIN_BRIGHTNESS = 35.0;
    public static final double MAX_BRIGHTNESS = 220.0;

    private ImageQuality() {}

    public record Score(double sharpness, double brightness) {
        public boolean acceptable() {
            return sharpness >= MIN_SHARPNESS
                && brightness >= MIN_BRIGHTNESS && brightness <= MAX_BRIGHTNESS;
        }
    }

    /** @return null nếu không giải mã được ảnh. */
    public static Score evaluate(byte[] jpegBytes) {
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(jpegBytes));
            if (img == null) return null;
            return evaluate(img);
        } catch (Exception ex) {
            return null;
        }
    }

    public static Score evaluate(BufferedImage src) {
        // thu nhỏ về ~320 px cho nhanh và ổn định giữa các cỡ ảnh
        int w = 320;
        int h = Math.max(1, src.getHeight() * w / Math.max(1, src.getWidth()));
        BufferedImage gray = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = gray.createGraphics();
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();

        byte[] px = ((java.awt.image.DataBufferByte) gray.getRaster().getDataBuffer()).getData();
        long sum = 0;
        for (byte b : px) sum += b & 0xFF;
        double brightness = (double) sum / px.length;

        // Laplacian 4 hướng, tính phương sai
        double lapSum = 0, lapSqSum = 0;
        int n = 0;
        for (int y = 1; y < h - 1; y++) {
            for (int x = 1; x < w - 1; x++) {
                int c = px[y * w + x] & 0xFF;
                int lap = 4 * c - (px[y * w + x - 1] & 0xFF) - (px[y * w + x + 1] & 0xFF)
                        - (px[(y - 1) * w + x] & 0xFF) - (px[(y + 1) * w + x] & 0xFF);
                lapSum += lap;
                lapSqSum += (double) lap * lap;
                n++;
            }
        }
        double mean = lapSum / n;
        double variance = lapSqSum / n - mean * mean;
        return new Score(variance, brightness);
    }

    /** Hiệu chỉnh ngưỡng: in điểm của mọi ảnh trong assets/raw, sắp theo độ nét tăng dần. */
    public static void main(String[] args) throws Exception {
        Path raw = Path.of("assets", "raw");
        record Row(String file, Score score) {}
        List<Row> rows;
        try (var stream = Files.walk(raw)) {
            rows = stream.filter(p -> p.toString().toLowerCase().endsWith(".jpg"))
                    .map(p -> {
                        try {
                            return new Row(raw.relativize(p).toString(), evaluate(Files.readAllBytes(p)));
                        } catch (Exception e) {
                            return new Row(p.toString(), null);
                        }
                    })
                    .filter(r -> r.score != null)
                    .sorted(Comparator.comparingDouble(r -> r.score.sharpness))
                    .toList();
        }
        for (Row r : rows) {
            System.out.printf("%8.1f  sang=%5.1f  %s  %s%n", r.score.sharpness, r.score.brightness,
                    r.score.acceptable() ? "OK " : "LOAI", r.file);
        }
        System.out.println("Tong " + rows.size() + " anh; nguong hien tai: sharp>=" + MIN_SHARPNESS
                + ", sang trong [" + MIN_BRIGHTNESS + ", " + MAX_BRIGHTNESS + "]");
    }
}
