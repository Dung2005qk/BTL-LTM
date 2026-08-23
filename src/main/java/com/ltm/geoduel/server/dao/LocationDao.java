package com.ltm.geoduel.server.dao;

import com.ltm.geoduel.server.Db;
import com.ltm.geoduel.server.model.LocationData;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public class LocationDao {
    private final Db db;

    public LocationDao(Db db) { this.db = db; }

    /** Chọn ngẫu nhiên {@code count} địa điểm đang hoạt động (kèm ảnh). */
    public List<LocationData> pickRandom(int count) throws SQLException {
        List<LocationData> result = new ArrayList<>();
        try (Connection c = db.open()) {
            try (PreparedStatement st = c.prepareStatement(
                    "SELECT id, slug, name, country, target_lat, target_lng FROM locations" +
                    " WHERE active = 1 ORDER BY RAND() LIMIT ?")) {
                st.setInt(1, count);
                try (ResultSet rs = st.executeQuery()) {
                    while (rs.next()) {
                        LocationData loc = new LocationData();
                        loc.id = rs.getInt("id");
                        loc.slug = rs.getString("slug");
                        loc.name = rs.getString("name");
                        loc.country = rs.getString("country");
                        loc.targetLat = rs.getDouble("target_lat");
                        loc.targetLng = rs.getDouble("target_lng");
                        result.add(loc);
                    }
                }
            }
            try (PreparedStatement st = c.prepareStatement(
                    "SELECT file_path, is_pano FROM location_images WHERE location_id = ? ORDER BY sort_order")) {
                for (LocationData loc : result) {
                    st.setInt(1, loc.id);
                    try (ResultSet rs = st.executeQuery()) {
                        while (rs.next()) {
                            loc.images.add(new LocationData.ClueImage(
                                    rs.getString("file_path"), rs.getBoolean("is_pano")));
                        }
                    }
                }
            }
        }
        return result;
    }

    public int countActive() throws SQLException {
        try (Connection c = db.open();
             PreparedStatement st = c.prepareStatement("SELECT COUNT(*) FROM locations WHERE active = 1");
             ResultSet rs = st.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
