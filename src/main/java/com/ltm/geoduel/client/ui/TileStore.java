package com.ltm.geoduel.client.ui;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Kho tile bản đồ Carto Voyager dùng chung cho mọi thành phần vẽ bản đồ
 * (MapPanel, WorldBackdrop): cache RAM + đĩa (assets/tilecache), tải nền bất đồng bộ.
 */
public final class TileStore {
    private static final String[] SUBDOMAINS = {"a", "b", "c", "d"};
    private static final String UA = "GeoDuelLTM/1.0 (university course project)";
    private static final int MEM_CACHE_MAX = 700; // ~46 MB

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .connectTimeout(Duration.ofSeconds(10)).build();
    private static final ExecutorService LOADER = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "tile-loader");
        t.setDaemon(true);
        return t;
    });
    private static final Map<String, BufferedImage> MEM_CACHE = new ConcurrentHashMap<>();
    private static final Set<String> LOADING = ConcurrentHashMap.newKeySet();
    private static final Map<String, Long> FAILED_AT = new ConcurrentHashMap<>();
    private static volatile Path cacheDir = Path.of("assets", "tilecache");

    private TileStore() {}

    public static void setCacheDir(Path dir) {
        cacheDir = dir;
        try {
            Files.createDirectories(dir);
        } catch (IOException ignored) {}
    }

    /**
     * Lấy tile nếu đã có trong RAM; chưa có thì tải nền (đĩa → mạng) rồi gọi
     * {@code onLoaded} (trên thread tải — bên gọi tự invokeLater nếu cần).
     * @return null khi tile chưa sẵn sàng.
     */
    public static BufferedImage get(int z, int x, int y, Runnable onLoaded) {
        String key = z + "/" + x + "/" + y;
        BufferedImage cached = MEM_CACHE.get(key);
        if (cached != null) return cached;

        Long failedAt = FAILED_AT.get(key);
        if (failedAt != null && System.currentTimeMillis() - failedAt < 30_000) return null;

        if (LOADING.add(key)) {
            LOADER.submit(() -> {
                BufferedImage img = load(z, x, y, key);
                LOADING.remove(key);
                if (img != null) {
                    if (MEM_CACHE.size() > MEM_CACHE_MAX) MEM_CACHE.clear(); // đơn giản mà đủ dùng
                    MEM_CACHE.put(key, img);
                    FAILED_AT.remove(key);
                    if (onLoaded != null) onLoaded.run();
                } else {
                    FAILED_AT.put(key, System.currentTimeMillis());
                }
            });
        }
        return null;
    }

    private static BufferedImage load(int z, int x, int y, String key) {
        Path file = cacheDir.resolve(String.valueOf(z)).resolve(x + "_" + y + ".png");
        try {
            if (Files.exists(file)) {
                BufferedImage img = ImageIO.read(file.toFile());
                if (img != null) return img;
            }
        } catch (IOException ignored) {}
        try {
            String sub = SUBDOMAINS[Math.floorMod(x + y, SUBDOMAINS.length)];
            HttpRequest req = HttpRequest.newBuilder(URI.create(
                            "https://" + sub + ".basemaps.cartocdn.com/rastertiles/voyager/" + key + ".png"))
                    .header("User-Agent", UA)
                    .timeout(Duration.ofSeconds(15)).GET().build();
            HttpResponse<byte[]> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() == 200) {
                BufferedImage img = ImageIO.read(new ByteArrayInputStream(resp.body()));
                if (img != null) {
                    try {
                        Files.createDirectories(file.getParent());
                        Files.write(file, resp.body());
                    } catch (IOException ignored) {}
                    return img;
                }
            }
        } catch (IOException | InterruptedException ignored) {
            // mất mạng → thử lại sau 30 s (FAILED_AT)
        }
        return null;
    }
}
