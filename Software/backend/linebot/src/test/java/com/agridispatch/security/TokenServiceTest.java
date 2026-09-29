package com.agridispatch.security;

import com.agridispatch.config.AgriSettings;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Token 簽發 / 驗證 / 過期 / 竄改 單元測試
 */
class TokenServiceTest {

    private static AgriSettings settings(boolean demo, String secret) {
        AgriSettings s = new AgriSettings();
        ReflectionTestUtils.setField(s, "demoEnabled", demo);
        ReflectionTestUtils.setField(s, "tokenSecret", secret);
        return s;
    }

    @Test
    void issueAndVerify() {
        TokenService ts = new TokenService(settings(false, "unit-test-secret-0123456789abcdef-xyz"));
        String token = ts.issue("farmer", "farmer", "F001", false);
        assertEquals(1, token.chars().filter(c -> c == '.').count(), "格式應為 payload.signature");

        Optional<AuthContext> ctx = ts.verify(token);
        assertTrue(ctx.isPresent());
        assertEquals("farmer", ctx.get().sub());
        assertEquals("farmer", ctx.get().role());
        assertEquals("F001", ctx.get().ref());
        assertFalse(ctx.get().demo());
        long exp = ctx.get().expEpochSec() - Instant.now().getEpochSecond();
        assertTrue(exp > 11 * 3600 && exp <= 12 * 3600, "有效期應為 12 小時");

        String payloadJson = new String(Base64.getUrlDecoder().decode(token.split("\\.")[0]), StandardCharsets.UTF_8);
        assertTrue(payloadJson.contains("\"sub\":\"farmer\"") && payloadJson.contains("\"exp\":"));
    }

    @Test
    void expiredTokenRejected() {
        TokenService ts = new TokenService(settings(false, "unit-test-secret-0123456789abcdef-xyz"));
        String token = ts.issue("farmer", "farmer", "F001", false, Instant.now().getEpochSecond() - 1);
        assertTrue(ts.verify(token).isEmpty());
    }

    @Test
    void tamperedPayloadRejected() {
        TokenService ts = new TokenService(settings(false, "unit-test-secret-0123456789abcdef-xyz"));
        String token = ts.issue("farmer", "farmer", "F001", false);
        String sig = token.split("\\.")[1];
        String forgedPayload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("{\"sub\":\"farmer\",\"role\":\"admin_tech\",\"ref\":\"F001\",\"demo\":false,\"exp\":"
                        + (Instant.now().getEpochSecond() + 3600) + "}").getBytes(StandardCharsets.UTF_8));
        assertTrue(ts.verify(forgedPayload + "." + sig).isEmpty(), "竄改角色後簽章不符應拒絕");
    }

    @Test
    void tamperedSignatureAndGarbageRejected() {
        TokenService ts = new TokenService(settings(false, "unit-test-secret-0123456789abcdef-xyz"));
        String token = ts.issue("farmer", "farmer", "F001", false);
        char last = token.charAt(token.length() - 1);
        String flipped = token.substring(0, token.length() - 1) + (last == 'A' ? 'B' : 'A');
        assertTrue(ts.verify(flipped).isEmpty());
        assertTrue(ts.verify("jwt-token-admin_tech-123").isEmpty(), "舊版假 token 應拒絕");
        assertTrue(ts.verify("").isEmpty());
        assertTrue(ts.verify(null).isEmpty());
        assertTrue(ts.verify("a.b.c").isEmpty());
    }

    @Test
    void differentSecretRejected() {
        TokenService a = new TokenService(settings(false, "secret-A-0123456789abcdef-0123456789"));
        TokenService b = new TokenService(settings(false, "secret-B-0123456789abcdef-0123456789"));
        assertTrue(b.verify(a.issue("admin_tech", "admin_tech", "ADM01", false)).isEmpty());
    }

    @Test
    void demoTokenOnlyValidInDemoMode() {
        String secret = "unit-test-secret-0123456789abcdef-xyz";
        TokenService demo = new TokenService(settings(true, secret));
        TokenService prod = new TokenService(settings(false, secret));
        String demoToken = demo.issue("farmer", "farmer", "F001", true);

        Optional<AuthContext> ctx = demo.verify(demoToken);
        assertTrue(ctx.isPresent());
        assertTrue(ctx.get().isAdmin(), "demo 模式下 demo token 視同 admin");
        assertTrue(prod.verify(demoToken).isEmpty(), "production 模式一律拒絕 demo token");
    }

    @Test
    void randomSecretWhenNotConfigured() {
        TokenService a = new TokenService(settings(false, ""));
        TokenService b = new TokenService(settings(false, ""));
        String t = a.issue("x", "farmer", "F001", false);
        assertTrue(a.verify(t).isPresent());
        assertTrue(b.verify(t).isEmpty(), "未設定密鑰時每次啟動隨機產生");
    }
}
