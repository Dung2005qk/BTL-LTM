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
        // Hà Nội (Hồ Gươm) → TP.HCM (Bến Thành): ~1140 km
        double hnHcm = GeoUtil.haversineKm(21.02888, 105.85222, 10.77253, 106.69800);
        assertEquals(1140, hnHcm, 15);

        // Hà Nội → Đà Nẵng: ~608 km
        double hnDn = GeoUtil.haversineKm(21.02888, 105.85222, 16.0678, 108.2208);
        assertEquals(608, hnDn, 15);

        // Đối xứng
        assertEquals(hnHcm, GeoUtil.haversineKm(10.77253, 106.69800, 21.02888, 105.85222), 1e-9);
    }

    @Test
    void scoreBands() {
        assertEquals(5000, GeoUtil.roundScore(0));
        assertEquals(5000, GeoUtil.roundScore(0.5));
        assertEquals(4750, GeoUtil.roundScore(1));
        assertEquals(4500, GeoUtil.roundScore(2));
        assertEquals(4250, GeoUtil.roundScore(3));
        assertEquals(4000, GeoUtil.roundScore(5));
        assertEquals(3500, GeoUtil.roundScore(7));
        assertEquals(3000, GeoUtil.roundScore(10));
        assertEquals(2500, GeoUtil.roundScore(13));
        assertEquals(2000, GeoUtil.roundScore(16));
        assertEquals(1500, GeoUtil.roundScore(20));
        assertEquals(1000, GeoUtil.roundScore(25));
        assertEquals(500, GeoUtil.roundScore(40));
        assertEquals(200, GeoUtil.roundScore(55));
        assertEquals(0, GeoUtil.roundScore(56));
    }

    @Test
    void scoreBoundary() {
        assertEquals(5000, GeoUtil.roundScore(0.5));
        assertEquals(4750, GeoUtil.roundScore(0.5001));
        assertEquals(4750, GeoUtil.roundScore(1));
        assertEquals(4500, GeoUtil.roundScore(1.0001));
        assertEquals(500, GeoUtil.roundScore(40));
        assertEquals(200, GeoUtil.roundScore(40.0001));
        assertEquals(200, GeoUtil.roundScore(55));
        assertEquals(0, GeoUtil.roundScore(55.0001));
    }

    @Test
    void scoreRejectsInvalidInput() {
        assertThrows(IllegalArgumentException.class, () -> GeoUtil.roundScore(-1));
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