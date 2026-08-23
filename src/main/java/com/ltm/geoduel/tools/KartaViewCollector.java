package com.ltm.geoduel.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ltm.geoduel.common.GeoUtil;
import com.ltm.geoduel.common.Log;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Thu thập dữ liệu địa điểm cho game từ KartaView (kartaview.org, ảnh giấy phép CC BY-SA 4.0).
 *
 * Cách làm: với mỗi điểm mồi (thành phố), hỏi API ảnh trong bán kính 2 km (API trả về
 * một ảnh đại diện cho mỗi sequence — một chuyến ghi hình). Chọn vài sequence cách nhau
 * đủ xa, rồi tải 4 khung hình liên tiếp gần nhau của sequence đó: khung ĐẦU TIÊN là toạ
 * độ đích, các khung còn lại nằm trong bán kính 200 m quanh đích (đúng đặc tả đề bài).
 *
 * Chạy: java -cp target/geoduel.jar com.ltm.geoduel.tools.KartaViewCollector [maxLocations] [perSeed]
 * Kết quả: assets/raw/<slug>/img1..4.jpg + assets/raw/manifest.json (chạy lại sẽ bỏ qua slug đã có).
 */
public final class KartaViewCollector {
    private static final String API = "https://api.openstreetcam.org/2.0/photo/";
    private static final String UA = "GeoDuelLTM/1.0 (university course project)";
    private static final long THROTTLE_MS = 600;
    private static final double MIN_TARGET_SEPARATION_M = 500;
    private static final double MAX_CLUE_RADIUS_M = 200;   // đặc tả: manh mối trong bán kính 200 m
    private static final double MIN_FRAME_SPACING_M = 30;  // giãn xa để các góc nhìn đa dạng hơn
    private static final int IMAGES_PER_LOCATION = 4;      // đặc tả cho phép 3-5
    private static final int MIN_IMAGES_PER_LOCATION = 3;
    private static final int PREFERRED_SOURCE_WIDTH = 2000; // ưu tiên camera phân giải cao

    private record Seed(String slug, String cityName, double lat, double lng) {}
    private record Frame(int index, double lat, double lng, String urlLth) {}

    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .connectTimeout(Duration.ofSeconds(20))
            .build();
    private final Path rawDir;
    private final List<JsonObject> manifestLocations = new ArrayList<>();
    private final List<double[]> chosenTargets = new ArrayList<>();
    /** SHA-1 của ảnh đích đã dùng — chặn sequence bị upload trùng trên KartaView. */
    private final java.util.Set<String> usedTargetHashes = new java.util.HashSet<>();
    private long lastRequestAt = 0;

    public KartaViewCollector(Path rawDir) {
        this.rawDir = rawDir;
    }

    public static void main(String[] args) throws Exception {
        int maxLocations = args.length > 0 ? Integer.parseInt(args[0]) : 250;
        int perSeed = args.length > 1 ? Integer.parseInt(args[1]) : 5;
        KartaViewCollector collector = new KartaViewCollector(Path.of("assets", "raw"));
        collector.run(maxLocations, perSeed);
    }

    public void run(int maxLocations, int perSeed) throws Exception {
        Files.createDirectories(rawDir);
        loadExistingManifest();
        int startCount = manifestLocations.size();
        Log.info("Collector", "Bat dau: da co " + startCount + " dia diem, muc tieu " + maxLocations);

        for (Seed seed : seeds()) {
            if (manifestLocations.size() >= maxLocations) break;
            try {
                collectSeed(seed, perSeed, maxLocations);
            } catch (Exception ex) {
                Log.error("Collector", "Loi tai seed " + seed.slug + ", bo qua", ex);
            }
        }
        writeManifest();
        Log.info("Collector", "HOAN TAT: " + manifestLocations.size() + " dia diem (" +
                (manifestLocations.size() - startCount) + " moi)");
    }

    // ================= một điểm mồi =================

    private void collectSeed(Seed seed, int perSeed, int maxLocations) throws Exception {
        int already = (int) manifestLocations.stream()
                .filter(o -> o.get("slug").getAsString().startsWith(seed.slug + "-")).count();
        if (already >= perSeed) return;

        JsonArray reps = apiGetData(API + "?lat=" + seed.lat + "&lng=" + seed.lng +
                "&radius=2000&itemsPerPage=100");
        if (reps == null || reps.size() == 0) {
            Log.info("Collector", seed.slug + ": khong co du lieu");
            return;
        }
        // Ưu tiên camera phân giải cao (ảnh rõ, đọc được biển hiệu) rồi mới đến gần trung tâm.
        List<JsonObject> candidates = new ArrayList<>();
        for (JsonElement e : reps) candidates.add(e.getAsJsonObject());
        candidates.sort(Comparator
                .<JsonObject>comparingInt(o -> asDouble(o, "width", 0) >= PREFERRED_SOURCE_WIDTH ? 0 : 1)
                .thenComparingDouble(o -> asDouble(o, "distance", 99999)));

        int made = already;
        for (JsonObject cand : candidates) {
            if (made >= perSeed || manifestLocations.size() >= maxLocations) break;
            double lat = asDouble(cand, "lat", Double.NaN);
            double lng = asDouble(cand, "lng", Double.NaN);
            String seqId = asString(cand, "sequenceId");
            if (Double.isNaN(lat) || seqId == null) continue;
            if (!farFromChosenTargets(lat, lng)) continue;

            int index = made + 1;
            String slug = seed.slug + "-" + index;
            if (Files.exists(rawDir.resolve(slug))) continue; // đã tải ở lần chạy trước

            List<Frame> frames = pickFrames(seqId, (int) asDouble(cand, "sequenceIndex", 0));
            if (frames == null || frames.size() < MIN_IMAGES_PER_LOCATION) continue;

            List<String> files = downloadFrames(slug, frames);
            if (files.size() < MIN_IMAGES_PER_LOCATION) {
                deleteDir(rawDir.resolve(slug));
                continue;
            }
            Frame target = frames.get(0);
            JsonObject entry = new JsonObject();
            entry.addProperty("slug", slug);
            entry.addProperty("name", seed.cityName + " · điểm " + index);
            entry.addProperty("country", "Việt Nam");
            entry.addProperty("lat", target.lat);
            entry.addProperty("lng", target.lng);
            JsonArray fileArr = new JsonArray();
            files.forEach(fileArr::add);
            entry.add("files", fileArr);
            manifestLocations.add(entry);
            chosenTargets.add(new double[]{target.lat, target.lng});
            made++;
            writeManifest(); // ghi tăng dần để có thể dừng/chạy lại an toàn
            Log.info("Collector", slug + ": " + files.size() + " anh (tong " + manifestLocations.size() + ")");
        }
    }

    /**
     * Chọn khung hình của sequence: khung đích + các khung lân cận, cách nhau ≥15 m
     * và tất cả trong bán kính 200 m quanh khung đích.
     */
    private List<Frame> pickFrames(String sequenceId, int aroundIndex) throws Exception {
        List<Frame> all = new ArrayList<>();
        for (int page = 1; page <= 6; page++) {
            JsonArray data = apiGetData(API + "?sequenceId=" + sequenceId +
                    "&itemsPerPage=100&page=" + page);
            if (data == null || data.size() == 0) break;
            for (JsonElement e : data) {
                JsonObject o = e.getAsJsonObject();
                String url = asString(o, "fileurlLTh");
                if (url == null) url = asString(o, "fileurlProc");
                if (url == null) continue;
                all.add(new Frame((int) asDouble(o, "sequenceIndex", -1),
                        asDouble(o, "lat", Double.NaN), asDouble(o, "lng", Double.NaN), url));
            }
            if (data.size() < 100) break;
        }
        if (all.size() < MIN_IMAGES_PER_LOCATION) return null;
        all.sort(Comparator.comparingInt(Frame::index));

        // Khung đích: khung có index gần aroundIndex nhất.
        Frame target = all.get(0);
        for (Frame f : all) {
            if (Math.abs(f.index - aroundIndex) < Math.abs(target.index - aroundIndex)) target = f;
        }

        List<Frame> picked = new ArrayList<>();
        picked.add(target);
        // Lan ra hai phía từ khung đích, giữ khoảng cách giữa các ảnh và bán kính ≤ 200 m.
        int pos = all.indexOf(target);
        int lo = pos - 1, hi = pos + 1;
        boolean forward = true;
        while (picked.size() < IMAGES_PER_LOCATION && (lo >= 0 || hi < all.size())) {
            Frame f = null;
            if (forward && hi < all.size()) f = all.get(hi++);
            else if (!forward && lo >= 0) f = all.get(lo--);
            else if (hi < all.size()) f = all.get(hi++);
            else if (lo >= 0) f = all.get(lo--);
            forward = !forward;
            if (f == null || Double.isNaN(f.lat)) continue;
            double distTarget = GeoUtil.haversineKm(target.lat, target.lng, f.lat, f.lng) * 1000;
            if (distTarget > MAX_CLUE_RADIUS_M) continue;
            boolean tooClose = false;
            for (Frame p : picked) {
                if (GeoUtil.haversineKm(p.lat, p.lng, f.lat, f.lng) * 1000 < MIN_FRAME_SPACING_M) {
                    tooClose = true;
                    break;
                }
            }
            if (!tooClose) picked.add(f);
        }
        return picked.size() >= MIN_IMAGES_PER_LOCATION ? picked : null;
    }

    /**
     * Tải các khung hình đã chọn. Khung ĐẦU TIÊN là ảnh đích: nếu nó hỏng, mờ
     * hoặc trùng nội dung với địa điểm đã có thì bỏ cả địa điểm (toạ độ đích
     * phải luôn đúng là toạ độ của img1 theo đặc tả).
     */
    private List<String> downloadFrames(String slug, List<Frame> frames) throws IOException, InterruptedException {
        Path dir = rawDir.resolve(slug);
        Files.createDirectories(dir);
        List<String> files = new ArrayList<>();
        int n = 1;
        boolean isTarget = true;
        for (Frame f : frames) {
            byte[] bytes = httpGetBytes(f.urlLth);
            boolean valid = bytes != null && bytes.length >= 25_000
                    && bytes[0] == (byte) 0xFF && bytes[1] == (byte) 0xD8;
            if (valid) {
                ImageQuality.Score q = ImageQuality.evaluate(bytes);
                if (q == null || !q.acceptable()) {
                    Log.info("Collector", slug + ": loai 1 anh kem (net=" +
                            (q == null ? "?" : String.format("%.0f", q.sharpness())) + ")");
                    valid = false;
                }
            }
            if (!valid) {
                if (isTarget) return List.of(); // ảnh đích hỏng → bỏ địa điểm
                continue;
            }
            if (isTarget && !usedTargetHashes.add(sha1(bytes))) {
                Log.info("Collector", slug + ": anh dich trung noi dung voi dia diem khac, bo qua");
                return List.of(); // sequence bị upload trùng trên KartaView
            }
            Files.write(dir.resolve("img" + n + ".jpg"), bytes);
            files.add("img" + n + ".jpg");
            n++;
            isTarget = false;
        }
        return files;
    }

    private static String sha1(byte[] data) {
        try {
            return java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-1").digest(data));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private boolean farFromChosenTargets(double lat, double lng) {
        for (double[] t : chosenTargets) {
            if (GeoUtil.haversineKm(t[0], t[1], lat, lng) * 1000 < MIN_TARGET_SEPARATION_M) return false;
        }
        return true;
    }

    // ================= HTTP + manifest =================

    private JsonArray apiGetData(String url) throws IOException, InterruptedException {
        byte[] body = httpGetBytes(url);
        if (body == null) return null;
        try {
            JsonObject root = JsonParser.parseString(new String(body, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonObject result = root.getAsJsonObject("result");
            return result == null ? null : result.getAsJsonArray("data");
        } catch (Exception ex) {
            return null;
        }
    }

    /**
     * GET có điều tiết tốc độ; gặp 429 (rate limit) thì lùi lại chờ lâu dần rồi thử tiếp.
     * Dùng sendAsync + get(45s) làm watchdog vì HttpRequest.timeout() chỉ áp dụng
     * tới khi nhận header — body có thể treo vô hạn khi máy chủ tarpit.
     * @return null nếu thất bại sau mọi lần thử.
     */
    private byte[] httpGetBytes(String url) throws InterruptedException {
        for (int attempt = 1; attempt <= 4; attempt++) {
            throttle();
            java.util.concurrent.CompletableFuture<HttpResponse<byte[]>> future = null;
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                        .header("User-Agent", UA)
                        .timeout(Duration.ofSeconds(30))
                        .GET().build();
                future = http.sendAsync(req, HttpResponse.BodyHandlers.ofByteArray());
                HttpResponse<byte[]> resp = future.get(45, java.util.concurrent.TimeUnit.SECONDS);
                if (resp.statusCode() == 200) return resp.body();
                if (resp.statusCode() == 429) {
                    long backoff = 3000L * attempt;
                    Log.warn("Collector", "HTTP 429, cho " + backoff + " ms roi thu lai (" + attempt + "/4)");
                    Thread.sleep(backoff);
                    continue;
                }
                Log.warn("Collector", "HTTP " + resp.statusCode() + " (" + attempt + "/4): " + url);
            } catch (java.util.concurrent.TimeoutException ex) {
                future.cancel(true);
                Log.warn("Collector", "Qua 45 s khong xong, huy (" + attempt + "/4): " + url);
            } catch (java.util.concurrent.ExecutionException ex) {
                Log.warn("Collector", "Loi mang (" + attempt + "/4): " + ex.getCause());
            }
            Thread.sleep(1000);
        }
        return null;
    }

    private void throttle() throws InterruptedException {
        long wait = lastRequestAt + THROTTLE_MS - System.currentTimeMillis();
        if (wait > 0) Thread.sleep(wait);
        lastRequestAt = System.currentTimeMillis();
    }

    private void loadExistingManifest() throws IOException {
        Path f = rawDir.resolve("manifest.json");
        if (!Files.exists(f)) return;
        JsonObject root = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
        JsonArray locs = root.getAsJsonArray("locations");
        if (locs == null) return;
        for (JsonElement e : locs) {
            JsonObject o = e.getAsJsonObject();
            manifestLocations.add(o);
            chosenTargets.add(new double[]{o.get("lat").getAsDouble(), o.get("lng").getAsDouble()});
            // dựng lại tập hash ảnh đích để chống trùng khi chạy nối tiếp
            Path img1 = rawDir.resolve(o.get("slug").getAsString()).resolve("img1.jpg");
            if (Files.exists(img1)) usedTargetHashes.add(sha1(Files.readAllBytes(img1)));
        }
    }

    private void writeManifest() throws IOException {
        JsonObject root = new JsonObject();
        root.addProperty("source", "KartaView (kartaview.org), giay phep CC BY-SA 4.0");
        JsonArray arr = new JsonArray();
        manifestLocations.forEach(arr::add);
        root.add("locations", arr);
        Files.writeString(rawDir.resolve("manifest.json"), root.toString(), StandardCharsets.UTF_8);
    }

    private static void deleteDir(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (var stream = Files.walk(dir)) {
            stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.delete(p); } catch (IOException ignored) {}
            });
        }
    }

    private static double asDouble(JsonObject o, String key, double def) {
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull()) return def;
        try { return e.getAsDouble(); } catch (Exception ex) { return def; }
    }

    private static String asString(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? null : e.getAsString();
    }

    // ================= danh sách điểm mồi =================

    private static List<Seed> seeds() {
        Map<String, Object[]> m = new LinkedHashMap<>();
        // Miền Bắc
        m.put("hanoi-hoankiem", new Object[]{"Hà Nội", 21.0288, 105.8522});
        m.put("hanoi-caugiay", new Object[]{"Hà Nội", 21.0333, 105.7942});
        m.put("hanoi-hadong", new Object[]{"Hà Nội", 20.9709, 105.7791});
        m.put("hanoi-longbien", new Object[]{"Hà Nội", 21.0450, 105.8850});
        m.put("hanoi-dongda", new Object[]{"Hà Nội", 21.0180, 105.8290});
        m.put("hanoi-tayho", new Object[]{"Hà Nội", 21.0680, 105.8180});
        m.put("hanoi-thanhxuan", new Object[]{"Hà Nội", 20.9930, 105.8060});
        m.put("haiphong", new Object[]{"Hải Phòng", 20.8449, 106.6881});
        m.put("halong", new Object[]{"Hạ Long", 20.9517, 107.0800});
        m.put("namdinh", new Object[]{"Nam Định", 20.4200, 106.1683});
        m.put("thainguyen", new Object[]{"Thái Nguyên", 21.5928, 105.8442});
        m.put("viettri", new Object[]{"Việt Trì", 21.3100, 105.4300});
        m.put("ninhbinh", new Object[]{"Ninh Bình", 20.2506, 105.9745});
        m.put("thanhhoa", new Object[]{"Thanh Hóa", 19.8067, 105.7772});
        m.put("langson", new Object[]{"Lạng Sơn", 21.8478, 106.7578});
        m.put("laocai", new Object[]{"Lào Cai", 22.4856, 103.9707});
        m.put("sonla", new Object[]{"Sơn La", 21.3272, 103.9160});
        m.put("tuyenquang", new Object[]{"Tuyên Quang", 21.8233, 105.2181});
        m.put("bacninh", new Object[]{"Bắc Ninh", 21.1861, 106.0763});
        m.put("haiduong", new Object[]{"Hải Dương", 20.9373, 106.3146});
        m.put("hungyen", new Object[]{"Hưng Yên", 20.6464, 106.0511});
        m.put("phuly", new Object[]{"Phủ Lý", 20.5411, 105.9139});
        m.put("thaibinh", new Object[]{"Thái Bình", 20.4461, 106.3422});
        // Miền Trung
        m.put("vinh", new Object[]{"Vinh", 18.6796, 105.6813});
        m.put("hatinh", new Object[]{"Hà Tĩnh", 18.3428, 105.9058});
        m.put("donghoi", new Object[]{"Đồng Hới", 17.4689, 106.6223});
        m.put("dongha", new Object[]{"Đông Hà", 16.8163, 107.1003});
        m.put("hue", new Object[]{"Huế", 16.4637, 107.5909});
        m.put("danang", new Object[]{"Đà Nẵng", 16.0678, 108.2208});
        m.put("hoian", new Object[]{"Hội An", 15.8801, 108.3380});
        m.put("tamky", new Object[]{"Tam Kỳ", 15.5736, 108.4740});
        m.put("quangngai", new Object[]{"Quảng Ngãi", 15.1214, 108.8044});
        m.put("quynhon", new Object[]{"Quy Nhơn", 13.7830, 109.2196});
        m.put("tuyhoa", new Object[]{"Tuy Hòa", 13.0955, 109.3209});
        m.put("nhatrang", new Object[]{"Nha Trang", 12.2388, 109.1967});
        m.put("phanrang", new Object[]{"Phan Rang", 11.5643, 108.9886});
        m.put("phanthiet", new Object[]{"Phan Thiết", 10.9289, 108.1021});
        // Tây Nguyên
        m.put("kontum", new Object[]{"Kon Tum", 14.3545, 108.0076});
        m.put("pleiku", new Object[]{"Pleiku", 13.9833, 108.0000});
        m.put("buonmathuot", new Object[]{"Buôn Ma Thuột", 12.6667, 108.0500});
        m.put("dalat", new Object[]{"Đà Lạt", 11.9404, 108.4583});
        m.put("baoloc", new Object[]{"Bảo Lộc", 11.5481, 107.8075});
        // Miền Nam
        m.put("hcm-quan1", new Object[]{"TP. Hồ Chí Minh", 10.7769, 106.7009});
        m.put("hcm-tanbinh", new Object[]{"TP. Hồ Chí Minh", 10.8010, 106.6527});
        m.put("hcm-thuduc", new Object[]{"TP. Hồ Chí Minh", 10.8494, 106.7537});
        m.put("hcm-quan7", new Object[]{"TP. Hồ Chí Minh", 10.7340, 106.7220});
        m.put("hcm-govap", new Object[]{"TP. Hồ Chí Minh", 10.8380, 106.6650});
        m.put("hcm-binhthanh", new Object[]{"TP. Hồ Chí Minh", 10.8106, 106.7091});
        m.put("hcm-quan5", new Object[]{"TP. Hồ Chí Minh", 10.7540, 106.6630});
        m.put("danang-lienchieu", new Object[]{"Đà Nẵng", 16.0755, 108.1533});
        m.put("danang-sontra", new Object[]{"Đà Nẵng", 16.0830, 108.2380});
        m.put("uongbi", new Object[]{"Uông Bí", 21.0359, 106.7733});
        m.put("campha", new Object[]{"Cẩm Phả", 21.0160, 107.2990});
        m.put("hagiang", new Object[]{"Hà Giang", 22.8233, 104.9836});
        m.put("caobang", new Object[]{"Cao Bằng", 22.6657, 106.2570});
        m.put("dienbien", new Object[]{"Điện Biên Phủ", 21.3860, 103.0170});
        m.put("camranh", new Object[]{"Cam Ranh", 11.9214, 109.1591});
        m.put("longkhanh", new Object[]{"Long Khánh", 10.9430, 107.2400});
        m.put("bienhoa", new Object[]{"Biên Hòa", 10.9508, 106.8221});
        m.put("thudaumot", new Object[]{"Thủ Dầu Một", 10.9800, 106.6519});
        m.put("tayninh", new Object[]{"Tây Ninh", 11.3100, 106.0983});
        m.put("vungtau", new Object[]{"Vũng Tàu", 10.3460, 107.0843});
        m.put("mytho", new Object[]{"Mỹ Tho", 10.3600, 106.3600});
        m.put("bentre", new Object[]{"Bến Tre", 10.2415, 106.3759});
        m.put("travinh", new Object[]{"Trà Vinh", 9.9347, 106.3453});
        m.put("vinhlong", new Object[]{"Vĩnh Long", 10.2537, 105.9722});
        m.put("caolanh", new Object[]{"Cao Lãnh", 10.4602, 105.6420});
        m.put("longxuyen", new Object[]{"Long Xuyên", 10.3864, 105.4351});
        m.put("chaudoc", new Object[]{"Châu Đốc", 10.7011, 105.1119});
        m.put("rachgia", new Object[]{"Rạch Giá", 10.0125, 105.0809});
        m.put("cantho", new Object[]{"Cần Thơ", 10.0452, 105.7469});
        m.put("soctrang", new Object[]{"Sóc Trăng", 9.6025, 105.9739});
        m.put("baclieu", new Object[]{"Bạc Liêu", 9.2853, 105.7243});
        m.put("camau", new Object[]{"Cà Mau", 9.1769, 105.1524});
        m.put("tanan", new Object[]{"Tân An", 10.5347, 106.4042});
        m.put("hatien", new Object[]{"Hà Tiên", 10.3831, 104.4880});
        m.put("phuquoc", new Object[]{"Phú Quốc", 10.2270, 103.9637});

        List<Seed> seeds = new ArrayList<>();
        m.forEach((slug, v) -> seeds.add(new Seed(slug, (String) v[0], (Double) v[1], (Double) v[2])));
        return seeds;
    }
}
