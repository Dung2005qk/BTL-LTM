package com.ltm.geoduel;

import com.ltm.geoduel.common.GeoUtil;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeoUtilTest {

    @Test
    void haversineZeroForSamePoint() {
        assertEquals(0.0, GeoUtil.haversineKm(21.0285, 105.8522, 21.0285, 105.8522), 1e-9);
    }

    @Test
    void haversineKnownDistances() {
        // Hà Nội (Hồ Gươm) → TP.HCM (Bến Thành): ~1140 km theo đường chim bay
        double hnHcm = GeoUtil.haversineKm(21.02888, 105.85222, 10.77253, 106.69800);
        assertEquals(1140, hnHcm, 15);

        // Hà Nội → Đà Nẵng: ~608 km
        double hnDn = GeoUtil.haversineKm(21.02888, 105.85222, 16.0678, 108.2208);
        assertEquals(608, hnDn, 15);

        // đối xứng
        assertEquals(hnHcm, GeoUtil.haversineKm(10.77253, 106.69800, 21.02888, 105.85222), 1e-9);
    }

    @Test
    void scoreMaxAtZeroDistance() {
        assertEquals(5000, GeoUtil.roundScore(0.0, 150));
    }

    @Test
    void scoreDecreasesWithDistance() {
        int s1 = GeoUtil.roundScore(1, 150);
        int s50 = GeoUtil.roundScore(50, 150);
        int s500 = GeoUtil.roundScore(500, 150);
        assertTrue(s1 > s50 && s50 > s500, "diem phai giam dan theo khoang cach");
        assertTrue(s1 <= 5000 && s500 >= 0);
        // giá trị cụ thể của công thức round(5000 * e^(-d/150))
        assertEquals(3583, GeoUtil.roundScore(50, 150));
        assertEquals(1839, GeoUtil.roundScore(150, 150));
    }

    @Test
    void scoreRejectsInvalidInput() {
        assertThrows(IllegalArgumentException.class, () -> GeoUtil.roundScore(-1, 150));
        assertThrows(IllegalArgumentException.class, () -> GeoUtil.roundScore(10, 0));
    }

    @Test
    void coordValidation() {
        assertTrue(GeoUtil.isValidCoord(21.0, 105.8));
        assertTrue(GeoUtil.isValidCoord(-90, -180));
        assertTrue(GeoUtil.isValidCoord(90, 180));
        assertFalse(GeoUtil.isValidCoord(90.1, 0));
        assertFalse(GeoUtil.isValidCoord(0, 180.1));
        assertFalse(GeoUtil.isValidCoord(Double.NaN, 0));
    }
}
