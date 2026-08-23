package com.ltm.geoduel;

import com.ltm.geoduel.common.EloCalculator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EloCalculatorTest {

    @Test
    void equalRatingsWinnerGains16() {
        // E = 0.5, K = 32 → thắng +16, thua -16, hoà 0
        assertEquals(1016, EloCalculator.newRating(1000, 1000, EloCalculator.SCORE_WIN));
        assertEquals(984, EloCalculator.newRating(1000, 1000, EloCalculator.SCORE_LOSS));
        assertEquals(1000, EloCalculator.newRating(1000, 1000, EloCalculator.SCORE_DRAW));
    }

    @Test
    void expectedScoreFormula() {
        assertEquals(0.5, EloCalculator.expectedScore(1000, 1000), 1e-9);
        // chênh 400 điểm → kỳ vọng ~0.909
        assertEquals(1.0 / (1 + Math.pow(10, -1)), EloCalculator.expectedScore(1400, 1000), 1e-9);
    }

    @Test
    void underdogGainsMoreWhenWinning() {
        int underdogGain = EloCalculator.ratingChange(1000, 1400, EloCalculator.SCORE_WIN);
        int favoriteGain = EloCalculator.ratingChange(1400, 1000, EloCalculator.SCORE_WIN);
        assertTrue(underdogGain > favoriteGain, "cua duoi thang phai duoc nhieu Elo hon");
        // 1000 vs 1400: E ≈ 0.0909 → thắng ≈ +29
        assertEquals(29, underdogGain);
        // 1400 vs 1000 thắng: E ≈ 0.909 → +3
        assertEquals(3, favoriteGain);
    }

    @Test
    void zeroSumWhenRatingsEqual() {
        int a = EloCalculator.ratingChange(1200, 1200, EloCalculator.SCORE_WIN);
        int b = EloCalculator.ratingChange(1200, 1200, EloCalculator.SCORE_LOSS);
        assertEquals(0, a + b, "cung Elo: tong thay doi bang 0");
    }

    @Test
    void drawMovesRatingsTowardEachOther() {
        // hoà: người Elo cao mất điểm, người Elo thấp được điểm
        assertTrue(EloCalculator.ratingChange(1400, 1000, EloCalculator.SCORE_DRAW) < 0);
        assertTrue(EloCalculator.ratingChange(1000, 1400, EloCalculator.SCORE_DRAW) > 0);
    }
}
