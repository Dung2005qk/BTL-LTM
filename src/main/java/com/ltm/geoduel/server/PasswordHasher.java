package com.ltm.geoduel.server;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.HexFormat;

/** Băm mật khẩu PBKDF2WithHmacSHA256: salt 16 byte, 65536 vòng, khoá 256 bit. */
public final class PasswordHasher {
    private static final int ITERATIONS = 65536;
    private static final int KEY_BITS = 256;
    private static final SecureRandom RANDOM = new SecureRandom();

    private PasswordHasher() {}

    /** @return salt mới dạng hex (32 ký tự). */
    public static String newSaltHex() {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        return HexFormat.of().formatHex(salt);
    }

    /** @return hash dạng hex (64 ký tự). */
    public static String hash(String password, String saltHex) {
        try {
            byte[] salt = HexFormat.of().parseHex(saltHex);
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, ITERATIONS, KEY_BITS);
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            byte[] hash = factory.generateSecret(spec).getEncoded();
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new IllegalStateException("PBKDF2 khong kha dung", e);
        }
    }

    /** So sánh chống timing-attack. */
    public static boolean verify(String password, String saltHex, String expectedHashHex) {
        byte[] actual = HexFormat.of().parseHex(hash(password, saltHex));
        byte[] expected = HexFormat.of().parseHex(expectedHashHex);
        return java.security.MessageDigest.isEqual(actual, expected);
    }
}
