package com.agridispatch.security;

import com.agridispatch.config.AgriSettings;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Token 簽發與驗證（規格第 2 節）
 * 格式：base64url(payloadJSON) + "." + base64url(HMAC-SHA256(secret, payloadPart))
 * payload：{"sub","role","ref","demo","ver","exp"}，有效期 12 小時；ver 為帳號 token 版本（AuthInterceptor 比對資料庫）
 */
@Service
public class TokenService {

    private static final Logger log = LoggerFactory.getLogger(TokenService.class);
    public static final long TTL_SECONDS = 12 * 60 * 60;

    private static final Base64.Encoder B64URL = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64URL_DEC = Base64.getUrlDecoder();

    private final byte[] secret;
    private final AgriSettings settings;
    private final ObjectMapper mapper = new ObjectMapper();

    public TokenService(AgriSettings settings) {
        this.settings = settings;
        String configured = settings.getTokenSecret();
        if (configured == null || configured.isBlank()) {
            byte[] rnd = new byte[32];
            new SecureRandom().nextBytes(rnd);
            this.secret = rnd;
            log.warn("[安全] 未設定 AGRI_TOKEN_SECRET（agri.security.token-secret），已於本次啟動隨機產生 Token 密鑰；重啟後所有既有 token 將失效。");
        } else {
            if (configured.length() < 32) {
                log.warn("[安全] AGRI_TOKEN_SECRET 長度不足 32 字元，建議改用更長的隨機字串。");
            }
            this.secret = configured.getBytes(StandardCharsets.UTF_8);
        }
    }

    public String issue(String sub, String role, String ref, boolean demo) {
        return issueWithVersion(sub, role, ref, demo, 0, Instant.now().getEpochSecond() + TTL_SECONDS);
    }

    /** 指定到期時間簽發（測試過期情境用） */
    public String issue(String sub, String role, String ref, boolean demo, long expEpochSec) {
        return issueWithVersion(sub, role, ref, demo, 0, expEpochSec);
    }

    /** 帶入帳號 token 版本簽發（登入用） */
    public String issueWithVersion(String sub, String role, String ref, boolean demo, int tokenVersion) {
        return issueWithVersion(sub, role, ref, demo, tokenVersion, Instant.now().getEpochSecond() + TTL_SECONDS);
    }

    public String issueWithVersion(String sub, String role, String ref, boolean demo, int tokenVersion, long expEpochSec) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sub", sub);
        payload.put("role", role);
        payload.put("ref", ref);
        payload.put("demo", demo);
        payload.put("ver", tokenVersion);
        payload.put("exp", expEpochSec);
        try {
            String payloadPart = B64URL.encodeToString(mapper.writeValueAsBytes(payload));
            return payloadPart + "." + B64URL.encodeToString(hmac(payloadPart));
        } catch (Exception e) {
            throw new IllegalStateException("Token 簽發失敗", e);
        }
    }

    /**
     * 驗證 token：簽章（constant-time 比對）→ 解析 → 到期檢查 → production 模式拒絕 demo token
     */
    public Optional<AuthContext> verify(String token) {
        if (token == null || token.isBlank() || token.length() > 4096) return Optional.empty();
        int dot = token.indexOf('.');
        if (dot <= 0 || dot != token.lastIndexOf('.') || dot == token.length() - 1) return Optional.empty();
        String payloadPart = token.substring(0, dot);
        String sigPart = token.substring(dot + 1);
        try {
            byte[] expected = hmac(payloadPart);
            byte[] actual = B64URL_DEC.decode(sigPart);
            if (!MessageDigest.isEqual(expected, actual)) return Optional.empty();

            JsonNode node = mapper.readTree(B64URL_DEC.decode(payloadPart));
            String sub = text(node, "sub");
            String role = text(node, "role");
            String ref = text(node, "ref");
            boolean demo = node.path("demo").asBoolean(false);
            long exp = node.path("exp").asLong(0);
            int ver = node.path("ver").asInt(0);
            if (sub == null || role == null || exp <= 0) return Optional.empty();
            if (Instant.now().getEpochSecond() >= exp) return Optional.empty();
            if (demo && !settings.isDemoEnabled()) return Optional.empty();
            return Optional.of(new AuthContext(sub, role, ref, demo, exp, settings.isDemoEnabled(), ver));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    /**
     * 以同一把密鑰產生其他用途的簽章（例：合法用工證明查驗碼），回傳大寫十六進位。
     * 簽章內容一律加上「用途:」前綴，且含 base64url 字元集以外的字元，不可能與 token 簽章混用。
     */
    public String signHex(String purpose, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            byte[] out = mac.doFinal((purpose + ":" + data).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(out.length * 2);
            for (byte b : out) sb.append(String.format("%02X", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("HMAC 計算失敗", e);
        }
    }

    private byte[] hmac(String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret, "HmacSHA256"));
        return mac.doFinal(data.getBytes(StandardCharsets.US_ASCII));
    }
}
