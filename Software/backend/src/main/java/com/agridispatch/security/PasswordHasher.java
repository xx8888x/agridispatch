package com.agridispatch.security;

import org.springframework.stereotype.Component;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 密碼雜湊（規格第 8 節）：PBKDF2-HMAC-SHA256，310,000 次、16 bytes salt
 * 儲存格式：pbkdf2$&lt;iter&gt;$&lt;saltB64&gt;$&lt;hashB64&gt;
 * 僅使用 JDK 內建 javax.crypto，不依賴 spring-security。
 */
@Component
public class PasswordHasher {

    public static final String PREFIX = "pbkdf2";
    public static final int DEFAULT_ITERATIONS = 310_000;
    private static final int SALT_BYTES = 16;
    private static final int HASH_BITS = 256;
    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";

    private final SecureRandom random = new SecureRandom();
    private final int iterations;

    /** 用於「查無帳號」時仍執行一次雜湊比對，降低以回應時間猜測帳號是否存在 */
    private volatile String dummyHash;

    public PasswordHasher() {
        this(DEFAULT_ITERATIONS);
    }

    PasswordHasher(int iterations) {
        this.iterations = iterations;
    }

    public String hash(String rawPassword) {
        if (rawPassword == null || rawPassword.isEmpty()) throw new IllegalArgumentException("密碼不得為空");
        byte[] salt = new byte[SALT_BYTES];
        random.nextBytes(salt);
        byte[] hash = pbkdf2(rawPassword.toCharArray(), salt, iterations);
        return PREFIX + "$" + iterations + "$" + Base64.getEncoder().encodeToString(salt)
                + "$" + Base64.getEncoder().encodeToString(hash);
    }

    public boolean matches(String rawPassword, String stored) {
        if (rawPassword == null || rawPassword.isEmpty() || stored == null || !isHashed(stored)) return false;
        String[] parts = stored.split("\\$");
        if (parts.length != 4) return false;
        try {
            int iter = Integer.parseInt(parts[1]);
            if (iter < 1 || iter > 10_000_000) return false;
            byte[] salt = Base64.getDecoder().decode(parts[2]);
            byte[] expected = Base64.getDecoder().decode(parts[3]);
            byte[] actual = pbkdf2(rawPassword.toCharArray(), salt, iter);
            return MessageDigest.isEqual(expected, actual);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** 查無帳號時呼叫，消耗與正常比對相近的時間 */
    public void burnTime(String rawPassword) {
        String d = dummyHash;
        if (d == null) {
            d = hash("dummy-password-for-timing");
            dummyHash = d;
        }
        matches(rawPassword == null || rawPassword.isEmpty() ? "x" : rawPassword, d);
    }

    public static boolean isHashed(String stored) {
        return stored != null && stored.startsWith(PREFIX + "$");
    }

    private static byte[] pbkdf2(char[] password, byte[] salt, int iter) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password, salt, iter, HASH_BITS);
            try {
                return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).getEncoded();
            } finally {
                spec.clearPassword();
            }
        } catch (Exception e) {
            throw new IllegalStateException("PBKDF2 雜湊失敗", e);
        }
    }
}
