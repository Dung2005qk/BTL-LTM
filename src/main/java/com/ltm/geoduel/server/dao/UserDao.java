package com.ltm.geoduel.server.dao;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.ltm.geoduel.server.Db;
import com.ltm.geoduel.server.PasswordHasher;
import com.ltm.geoduel.server.model.UserProfile;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public class UserDao {
    private final Db db;

    public UserDao(Db db) { this.db = db; }

    /** @return null nếu tạo thành công, ngược lại là thông báo lỗi cho người dùng. */
    public String register(String username, String password, String displayName) throws SQLException {
        try (Connection c = db.open()) {
            try (PreparedStatement check = c.prepareStatement("SELECT id FROM users WHERE username = ?")) {
                check.setString(1, username);
                try (ResultSet rs = check.executeQuery()) {
                    if (rs.next()) return "Tên đăng nhập đã tồn tại";
                }
            }
            String salt = PasswordHasher.newSaltHex();
            String hash = PasswordHasher.hash(password, salt);
            try (PreparedStatement ins = c.prepareStatement(
                    "INSERT INTO users (username, password_hash, salt, display_name) VALUES (?,?,?,?)")) {
                ins.setString(1, username);
                ins.setString(2, hash);
                ins.setString(3, salt);
                ins.setString(4, displayName);
                ins.executeUpdate();
            }
            return null;
        }
    }

    /** @return hồ sơ nếu đúng mật khẩu, null nếu sai. */
    public UserProfile authenticate(String username, String password) throws SQLException {
        try (Connection c = db.open();
             PreparedStatement st = c.prepareStatement(
                     "SELECT id, username, password_hash, salt, display_name, elo, wins, losses, draws, total_score" +
                     " FROM users WHERE username = ?")) {
            st.setString(1, username);
            try (ResultSet rs = st.executeQuery()) {
                if (!rs.next()) return null;
                String hash = rs.getString("password_hash");
                String salt = rs.getString("salt");
                if (!PasswordHasher.verify(password, salt, hash)) return null;
                return readProfile(rs);
            }
        }
    }

    public UserProfile findById(int id) throws SQLException {
        try (Connection c = db.open();
             PreparedStatement st = c.prepareStatement(
                     "SELECT id, username, display_name, elo, wins, losses, draws, total_score FROM users WHERE id = ?")) {
            st.setInt(1, id);
            try (ResultSet rs = st.executeQuery()) {
                return rs.next() ? readProfile(rs) : null;
            }
        }
    }

    /** Cập nhật sau trận: Elo mới, thắng/hoà/thua (+1 đúng cột) và cộng điểm trận vào total_score. */
    public void applyMatchOutcome(int userId, int newElo, char outcome, int matchScore) throws SQLException {
        String col = switch (outcome) {
            case 'W' -> "wins";
            case 'L' -> "losses";
            case 'D' -> "draws";
            default -> throw new IllegalArgumentException("outcome: " + outcome);
        };
        try (Connection c = db.open();
             PreparedStatement st = c.prepareStatement(
                     "UPDATE users SET elo = ?, " + col + " = " + col + " + 1, total_score = total_score + ? WHERE id = ?")) {
            st.setInt(1, newElo);
            st.setInt(2, matchScore);
            st.setInt(3, userId);
            st.executeUpdate();
        }
    }

    /** Bảng xếp hạng: Elo giảm dần → thắng giảm dần → tổng điểm giảm dần. */
    public JsonArray leaderboard(int limit) throws SQLException {
        JsonArray rows = new JsonArray();
        try (Connection c = db.open();
             PreparedStatement st = c.prepareStatement(
                     "SELECT username, display_name, elo, wins, losses, draws, total_score FROM users" +
                     " ORDER BY elo DESC, wins DESC, total_score DESC LIMIT ?")) {
            st.setInt(1, limit);
            try (ResultSet rs = st.executeQuery()) {
                int rank = 1;
                while (rs.next()) {
                    JsonObject row = new JsonObject();
                    row.addProperty("rank", rank++);
                    row.addProperty("username", rs.getString("username"));
                    row.addProperty("displayName", rs.getString("display_name"));
                    row.addProperty("elo", rs.getInt("elo"));
                    row.addProperty("wins", rs.getInt("wins"));
                    row.addProperty("losses", rs.getInt("losses"));
                    row.addProperty("draws", rs.getInt("draws"));
                    row.addProperty("totalScore", rs.getLong("total_score"));
                    rows.add(row);
                }
            }
        }
        return rows;
    }

    private static UserProfile readProfile(ResultSet rs) throws SQLException {
        UserProfile p = new UserProfile();
        p.id = rs.getInt("id");
        p.username = rs.getString("username");
        p.displayName = rs.getString("display_name");
        p.elo = rs.getInt("elo");
        p.wins = rs.getInt("wins");
        p.losses = rs.getInt("losses");
        p.draws = rs.getInt("draws");
        p.totalScore = rs.getLong("total_score");
        return p;
    }
}
