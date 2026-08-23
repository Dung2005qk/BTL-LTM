package com.ltm.geoduel.server;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * Nguồn kết nối MySQL đơn giản: mỗi thao tác DAO mở một kết nối mới rồi đóng
 * (try-with-resources ở phía gọi). Đủ cho quy mô đồ án; tránh chia sẻ Connection giữa các thread.
 */
public final class Db {
    private final String url;
    private final String user;
    private final String password;

    public Db(String url, String user, String password) {
        this.url = url;
        this.user = user;
        this.password = password;
    }

    public Connection open() throws SQLException {
        return DriverManager.getConnection(url, user, password);
    }
}
