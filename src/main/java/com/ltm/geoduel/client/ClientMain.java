package com.ltm.geoduel.client;

import com.formdev.flatlaf.FlatLightLaf;
import com.ltm.geoduel.client.ui.MainFrame;
import com.ltm.geoduel.client.ui.Theme;

import javax.swing.SwingUtilities;
import javax.swing.ToolTipManager;
import javax.swing.UIManager;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Properties;

/** Điểm vào của client desktop. Tham số tuỳ chọn: đường dẫn config (mặc định config.properties). */
public final class ClientMain {
    public static void main(String[] args) {
        Properties props = new Properties();
        String configPath = args.length > 0 ? args[0] : "config.properties";
        try (InputStreamReader r = new InputStreamReader(new FileInputStream(configPath), StandardCharsets.UTF_8)) {
            props.load(r);
        } catch (Exception ignored) {
            // không có config → dùng mặc định localhost:5555
        }
        String host = props.getProperty("server.host", "localhost");
        int port = Integer.parseInt(props.getProperty("server.port", "5555"));
        Path assetsDir = Path.of(props.getProperty("assets.dir", "assets"));

        FlatLightLaf.setup();
        // đồng bộ vài màu nền tảng với Theme để các control mặc định không lạc tông
        UIManager.put("Panel.background", Theme.BG);
        UIManager.put("ToolTip.background", Theme.ELEVATED);
        UIManager.put("ToolTip.foreground", Theme.TEXT);
        UIManager.put("ScrollBar.thumb", Theme.BORDER);
        UIManager.put("ScrollBar.track", Theme.SURFACE);
        ToolTipManager.sharedInstance().setInitialDelay(300);
        com.ltm.geoduel.client.audio.SoundManager.init(assetsDir.resolve("audio"));

        SwingUtilities.invokeLater(() -> new MainFrame(host, port, assetsDir).setVisible(true));
    }
}
