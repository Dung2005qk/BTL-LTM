package com.ltm.geoduel.server;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/** Cấu hình đọc từ file .properties (mặc định config.properties ở thư mục làm việc). */
public final class ServerConfig {
    private final Properties props = new Properties();

    public ServerConfig(String path) throws IOException {
        try (InputStreamReader reader = new InputStreamReader(new FileInputStream(path), StandardCharsets.UTF_8)) {
            props.load(reader);
        }
    }

    public String dbUrl() { return props.getProperty("db.url", "jdbc:mysql://localhost:3306/geoduel"); }
    public String dbUser() { return props.getProperty("db.user", "root"); }
    public String dbPassword() { return props.getProperty("db.password", ""); }
    public String host() { return props.getProperty("server.host", "localhost"); }
    public int port() { return Integer.parseInt(props.getProperty("server.port", "5555")); }
    public String assetsDir() { return props.getProperty("assets.dir", "assets"); }
    public int roundDurationSec() { return Integer.parseInt(props.getProperty("round.duration.sec", "180")); }
    /** Khi một bên đã nộp, bên kia chỉ còn tối đa chừng này giây. */
    public int roundSnipeSec() { return Integer.parseInt(props.getProperty("round.snipe.sec", "15")); }
    public int roundResultSec() { return Integer.parseInt(props.getProperty("round.result.sec", "5")); }
    public int roundsPerMatch() { return Integer.parseInt(props.getProperty("rounds.per.match", "5")); }
    public double scoreDecayKm() { return Double.parseDouble(props.getProperty("score.decay.km", "150")); }
}
