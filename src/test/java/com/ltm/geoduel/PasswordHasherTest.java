package com.ltm.geoduel;

import com.ltm.geoduel.server.PasswordHasher;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PasswordHasherTest {

    @Test
    void verifyCorrectPassword() {
        String salt = PasswordHasher.newSaltHex();
        String hash = PasswordHasher.hash("mật khẩu tiếng Việt 123", salt);
        assertTrue(PasswordHasher.verify("mật khẩu tiếng Việt 123", salt, hash));
    }

    @Test
    void rejectWrongPassword() {
        String salt = PasswordHasher.newSaltHex();
        String hash = PasswordHasher.hash("abc123", salt);
        assertFalse(PasswordHasher.verify("abc124", salt, hash));
        assertFalse(PasswordHasher.verify("", salt, hash));
    }

    @Test
    void differentSaltsGiveDifferentHashes() {
        String s1 = PasswordHasher.newSaltHex();
        String s2 = PasswordHasher.newSaltHex();
        assertNotEquals(s1, s2, "salt phai ngau nhien");
        assertNotEquals(PasswordHasher.hash("abc", s1), PasswordHasher.hash("abc", s2));
    }

    @Test
    void hashAndSaltLengthMatchDbSchema() {
        String salt = PasswordHasher.newSaltHex();
        assertEquals(32, salt.length(), "salt CHAR(32)");
        assertEquals(64, PasswordHasher.hash("x", salt).length(), "hash CHAR(64)");
    }
}
