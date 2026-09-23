package com.agridispatch.security;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class PasswordHasherTest {

    private final PasswordHasher hasher = new PasswordHasher();

    @Test
    void hashFormatFollowsSpec() {
        String h = hasher.hash("S3cure!pass");
        String[] parts = h.split("\\$");
        assertEquals(4, parts.length);
        assertEquals("pbkdf2", parts[0]);
        assertEquals("310000", parts[1]);
        assertEquals(16, Base64.getDecoder().decode(parts[2]).length, "salt 16 bytes");
        assertEquals(32, Base64.getDecoder().decode(parts[3]).length, "SHA-256 輸出 32 bytes");
        assertTrue(PasswordHasher.isHashed(h));
        assertFalse(PasswordHasher.isHashed("123456"));
    }

    @Test
    void matchesOnlyCorrectPassword() {
        String h = hasher.hash("S3cure!pass");
        assertTrue(hasher.matches("S3cure!pass", h));
        assertFalse(hasher.matches("s3cure!pass", h));
        assertFalse(hasher.matches("", h));
        assertFalse(hasher.matches(null, h));
        assertFalse(hasher.matches("S3cure!pass", "S3cure!pass"), "明碼不可視為雜湊");
        assertFalse(hasher.matches("S3cure!pass", "pbkdf2$abc$def"), "格式錯誤不應丟例外");
    }

    @Test
    void saltIsRandom() {
        assertNotEquals(hasher.hash("same"), hasher.hash("same"));
    }

    @Test
    void customIterationsRoundTrip() {
        PasswordHasher fast = new PasswordHasher(1000);
        String h = fast.hash("abc12345");
        assertTrue(h.startsWith("pbkdf2$1000$"));
        // 以 iteration 數儲存於雜湊中，預設 hasher 也能驗證
        assertTrue(hasher.matches("abc12345", h));
    }
}
