package com.ltm.geoduel.tools;

import com.google.gson.JsonObject;
import com.ltm.geoduel.common.Log;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Ghép bản đồ Việt Nam từ tile raster Carto Voyager (dữ liệu OpenStreetMap,
 * chiếu Web Mercator, zoom 9) thành một ảnh lớn assets/map/vietnam.png
 * + file mô tả vietnam.json để client quy đổi toạ độ chuột ↔ kinh/vĩ độ.
 *
 * Dùng CDN của Carto vì tile.openstreetmap.org bị chặn DNS trên nhiều mạng VN.
 * Vùng phủ: vĩ độ 8.0–23.6, kinh độ 101.5–110.0 (toàn bộ đất liền + đảo gần bờ).
 * Ghi công bắt buộc: © OpenStreetMap contributors © CARTO — client hiển thị.
 */
public final class VietnamMapBuilder {
    private static final String UA = "GeoDuelLTM/1.0 (university course project)";
    private static final int ZOOM = 9;
    private static final int TILE = 256;
    private static final double LAT_MAX = 23.6, LAT_MIN = 8.0;
    private static final double LNG_MIN = 101.5, LNG_MAX = 110.0;

    public static void main(String[] args) throws Exception {
        Path mapDir = Path.of("assets", "map");
        Files.createDirectories(mapDir);

        int n = 1 << ZOOM;
        int x0 = (int) Math.floor((LNG_MIN + 180) / 360 * n);
        int x1 = (int) Math.floor((LNG_MAX + 180) / 360 * n);
        int y0 = latToTileY(LAT_MAX, n); // vĩ độ lớn → hàng tile nhỏ
        int y1 = latToTileY(LAT_MIN, n);
        int tilesX = x1 - x0 + 1, tilesY = y1 - y0 + 1;
        Log.info("MapBuilder", "Tai " + (tilesX * tilesY) + " tile (z" + ZOOM + ", x" + x0 + ".." + x1 +
                ", y" + y0 + ".." + y1 + ") -> anh " + (tilesX * TILE) + "x" + (tilesY * TILE));

        BufferedImage map = new BufferedImage(tilesX * TILE, tilesY * TILE, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = map.createGraphics();
        HttpClient http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .connectTimeout(Duration.ofSeconds(20)).build();

        int done = 0, failed = 0;
        for (int ty = y0; ty <= y1; ty++) {
            for (int tx = x0; tx <= x1; tx++) {
                BufferedImage tile = fetchTile(http, tx, ty);
                if (tile != null) {
                    g.drawImage(tile, (tx - x0) * TILE, (ty - y0) * TILE, null);
                } else {
                    failed++;
                }
                done++;
                if (done % 50 == 0) Log.info("MapBuilder", done + "/" + (tilesX * tilesY) + " tile...");
                Thread.sleep(100); // lịch sự với CDN tile
            }
        }
        g.dispose();
        if (failed > tilesX * tilesY / 20) {
            throw new IllegalStateException("Qua nhieu tile loi (" + failed + "), khong luu ban do");
        }

        Path png = mapDir.resolve("vietnam.png");
        ImageIO.write(map, "png", png.toFile());

        JsonObject meta = new JsonObject();
        meta.addProperty("projection", "webmercator");
        meta.addProperty("zoom", ZOOM);
        meta.addProperty("tileSize", TILE);
        meta.addProperty("x0", x0);
        meta.addProperty("y0", y0);
        meta.addProperty("width", tilesX * TILE);
        meta.addProperty("height", tilesY * TILE);
        meta.addProperty("attribution", "© OpenStreetMap contributors © CARTO");
        Files.writeString(mapDir.resolve("vietnam.json"), meta.toString(), StandardCharsets.UTF_8);

        Log.info("MapBuilder", "Xong: " + png + " (" + Files.size(png) / 1024 / 1024 + " MB, loi " + failed + " tile)");
    }

    private static final String[] SUBDOMAINS = {"a", "b", "c", "d"};

    private static BufferedImage fetchTile(HttpClient http, int x, int y) throws InterruptedException {
        String sub = SUBDOMAINS[(x + y) % SUBDOMAINS.length];
        String url = "https://" + sub + ".basemaps.cartocdn.com/rastertiles/voyager/" +
                ZOOM + "/" + x + "/" + y + ".png";
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                        .header("User-Agent", UA)
                        .timeout(Duration.ofSeconds(30)).GET().build();
                HttpResponse<byte[]> resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
                if (resp.statusCode() == 200) {
                    BufferedImage img = ImageIO.read(new ByteArrayInputStream(resp.body()));
                    if (img != null) return img;
                }
                Log.warn("MapBuilder", "Tile " + x + "," + y + " HTTP " + resp.statusCode());
            } catch (IOException ex) {
                Log.warn("MapBuilder", "Tile " + x + "," + y + " loi: " + ex.getMessage());
            }
            Thread.sleep(1000);
        }
        return null;
    }

    private static int latToTileY(double lat, int n) {
        double rad = Math.toRadians(lat);
        double y = (1 - Math.log(Math.tan(rad) + 1 / Math.cos(rad)) / Math.PI) / 2 * n;
        return (int) Math.floor(y);
    }
}
