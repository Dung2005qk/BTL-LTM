package com.ltm.geoduel.common;

/**
 * Hệ thống Elo theo đặc tả: khởi tạo 1000, K = 32,
 * E = 1/(1+10^((Rb-Ra)/400)), R' = R + K(S-E) làm tròn về số nguyên gần nhất.
 */
public final class EloCalculator {
    public static final int INITIAL_ELO = 1000;
    public static final int K_FACTOR = 32;

    public static final double SCORE_WIN = 1.0;
    public static final double SCORE_DRAW = 0.5;
    public static final double SCORE_LOSS = 0.0;

    private EloCalculator() {}

    /** Xác suất kỳ vọng của người chơi có Elo {@code rating} trước đối thủ {@code opponentRating}. */
    public static double expectedScore(int rating, int opponentRating) {
        return 1.0 / (1.0 + Math.pow(10.0, (opponentRating - rating) / 400.0));
    }

    /** Elo mới của người chơi. actualScore: 1 thắng, 0.5 hoà, 0 thua. */
    public static int newRating(int rating, int opponentRating, double actualScore) {
        double expected = expectedScore(rating, opponentRating);
        return (int) Math.round(rating + K_FACTOR * (actualScore - expected));
    }

    /** Mức thay đổi Elo (có thể âm). */
    public static int ratingChange(int rating, int opponentRating, double actualScore) {
        return newRating(rating, opponentRating, actualScore) - rating;
    }
}
