package com.ltm.geoduel.server;

import com.ltm.geoduel.common.Log;

/** Điểm vào của server. Tham số tuỳ chọn: đường dẫn file cấu hình (mặc định config.properties). */
public final class ServerMain {
    public static void main(String[] args) throws Exception {
        String configPath = args.length > 0 ? args[0] : "config.properties";
        Log.info("Server", "Doc cau hinh tu " + configPath);
        ServerConfig config = new ServerConfig(configPath);
        new GameServer(config).serve();
    }
}
