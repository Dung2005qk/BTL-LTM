package com.ltm.geoduel.server.dao;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.ltm.geoduel.server.Db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;

public class MatchDao {
    private final Db db;

    public MatchDao(Db db) { this.db = db; }

    /** Tạo bản ghi trận mới, trả về id. */
    public int createMatch(int player1Id, int player2Id) throws SQLException {
        try (Connection c = db.open();
             PreparedStatement st = c.prepareStatement(
                     "INSERT INTO matches (player1_id, player2_id) VALUES (?,?)",
                     Statement.RETURN_GENERATED_KEYS)) {
            st.setInt(1, player1Id);
            st.setInt(2, player2Id);
            st.executeUpdate();
            try (ResultSet rs = st.getGeneratedKeys()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    /** Lưu kết quả một lượt. Toạ độ null = người chơi không gửi dự đoán. */
    public void saveRound(int matchId, int roundNo, int locationId,
                          Double g1Lat, Double g1Lng, Double d1Km, int score1,
                          Double g2Lat, Double g2Lng, Double d2Km, int score2) throws SQLException {
        try (Connection c = db.open();
             PreparedStatement st = c.prepareStatement(
                     "INSERT INTO match_rounds (match_id, round_no, location_id," +
                     " guess1_lat, guess1_lng, dist1_km, score1, guess2_lat, guess2_lng, dist2_km, score2)" +
                     " VALUES (?,?,?,?,?,?,?,?,?,?,?)")) {
            st.setInt(1, matchId);
            st.setInt(2, roundNo);
            st.setInt(3, locationId);
            setNullableDouble(st, 4, g1Lat);
            setNullableDouble(st, 5, g1Lng);
            setNullableDouble(st, 6, d1Km);
            st.setInt(7, score1);
            setNullableDouble(st, 8, g2Lat);
            setNullableDouble(st, 9, g2Lng);
            setNullableDouble(st, 10, d2Km);
            st.setInt(11, score2);
            st.executeUpdate();
        }
    }

    /** Chốt trận: tổng điểm, kết quả, lý do kết thúc và biến động Elo. */
    public void finishMatch(int matchId, int totalScore1, int totalScore2,
                            String result, String endReason,
                            int eloChange1, int eloChange2, int eloAfter1, int eloAfter2) throws SQLException {
        try (Connection c = db.open();
             PreparedStatement st = c.prepareStatement(
                     "UPDATE matches SET ended_at = CURRENT_TIMESTAMP, total_score1 = ?, total_score2 = ?," +
                     " result = ?, end_reason = ?, elo_change1 = ?, elo_change2 = ?, elo_after1 = ?, elo_after2 = ?" +
                     " WHERE id = ?")) {
            st.setInt(1, totalScore1);
            st.setInt(2, totalScore2);
            st.setString(3, result);
            st.setString(4, endReason);
            st.setInt(5, eloChange1);
            st.setInt(6, eloChange2);
            st.setInt(7, eloAfter1);
            st.setInt(8, eloAfter2);
            st.setInt(9, matchId);
            st.executeUpdate();
        }
    }

    /** Lịch sử đấu của một người chơi (mới nhất trước), đã quy về góc nhìn của người đó. */
    public JsonArray historyForUser(int userId, int limit) throws SQLException {
        JsonArray rows = new JsonArray();
        String sql =
            "SELECT m.id, m.started_at, m.result, m.end_reason," +
            " m.total_score1, m.total_score2, m.elo_change1, m.elo_change2, m.player1_id," +
            " u1.display_name AS name1, u2.display_name AS name2" +
            " FROM matches m JOIN users u1 ON u1.id = m.player1_id JOIN users u2 ON u2.id = m.player2_id" +
            " WHERE (m.player1_id = ? OR m.player2_id = ?) AND m.result IS NOT NULL" +
            " ORDER BY m.started_at DESC, m.id DESC LIMIT ?";
        try (Connection c = db.open(); PreparedStatement st = c.prepareStatement(sql)) {
            st.setInt(1, userId);
            st.setInt(2, userId);
            st.setInt(3, limit);
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    boolean iAmP1 = rs.getInt("player1_id") == userId;
                    String result = rs.getString("result");
                    String myResult;
                    if ("DRAW".equals(result)) myResult = "HÒA";
                    else if (("P1_WIN".equals(result) && iAmP1) || ("P2_WIN".equals(result) && !iAmP1)) myResult = "THẮNG";
                    else myResult = "THUA";

                    JsonObject row = new JsonObject();
                    row.addProperty("matchId", rs.getInt("id"));
                    row.addProperty("time", rs.getTimestamp("started_at").toLocalDateTime().toString());
                    row.addProperty("opponent", iAmP1 ? rs.getString("name2") : rs.getString("name1"));
                    row.addProperty("myScore", iAmP1 ? rs.getInt("total_score1") : rs.getInt("total_score2"));
                    row.addProperty("oppScore", iAmP1 ? rs.getInt("total_score2") : rs.getInt("total_score1"));
                    row.addProperty("result", myResult);
                    row.addProperty("eloChange", iAmP1 ? rs.getInt("elo_change1") : rs.getInt("elo_change2"));
                    row.addProperty("endReason", rs.getString("end_reason"));
                    rows.add(row);
                }
            }
        }
        return rows;
    }

    private static void setNullableDouble(PreparedStatement st, int idx, Double v) throws SQLException {
        if (v == null) st.setNull(idx, Types.DOUBLE);
        else st.setDouble(idx, v);
    }
}
