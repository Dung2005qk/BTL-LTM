package com.ltm.geoduel.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ltm.geoduel.common.Log;
import com.ltm.geoduel.server.PasswordHasher;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Properties;

/**
 * Nạp dữ liệu địa điểm vào game:
 *  1. Đọc assets/raw/manifest.json (kết quả của KartaViewCollector).
 *  2. RE-ENCODE từng ảnh JPEG (ImageIO đọc điểm ảnh rồi ghi mới → xoá sạch EXIF/GPS,
 *     đúng yêu cầu không để lộ toạ độ trong metadata), thu nhỏ tối đa 1280 px,
 *     ghi vào assets/locations/<slug>/<n>.jpg.
 *  3. Ghi bảng locations + location_images vào MỌI database liệt kê trong tham số
 *     (mặc định: config.properties và config-test.properties nếu có).
 *
 * Chạy: java -cp target/geoduel.jar com.ltm.geoduel.tools.AssetSeeder [--demo-users] [configPath...]
 * (--demo-users: tạo 4 tài khoản demo1..demo4, mật khẩu 123456, ở database chính đầu tiên)
 */
public final class AssetSeeder {
    private static final int MAX_DIMENSION = 1280;
    private static final float JPEG_QUALITY = 0.82f;

    public static void main(String[] args) throws Exception {
        boolean demoUsers = false;
        List<String> configs = new ArrayList<>();
        for (String a : args) {
            if ("--demo-users".equals(a)) demoUsers = true;
            else configs.add(a);
        }
        if (configs.isEmpty()) {
            configs.add("config.properties");
            if (Files.exists(Path.of("config-test.properties"))) configs.add("config-test.properties");
        }

        Path rawDir = Path.of("assets", "raw");
        Path outBase = Path.of("assets", "locations");
        JsonObject manifest = JsonParser.parseString(
                Files.readString(rawDir.resolve("manifest.json"), StandardCharsets.UTF_8)).getAsJsonObject();
        JsonArray locations = manifest.getAsJsonArray("locations");
        Log.info("Seeder", "Manifest co " + locations.size() + " dia diem");

        // Bước 1+2: xử lý ảnh (chỉ làm với slug chưa có đầu ra).
        List<JsonObject> usable = new ArrayList<>();
        for (JsonElement e : locations) {
            JsonObject loc = e.getAsJsonObject();
            String slug = loc.get("slug").getAsString();
            JsonArray outFiles = processImages(rawDir.resolve(slug), outBase.resolve(slug),
                    loc.getAsJsonArray("files"));
            if (outFiles.size() >= 3) {
                JsonObject copy = loc.deepCopy();
                copy.add("outFiles", outFiles);
                usable.add(copy);
            } else {
                Log.warn("Seeder", slug + ": chi con " + outFiles.size() + " anh hop le, bo qua");
            }
        }
        Log.info("Seeder", usable.size() + " dia diem du dieu kien (>=3 anh)");
        if (usable.size() < 5) throw new IllegalStateException("Qua it dia diem, khong the seed");

        // Bước 3: ghi DB.
        boolean first = true;
        for (String cfg : configs) {
            Properties p = new Properties();
            try (var reader = Files.newBufferedReader(Path.of(cfg), StandardCharsets.UTF_8)) {
                p.load(reader);
            }
            String url = p.getProperty("db.url");
            Log.info("Seeder", "Ghi vao " + url);
            try (Connection c = DriverManager.getConnection(url, p.getProperty("db.user"), p.getProperty("db.password"))) {
                seedDatabase(c, usable);
                if (demoUsers && first) seedDemoUsers(c);
            }
            first = false;
        }
        Log.info("Seeder", "HOAN TAT");
    }

    // ================= ảnh =================

    /**
     * Re-encode toàn bộ ảnh của một địa điểm. Phần tử manifest có thể là chuỗi tên file
     * (ảnh phẳng — dữ liệu KartaView) hoặc object {"f": tên, "pano": true} (Mapillary 360).
     * @return mảng object {"f": tên file đầu ra, "pano": bool}.
     */
    private static JsonArray processImages(Path rawLocDir, Path outLocDir, JsonArray files) {
        JsonArray out = new JsonArray();
        int n = 1;
        for (JsonElement fe : files) {
            String srcName;
            boolean pano;
            if (fe.isJsonObject()) {
                srcName = fe.getAsJsonObject().get("f").getAsString();
                pano = fe.getAsJsonObject().has("pano") && fe.getAsJsonObject().get("pano").getAsBoolean();
            } else {
                srcName = fe.getAsString();
                pano = false;
            }
            Path src = rawLocDir.resolve(srcName);
            String outName = n + ".jpg";
            Path dst = outLocDir.resolve(outName);
            try {
                if (!Files.exists(dst)) {
                    BufferedImage img = ImageIO.read(src.toFile());
                    if (img == null) continue;
                    // ảnh 360 cần độ phân giải cao hơn để xoay xem không vỡ
                    BufferedImage clean = scaleToRgb(img, pano ? 4096 : MAX_DIMENSION);
                    Files.createDirectories(outLocDir);
                    writeJpeg(clean, dst);
                }
                JsonObject o = new JsonObject();
                o.addProperty("f", outName);
                o.addProperty("pano", pano);
                out.add(o);
                n++;
            } catch (IOException ex) {
                Log.warn("Seeder", "Loi anh " + src + ": " + ex.getMessage());
            }
        }
        return out;
    }

    /** Vẽ lại ảnh vào buffer RGB mới (mất mọi metadata), thu nhỏ nếu vượt maxDim px. */
    private static BufferedImage scaleToRgb(BufferedImage src, int maxDim) {
        int w = src.getWidth(), h = src.getHeight();
        double scale = Math.min(1.0, (double) maxDim / Math.max(w, h));
        int nw = (int) Math.round(w * scale), nh = (int) Math.round(h * scale);
        BufferedImage dst = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = dst.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, 0, 0, nw, nh, null);
        g.dispose();
        return dst;
    }

    private static void writeJpeg(BufferedImage img, Path dst) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpg");
        ImageWriter writer = writers.next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(JPEG_QUALITY);
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(dst.toFile())) {
            writer.setOutput(ios);
            writer.write(null, new IIOImage(img, null, null), param);
        } finally {
            writer.dispose();
        }
    }

    // ================= DB =================

    private static void seedDatabase(Connection c, List<JsonObject> locations) throws Exception {
        // vô hiệu hoá các địa điểm không còn trong manifest (ảnh đã bị xoá/thay)
        StringBuilder in = new StringBuilder();
        for (int i = 0; i < locations.size(); i++) in.append(i == 0 ? "?" : ",?");
        try (PreparedStatement st = c.prepareStatement(
                "UPDATE locations SET active = 0 WHERE slug NOT IN (" + in + ")")) {
            for (int i = 0; i < locations.size(); i++) {
                st.setString(i + 1, locations.get(i).get("slug").getAsString());
            }
            int off = st.executeUpdate();
            if (off > 0) Log.info("Seeder", "Vo hieu hoa " + off + " dia diem cu khong con anh");
        }
        int inserted = 0, updated = 0;
        for (JsonObject loc : locations) {
            String slug = loc.get("slug").getAsString();
            Integer id = null;
            try (PreparedStatement st = c.prepareStatement("SELECT id FROM locations WHERE slug = ?")) {
                st.setString(1, slug);
                try (ResultSet rs = st.executeQuery()) {
                    if (rs.next()) id = rs.getInt(1);
                }
            }
            if (id == null) {
                try (PreparedStatement st = c.prepareStatement(
                        "INSERT INTO locations (slug, name, country, target_lat, target_lng, active) VALUES (?,?,?,?,?,1)",
                        java.sql.Statement.RETURN_GENERATED_KEYS)) {
                    st.setString(1, slug);
                    st.setString(2, loc.get("name").getAsString());
                    st.setString(3, loc.get("country").getAsString());
                    st.setDouble(4, loc.get("lat").getAsDouble());
                    st.setDouble(5, loc.get("lng").getAsDouble());
                    st.executeUpdate();
                    try (ResultSet rs = st.getGeneratedKeys()) { rs.next(); id = rs.getInt(1); }
                }
                inserted++;
            } else {
                try (PreparedStatement st = c.prepareStatement(
                        "UPDATE locations SET name = ?, country = ?, target_lat = ?, target_lng = ?, active = 1 WHERE id = ?")) {
                    st.setString(1, loc.get("name").getAsString());
                    st.setString(2, loc.get("country").getAsString());
                    st.setDouble(3, loc.get("lat").getAsDouble());
                    st.setDouble(4, loc.get("lng").getAsDouble());
                    st.setInt(5, id);
                    st.executeUpdate();
                }
                try (PreparedStatement st = c.prepareStatement("DELETE FROM location_images WHERE location_id = ?")) {
                    st.setInt(1, id);
                    st.executeUpdate();
                }
                updated++;
            }
            try (PreparedStatement st = c.prepareStatement(
                    "INSERT INTO location_images (location_id, file_path, sort_order, is_pano) VALUES (?,?,?,?)")) {
                JsonArray outFiles = loc.getAsJsonArray("outFiles");
                for (int i = 0; i < outFiles.size(); i++) {
                    JsonObject f = outFiles.get(i).getAsJsonObject();
                    st.setInt(1, id);
                    st.setString(2, "locations/" + slug + "/" + f.get("f").getAsString());
                    st.setInt(3, i);
                    st.setBoolean(4, f.get("pano").getAsBoolean());
                    st.addBatch();
                }
                st.executeBatch();
            }
        }
        Log.info("Seeder", "DB: them moi " + inserted + ", cap nhat " + updated + " dia diem");
    }

    private static void seedDemoUsers(Connection c) throws Exception {
        String[][] users = {
                {"demo1", "Minh Anh"}, {"demo2", "Quốc Bảo"}, {"demo3", "Thu Hà"}, {"demo4", "Văn Khoa"}
        };
        for (String[] u : users) {
            try (PreparedStatement check = c.prepareStatement("SELECT id FROM users WHERE username = ?")) {
                check.setString(1, u[0]);
                try (ResultSet rs = check.executeQuery()) {
                    if (rs.next()) continue;
                }
            }
            String salt = PasswordHasher.newSaltHex();
            try (PreparedStatement st = c.prepareStatement(
                    "INSERT INTO users (username, password_hash, salt, display_name) VALUES (?,?,?,?)")) {
                st.setString(1, u[0]);
                st.setString(2, PasswordHasher.hash("123456", salt));
                st.setString(3, salt);
                st.setString(4, u[1]);
                st.executeUpdate();
            }
            Log.info("Seeder", "Tao tai khoan demo: " + u[0] + " / 123456");
        }
    }
}
