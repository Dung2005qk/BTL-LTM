package com.ltm.geoduel.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ltm.geoduel.common.GeoUtil;
import com.ltm.geoduel.common.Log;

import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Thu thập địa điểm TOÀN CẦU từ Mapillary (graph.mapillary.com, ảnh CC BY-SA):
 * ưu tiên ảnh toàn cảnh 360° (is_pano) để client hiển thị kiểu GeoGuessr,
 * không có thì lấy ảnh phẳng độ phân giải cao (thumb 2048).
 *
 * Cần access token (miễn phí): https://www.mapillary.com/dashboard/developers
 * → Register application → copy "Client Token" → dán vào config.properties (mapillary.token).
 *
 * Ghi tiếp vào cùng assets/raw/manifest.json với KartaViewCollector (chạy lại an toàn);
 * phần tử files là object {"f": tên, "pano": true/false}.
 *
 * Chạy: java -cp target/geoduel.jar com.ltm.geoduel.tools.MapillaryCollector [maxNew] [perSeed]
 */
public final class MapillaryCollector {
    private static final String API = "https://graph.mapillary.com/images";
    private static final String UA = "GeoDuelLTM/1.0 (university course project)";
    private static final long THROTTLE_MS = 300;
    // API hay trả 500 với bbox rộng/đặc → quét nhiều ô nhỏ (~800 m) quanh điểm mồi
    private static final double BBOX_HALF_DEG = 0.004;
    private static final double[][] CELL_OFFSETS = {
            {0, 0}, {0.008, 0}, {-0.008, 0}, {0, 0.008}, {0, -0.008}
    };
    private static final double MIN_TARGET_SEPARATION_M = 500;
    private static final double MAX_CLUE_RADIUS_M = 200;   // đặc tả: manh mối trong 200 m
    private static final double MIN_FRAME_SPACING_M = 30;
    private static final int MIN_IMAGES = 3;
    private static final double MIN_SHARP_PANO = 50;       // pano thu nhỏ vốn mềm hơn ảnh thường

    private record Seed(String slug, String cityName, String country, double lat, double lng) {}
    private record Img(String id, double lat, double lng, boolean pano, String url,
                       String sequence, double compass, long capturedAt) {}

    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .connectTimeout(Duration.ofSeconds(20)).build();
    private final Path rawDir = Path.of("assets", "raw");
    private final String token;
    private final List<JsonObject> manifestLocations = new ArrayList<>();
    private final List<double[]> chosenTargets = new ArrayList<>();
    private final Set<String> usedTargetHashes = new HashSet<>();
    private long lastRequestAt = 0;

    public MapillaryCollector(String token) {
        this.token = token;
    }

    public static void main(String[] args) throws Exception {
        int maxNew = args.length > 0 ? Integer.parseInt(args[0]) : 200;
        int perSeed = args.length > 1 ? Integer.parseInt(args[1]) : 3;

        Properties props = new Properties();
        try (var r = new InputStreamReader(Files.newInputStream(Path.of("config.properties")),
                StandardCharsets.UTF_8)) {
            props.load(r);
        }
        String token = props.getProperty("mapillary.token", "").trim();
        if (token.isEmpty()) {
            System.out.println("CHUA CO MAPILLARY TOKEN.");
            System.out.println("Lay token mien phi (~3 phut):");
            System.out.println("  1. Dang nhap https://www.mapillary.com/dashboard/developers");
            System.out.println("  2. Register application (dien ten bat ky, callback de trong)");
            System.out.println("  3. Copy 'Client Token' (bat dau bang MLY|...)");
            System.out.println("  4. Dan vao config.properties: mapillary.token=MLY|...");
            System.exit(3);
        }
        new MapillaryCollector(token).run(maxNew, perSeed);
    }

    public void run(int maxNew, int perSeed) throws Exception {
        Files.createDirectories(rawDir);
        loadExistingManifest();
        int startCount = manifestLocations.size();
        Log.info("Mapillary", "Bat dau: da co " + startCount + " dia diem, them toi da " + maxNew);

        for (Seed seed : seeds()) {
            if (manifestLocations.size() - startCount >= maxNew) break;
            try {
                collectSeed(seed, perSeed, startCount + maxNew);
            } catch (Exception ex) {
                Log.error("Mapillary", "Loi seed " + seed.slug + ", bo qua", ex);
            }
        }
        writeManifest();
        Log.info("Mapillary", "HOAN TAT: tong " + manifestLocations.size() + " dia diem (" +
                (manifestLocations.size() - startCount) + " moi tu Mapillary)");
    }

    // ================= một điểm mồi =================

    private void collectSeed(Seed seed, int perSeed, int hardCap) throws Exception {
        int already = (int) manifestLocations.stream()
                .filter(o -> o.get("slug").getAsString().startsWith(seed.slug + "-")).count();
        if (already >= perSeed) return;

        // gộp kết quả từ nhiều ô nhỏ quanh trung tâm (khử trùng theo id ảnh)
        Map<String, Img> byId = new HashMap<>();
        for (double[] off : CELL_OFFSETS) {
            for (Img img : queryBbox(seed.lat + off[0], seed.lng + off[1])) {
                byId.putIfAbsent(img.id, img);
            }
        }
        List<Img> all = new ArrayList<>(byId.values());
        if (all.isEmpty()) {
            Log.info("Mapillary", seed.slug + ": khong co du lieu");
            return;
        }
        // nhóm theo sequence; ưu tiên sequence 360°
        Map<String, List<Img>> bySeq = new HashMap<>();
        for (Img img : all) bySeq.computeIfAbsent(img.sequence, k -> new ArrayList<>()).add(img);
        List<List<Img>> sequences = new ArrayList<>(bySeq.values());
        sequences.removeIf(s -> s.size() < MIN_IMAGES);
        sequences.sort(Comparator
                .<List<Img>>comparingInt(s -> s.get(0).pano ? 0 : 1)   // pano trước
                .thenComparingLong(s -> -s.get(0).capturedAt));        // ảnh mới trước

        int made = already;
        for (List<Img> seq : sequences) {
            if (made >= perSeed || manifestLocations.size() >= hardCap) break;
            seq.sort(Comparator.comparingLong(i -> i.capturedAt));
            Img target = seq.get(seq.size() / 2);
            if (!farFromChosenTargets(target.lat, target.lng)) continue;

            List<Img> picked = pickAround(seq, target);
            if (picked == null) continue;

            int index = made + 1;
            String slug = seed.slug + "-" + index;
            if (Files.exists(rawDir.resolve(slug))) continue;

            List<JsonObject> files = downloadImages(slug, picked);
            if (files.size() < MIN_IMAGES) {
                deleteDir(rawDir.resolve(slug));
                continue;
            }
            JsonObject entry = new JsonObject();
            entry.addProperty("slug", slug);
            entry.addProperty("name", seed.cityName + " · điểm " + index);
            entry.addProperty("country", seed.country);
            entry.addProperty("lat", target.lat);
            entry.addProperty("lng", target.lng);
            JsonArray fa = new JsonArray();
            files.forEach(fa::add);
            entry.add("files", fa);
            manifestLocations.add(entry);
            chosenTargets.add(new double[]{target.lat, target.lng});
            made++;
            writeManifest();
            Log.info("Mapillary", slug + ": " + files.size() + " anh" +
                    (picked.get(0).pano ? " (360)" : "") + " (tong " + manifestLocations.size() + ")");
        }
    }

    /** Chọn ảnh đích + các ảnh cùng sequence trong bán kính 200 m, cách nhau ≥ 30 m. */
    private List<Img> pickAround(List<Img> seq, Img target) {
        List<Img> picked = new ArrayList<>();
        picked.add(target);
        int want = target.pano ? 3 : 4; // pano đã bao quát 360° nên 3 ảnh là đủ
        for (Img img : seq) {
            if (picked.size() >= want) break;
            if (img == target) continue;
            double dist = GeoUtil.haversineKm(target.lat, target.lng, img.lat, img.lng) * 1000;
            if (dist > MAX_CLUE_RADIUS_M) continue;
            boolean tooClose = false;
            for (Img p : picked) {
                if (GeoUtil.haversineKm(p.lat, p.lng, img.lat, img.lng) * 1000 < MIN_FRAME_SPACING_M) {
                    tooClose = true;
                    break;
                }
            }
            if (!tooClose) picked.add(img);
        }
        return picked.size() >= MIN_IMAGES ? picked : null;
    }

    private List<JsonObject> downloadImages(String slug, List<Img> picked)
            throws IOException, InterruptedException {
        Path dir = rawDir.resolve(slug);
        Files.createDirectories(dir);
        List<JsonObject> files = new ArrayList<>();
        int n = 1;
        boolean isTarget = true;
        for (Img img : picked) {
            byte[] bytes = httpGetBytes(img.url);
            boolean valid = bytes != null && bytes.length >= 40_000
                    && bytes[0] == (byte) 0xFF && bytes[1] == (byte) 0xD8;
            if (valid) {
                ImageQuality.Score q = ImageQuality.evaluate(bytes);
                double minSharp = img.pano ? MIN_SHARP_PANO : ImageQuality.MIN_SHARPNESS;
                if (q == null || q.sharpness() < minSharp
                        || q.brightness() < ImageQuality.MIN_BRIGHTNESS
                        || q.brightness() > ImageQuality.MAX_BRIGHTNESS) {
                    Log.info("Mapillary", slug + ": loai 1 anh kem");
                    valid = false;
                }
            }
            if (!valid) {
                if (isTarget) return List.of();
                continue;
            }
            if (isTarget && !usedTargetHashes.add(sha1(bytes))) {
                Log.info("Mapillary", slug + ": anh dich trung noi dung, bo qua");
                return List.of();
            }
            String name = "img" + n + ".jpg";
            Files.write(dir.resolve(name), bytes);
            JsonObject f = new JsonObject();
            f.addProperty("f", name);
            f.addProperty("pano", img.pano);
            files.add(f);
            n++;
            isTarget = false;
        }
        return files;
    }

    // ================= API =================

    private List<Img> queryBbox(double lat, double lng) throws IOException, InterruptedException {
        String bbox = (lng - BBOX_HALF_DEG) + "," + (lat - BBOX_HALF_DEG) + ","
                    + (lng + BBOX_HALF_DEG) + "," + (lat + BBOX_HALF_DEG);
        String url = API + "?access_token=" + URLEncoder.encode(token, StandardCharsets.UTF_8)
                + "&bbox=" + bbox
                + "&fields=id,computed_geometry,thumb_2048_url,thumb_original_url,is_pano,sequence,compass_angle,captured_at"
                + "&limit=100";
        byte[] body = httpGetBytes(url);
        if (body == null) return List.of();
        List<Img> out = new ArrayList<>();
        try {
            JsonObject root = JsonParser.parseString(new String(body, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonArray data = root.getAsJsonArray("data");
            if (data == null) return List.of();
            for (JsonElement e : data) {
                JsonObject o = e.getAsJsonObject();
                JsonObject geom = o.has("computed_geometry") && o.get("computed_geometry").isJsonObject()
                        ? o.getAsJsonObject("computed_geometry") : null;
                if (geom == null) continue;
                JsonArray coords = geom.getAsJsonArray("coordinates"); // [lng, lat]
                boolean pano = o.has("is_pano") && o.get("is_pano").getAsBoolean();
                // pano cần độ phân giải gốc để xoay xem; ảnh phẳng dùng thumb 2048
                String imgUrl = null;
                if (pano && o.has("thumb_original_url") && !o.get("thumb_original_url").isJsonNull()) {
                    imgUrl = o.get("thumb_original_url").getAsString();
                } else if (o.has("thumb_2048_url") && !o.get("thumb_2048_url").isJsonNull()) {
                    imgUrl = o.get("thumb_2048_url").getAsString();
                }
                if (imgUrl == null) continue;
                out.add(new Img(
                        o.get("id").getAsString(),
                        coords.get(1).getAsDouble(),
                        coords.get(0).getAsDouble(),
                        pano,
                        imgUrl,
                        o.has("sequence") ? o.get("sequence").getAsString() : "?",
                        o.has("compass_angle") && !o.get("compass_angle").isJsonNull()
                                ? o.get("compass_angle").getAsDouble() : Double.NaN,
                        o.has("captured_at") ? o.get("captured_at").getAsLong() : 0));
            }
        } catch (Exception ex) {
            Log.warn("Mapillary", "Khong doc duoc phan hoi API: " + ex.getMessage());
        }
        return out;
    }

    private boolean farFromChosenTargets(double lat, double lng) {
        for (double[] t : chosenTargets) {
            if (GeoUtil.haversineKm(t[0], t[1], lat, lng) * 1000 < MIN_TARGET_SEPARATION_M) return false;
        }
        return true;
    }

    /** GET có điều tiết + watchdog tổng thời gian; retry-backoff cho 429 và 5xx (API hay chập chờn). */
    private byte[] httpGetBytes(String url) throws InterruptedException {
        for (int attempt = 1; attempt <= 4; attempt++) {
            throttle();
            CompletableFuture<HttpResponse<byte[]>> future = null;
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                        .header("User-Agent", UA)
                        .timeout(Duration.ofSeconds(30)).GET().build();
                future = http.sendAsync(req, HttpResponse.BodyHandlers.ofByteArray());
                HttpResponse<byte[]> resp = future.get(60, TimeUnit.SECONDS);
                if (resp.statusCode() == 200) return resp.body();
                if (resp.statusCode() == 401 || resp.statusCode() == 403) {
                    Log.warn("Mapillary", "Token khong hop le? Kiem tra mapillary.token trong config.properties");
                    return null;
                }
                if (resp.statusCode() == 429 || resp.statusCode() >= 500) {
                    long backoff = 2000L * attempt;
                    Log.warn("Mapillary", "HTTP " + resp.statusCode() + ", cho " + backoff + " ms (" + attempt + "/4)");
                    Thread.sleep(backoff);
                    continue;
                }
                Log.warn("Mapillary", "HTTP " + resp.statusCode() + " (" + attempt + "/4)");
            } catch (TimeoutException ex) {
                future.cancel(true);
                Log.warn("Mapillary", "Qua 60 s khong xong, huy (" + attempt + "/4)");
            } catch (ExecutionException ex) {
                Log.warn("Mapillary", "Loi mang (" + attempt + "/4): " + ex.getCause());
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

    // ================= manifest (chung với KartaViewCollector) =================

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
            Path img1 = rawDir.resolve(o.get("slug").getAsString()).resolve("img1.jpg");
            if (Files.exists(img1)) usedTargetHashes.add(sha1(Files.readAllBytes(img1)));
        }
    }

    private void writeManifest() throws IOException {
        JsonObject root = new JsonObject();
        root.addProperty("source",
                "KartaView (kartaview.org) + Mapillary (mapillary.com), giay phep CC BY-SA 4.0");
        JsonArray arr = new JsonArray();
        manifestLocations.forEach(arr::add);
        root.add("locations", arr);
        Files.writeString(rawDir.resolve("manifest.json"), root.toString(), StandardCharsets.UTF_8);
    }

    private static String sha1(byte[] data) {
        try {
            return java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-1").digest(data));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void deleteDir(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (var stream = Files.walk(dir)) {
            stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.delete(p); } catch (IOException ignored) {}
            });
        }
    }

    // ================= điểm mồi toàn cầu =================

    private static List<Seed> seeds() {
        Map<String, Object[]> m = new LinkedHashMap<>();
        // Châu Âu
        m.put("paris", new Object[]{"Paris", "Pháp", 48.8566, 2.3522});
        m.put("london", new Object[]{"London", "Anh", 51.5074, -0.1278});
        m.put("berlin", new Object[]{"Berlin", "Đức", 52.5200, 13.4050});
        m.put("madrid", new Object[]{"Madrid", "Tây Ban Nha", 40.4168, -3.7038});
        m.put("barcelona", new Object[]{"Barcelona", "Tây Ban Nha", 41.3874, 2.1686});
        m.put("rome", new Object[]{"Roma", "Ý", 41.9028, 12.4964});
        m.put("amsterdam", new Object[]{"Amsterdam", "Hà Lan", 52.3676, 4.9041});
        m.put("prague", new Object[]{"Praha", "Séc", 50.0755, 14.4378});
        m.put("vienna", new Object[]{"Wien", "Áo", 48.2082, 16.3738});
        m.put("warsaw", new Object[]{"Warszawa", "Ba Lan", 52.2297, 21.0122});
        m.put("budapest", new Object[]{"Budapest", "Hungary", 47.4979, 19.0402});
        m.put("lisbon", new Object[]{"Lisboa", "Bồ Đào Nha", 38.7223, -9.1393});
        m.put("stockholm", new Object[]{"Stockholm", "Thụy Điển", 59.3293, 18.0686});
        m.put("helsinki", new Object[]{"Helsinki", "Phần Lan", 60.1699, 24.9384});
        m.put("copenhagen", new Object[]{"København", "Đan Mạch", 55.6761, 12.5683});
        m.put("dublin", new Object[]{"Dublin", "Ireland", 53.3498, -6.2603});
        m.put("brussels", new Object[]{"Bruxelles", "Bỉ", 50.8503, 4.3517});
        m.put("zurich", new Object[]{"Zürich", "Thụy Sĩ", 47.3769, 8.5417});
        m.put("athens", new Object[]{"Athens", "Hy Lạp", 37.9838, 23.7275});
        m.put("istanbul", new Object[]{"Istanbul", "Thổ Nhĩ Kỳ", 41.0082, 28.9784});
        m.put("kyiv", new Object[]{"Kyiv", "Ukraine", 50.4501, 30.5234});
        m.put("bucharest", new Object[]{"București", "Romania", 44.4268, 26.1025});
        m.put("belgrade", new Object[]{"Beograd", "Serbia", 44.7866, 20.4489});
        m.put("oslo", new Object[]{"Oslo", "Na Uy", 59.9139, 10.7522});
        // Bắc Mỹ
        m.put("newyork", new Object[]{"New York", "Mỹ", 40.7580, -73.9855});
        m.put("sanfrancisco", new Object[]{"San Francisco", "Mỹ", 37.7749, -122.4194});
        m.put("losangeles", new Object[]{"Los Angeles", "Mỹ", 34.0522, -118.2437});
        m.put("chicago", new Object[]{"Chicago", "Mỹ", 41.8781, -87.6298});
        m.put("seattle", new Object[]{"Seattle", "Mỹ", 47.6062, -122.3321});
        m.put("boston", new Object[]{"Boston", "Mỹ", 42.3601, -71.0589});
        m.put("washington", new Object[]{"Washington D.C.", "Mỹ", 38.9072, -77.0369});
        m.put("miami", new Object[]{"Miami", "Mỹ", 25.7617, -80.1918});
        m.put("toronto", new Object[]{"Toronto", "Canada", 43.6532, -79.3832});
        m.put("vancouver", new Object[]{"Vancouver", "Canada", 49.2827, -123.1207});
        m.put("montreal", new Object[]{"Montréal", "Canada", 45.5017, -73.5673});
        m.put("mexicocity", new Object[]{"Ciudad de México", "Mexico", 19.4326, -99.1332});
        // Nam Mỹ
        m.put("bogota", new Object[]{"Bogotá", "Colombia", 4.7110, -74.0721});
        m.put("lima", new Object[]{"Lima", "Peru", -12.0464, -77.0428});
        m.put("santiago", new Object[]{"Santiago", "Chile", -33.4489, -70.6693});
        m.put("buenosaires", new Object[]{"Buenos Aires", "Argentina", -34.6037, -58.3816});
        m.put("saopaulo", new Object[]{"São Paulo", "Brazil", -23.5505, -46.6333});
        m.put("rio", new Object[]{"Rio de Janeiro", "Brazil", -22.9068, -43.1729});
        // Châu Á + Trung Đông
        m.put("tokyo", new Object[]{"Tokyo", "Nhật Bản", 35.6762, 139.6503});
        m.put("osaka", new Object[]{"Osaka", "Nhật Bản", 34.6937, 135.5023});
        m.put("seoul", new Object[]{"Seoul", "Hàn Quốc", 37.5665, 126.9780});
        m.put("taipei", new Object[]{"Đài Bắc", "Đài Loan", 25.0330, 121.5654});
        m.put("bangkok", new Object[]{"Bangkok", "Thái Lan", 13.7563, 100.5018});
        m.put("singapore-ml", new Object[]{"Singapore", "Singapore", 1.3521, 103.8198});
        m.put("kualalumpur", new Object[]{"Kuala Lumpur", "Malaysia", 3.1390, 101.6869});
        m.put("jakarta", new Object[]{"Jakarta", "Indonesia", -6.2088, 106.8456});
        m.put("manila", new Object[]{"Manila", "Philippines", 14.5995, 120.9842});
        m.put("hongkong", new Object[]{"Hong Kong", "Trung Quốc", 22.3193, 114.1694});
        m.put("mumbai", new Object[]{"Mumbai", "Ấn Độ", 19.0760, 72.8777});
        m.put("delhi", new Object[]{"New Delhi", "Ấn Độ", 28.6139, 77.2090});
        m.put("dubai", new Object[]{"Dubai", "UAE", 25.2048, 55.2708});
        m.put("telaviv", new Object[]{"Tel Aviv", "Israel", 32.0853, 34.7818});
        // Việt Nam (Mapillary cũng có dữ liệu, bổ sung cho KartaView)
        m.put("hanoi-ml", new Object[]{"Hà Nội", "Việt Nam", 21.0285, 105.8542});
        m.put("hcm-ml", new Object[]{"TP. Hồ Chí Minh", "Việt Nam", 10.7769, 106.7009});
        m.put("danang-ml", new Object[]{"Đà Nẵng", "Việt Nam", 16.0678, 108.2208});
        // Châu Phi + Châu Đại Dương
        m.put("capetown", new Object[]{"Cape Town", "Nam Phi", -33.9249, 18.4241});
        m.put("nairobi", new Object[]{"Nairobi", "Kenya", -1.2921, 36.8219});
        m.put("cairo", new Object[]{"Cairo", "Ai Cập", 30.0444, 31.2357});
        m.put("sydney", new Object[]{"Sydney", "Úc", -33.8688, 151.2093});
        m.put("melbourne", new Object[]{"Melbourne", "Úc", -37.8136, 144.9631});
        m.put("auckland", new Object[]{"Auckland", "New Zealand", -36.8509, 174.7645});

        List<Seed> seeds = new ArrayList<>();
        m.forEach((slug, v) -> seeds.add(new Seed(slug, (String) v[0], (String) v[1],
                (Double) v[2], (Double) v[3])));
        return seeds;
    }
}
