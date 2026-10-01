package com.ltm.geoduel.common;

/**
 * Khoảng cách Haversine và điểm địa lý của một lượt.
 * Chỉ server dùng để chấm điểm; client chỉ hiển thị kết quả server gửi về.
 */
public final class GeoUtil {
    /** Bán kính Trái Đất trung bình (km). */
    public static final double EARTH_RADIUS_KM = 6371.0088;
    /** Điểm tối đa của một lượt. */
    public static final int MAX_ROUND_SCORE = 5000;
    /**
     * Hằng số suy giảm điểm mặc định (km) — chỉnh theo phạm vi bản đồ.
     * Phạm vi Việt Nam dùng 150 km (đặt trong config.properties, key score.decay.km).
     */
    public static final double DEFAULT_SCORE_DECAY_KM = 150.0;

    private GeoUtil() {}

    /** Khoảng cách theo bề mặt Trái Đất giữa hai toạ độ (km), công thức Haversine. */
    public static double haversineKm(double lat1, double lng1, double lat2, double lng2) {
        double phi1 = Math.toRadians(lat1);
        double phi2 = Math.toRadians(lat2);
        double dPhi = Math.toRadians(lat2 - lat1);
        double dLambda = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dPhi / 2) * Math.sin(dPhi / 2)
                 + Math.cos(phi1) * Math.cos(phi2) * Math.sin(dLambda / 2) * Math.sin(dLambda / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return EARTH_RADIUS_KM * c;
    }

    /**
     * Điểm lượt: round(5000 * e^(-d/decay)). d = 0 → 5000 điểm; càng xa càng ít điểm.
     */
//    public static int roundScore(double distanceKm, double decayKm) {
//        if (distanceKm < 0) throw new IllegalArgumentException("distanceKm < 0");
//        if (decayKm <= 0) throw new IllegalArgumentException("decayKm <= 0");
//        return (int) Math.round(MAX_ROUND_SCORE * Math.exp(-distanceKm / decayKm));
//    }
    
    public static int roundScore(double distanceKm) {
        if (distanceKm < 0) throw new IllegalArgumentException("distanceKm < 0");

        if (distanceKm <= 0.5) return 5000;
        if (distanceKm <= 1) return 4750;
        if (distanceKm <= 2) return 4500;
        if (distanceKm <= 3) return 4250;
        if (distanceKm <= 5) return 4000;
        if (distanceKm <= 7) return 3500;
        if (distanceKm <= 10) return 3000;
        if (distanceKm <= 13) return 2500;
        if (distanceKm <= 16) return 2000;
        if (distanceKm <= 20) return 1500;
        if (distanceKm <= 25) return 1000;
        if (distanceKm <= 40) return 500;
        if (distanceKm <= 55) return 200;
        return 0;
    }
    
    /** Điểm lượt với hệ số suy giảm mặc định. */
//    public static int roundScore(double distanceKm) {
//        return roundScore(distanceKm, DEFAULT_SCORE_DECAY_KM);
//    }

    /** Kiểm tra toạ độ hợp lệ (client không đáng tin). */
    public static boolean isValidCoord(double lat, double lng) {
        return !Double.isNaN(lat) && !Double.isNaN(lng)
            && lat >= -90.0 && lat <= 90.0 && lng >= -180.0 && lng <= 180.0;
    }
}
