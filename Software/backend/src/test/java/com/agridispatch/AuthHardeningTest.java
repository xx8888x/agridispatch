package com.agridispatch;

import com.agridispatch.entity.User;
import com.agridispatch.repository.UserRepository;
import com.agridispatch.security.LoginRateLimiter;
import com.agridispatch.security.TokenService;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 認證強化回歸測試（2026-10-08）：
 * 登入失敗次數限制（帳號 15 分鐘 5 次、IP 15 分鐘 30 次 → 429），
 * 改密碼 / 重設密碼 / 刪除帳號 / 變更角色後舊 token 立即失效（token 版本）。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:agri_auth_hardening_test;DB_CLOSE_DELAY=-1",
        "agri.demo.enabled=false",
        "agri.security.token-secret=unit-test-token-secret-0123456789abcdef",
        "agri.security.admin-password=Admin#Pass2026"
})
@AutoConfigureMockMvc
class AuthHardeningTest extends ApiTestSupport {

    @Autowired LoginRateLimiter loginRateLimiter;
    @Autowired UserRepository userRepository;
    @Autowired TokenService tokenService;

    private String adminToken;

    @BeforeEach
    void setUp() throws Exception {
        loginRateLimiter.reset();
        adminToken = login("admin_tech", "Admin#Pass2026");
    }

    private MvcResult loginFrom(String ip, String identifier, String password) throws Exception {
        return mvc.perform(post("/api/auth/login")
                .with(req -> { req.setRemoteAddr(ip); return req; })
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of("identifier", identifier, "password", password)))).andReturn();
    }

    private void resetPassword(String identifier, String pw) throws Exception {
        assertEquals(200, call("POST", "/api/auth/reset-password", adminToken,
                Map.of("identifier", identifier, "newPassword", pw)).getResponse().getStatus());
    }

    private int meStatus(String token) throws Exception {
        return call("GET", "/api/auth/me", token, null).getResponse().getStatus();
    }

    // ------------------------------------------------------------------ 登入次數限制

    @Test
    void accountLockedAfterFiveFailures() throws Exception {
        resetPassword("farmer", "Farmer#2026pw");
        for (int i = 0; i < 5; i++) {
            assertEquals(401, loginFrom("10.0.0.1", "farmer", "wrong-" + i).getResponse().getStatus());
        }
        // 第 6 次即使密碼正確也回 429，格式同 ApiException
        MvcResult locked = loginFrom("10.0.0.1", "farmer", "Farmer#2026pw");
        assertEquals(429, locked.getResponse().getStatus());
        JsonNode body = json(locked);
        assertFalse(body.get("success").asBoolean());
        assertEquals("嘗試次數過多，請 15 分鐘後再試", body.get("error").asText());
        assertFalse(body.has("token"));
        // 帳號鎖定與來源 IP 無關
        assertEquals(429, loginFrom("10.0.0.2", "farmer", "Farmer#2026pw").getResponse().getStatus());
        // 其他帳號不受影響
        assertEquals(200, loginFrom("10.0.0.1", "admin_tech", "Admin#Pass2026").getResponse().getStatus());
    }

    @Test
    void successfulLoginClearsAccountCounter() throws Exception {
        resetPassword("farmer_yilan", "Yilan#2026pw");
        for (int round = 0; round < 2; round++) {
            for (int i = 0; i < 4; i++) {
                assertEquals(401, loginFrom("10.0.1.1", "farmer_yilan", "bad").getResponse().getStatus());
            }
            assertEquals(200, loginFrom("10.0.1.1", "farmer_yilan", "Yilan#2026pw").getResponse().getStatus());
        }
    }

    @Test
    void ipLockedAfterThirtyFailures() throws Exception {
        for (int i = 0; i < 30; i++) {
            assertEquals(401, loginFrom("10.9.9.9", "nobody-" + i, "x").getResponse().getStatus());
        }
        assertEquals(429, loginFrom("10.9.9.9", "admin_tech", "Admin#Pass2026").getResponse().getStatus());
        assertEquals(200, loginFrom("10.9.9.8", "admin_tech", "Admin#Pass2026").getResponse().getStatus());
    }

    // ------------------------------------------------------------------ token 版本

    @Test
    void changePasswordInvalidatesAllExistingTokens() throws Exception {
        resetPassword("farmer", "Farmer#2026pw");
        String t1 = login("farmer", "Farmer#2026pw");
        String t2 = login("farmer", "Farmer#2026pw");
        assertEquals(200, meStatus(t1));

        MvcResult r = call("POST", "/api/auth/change-password", t1,
                Map.of("oldPassword", "Farmer#2026pw", "newPassword", "Farmer#2026new"));
        assertEquals(200, r.getResponse().getStatus());
        assertEquals(401, meStatus(t1));
        assertEquals(401, meStatus(t2));
        assertEquals(401, call("GET", "/api/orders", t2, null).getResponse().getStatus());

        String t3 = login("farmer", "Farmer#2026new");
        assertEquals(200, meStatus(t3));
        assertEquals(200, meStatus(adminToken), "其他帳號的 token 不受影響");
    }

    @Test
    void workerChangePasswordInvalidatesToken() throws Exception {
        resetPassword("W006", "Worker#2026pw");
        String t = login("W006", "Worker#2026pw");
        assertEquals(200, call("POST", "/api/auth/change-password", t,
                Map.of("oldPassword", "Worker#2026pw", "newPassword", "Worker#2026new")).getResponse().getStatus());
        assertEquals(401, meStatus(t));
        assertEquals(200, meStatus(login("W006", "Worker#2026new")));
    }

    @Test
    void accountListIsAdminOnlyAndHasNoSecrets() throws Exception {
        resetPassword("farmer", "Farmer#2026pw");
        resetPassword("W006", "Worker#2026pw");
        assertEquals(403, call("GET", "/api/auth/accounts", login("farmer", "Farmer#2026pw"), null).getResponse().getStatus());
        assertEquals(403, call("GET", "/api/auth/accounts", login("W006", "Worker#2026pw"), null).getResponse().getStatus());
        assertEquals(401, call("GET", "/api/auth/accounts", null, null).getResponse().getStatus());

        MvcResult r = call("GET", "/api/auth/accounts", adminToken, null);
        assertEquals(200, r.getResponse().getStatus());
        String raw = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertFalse(raw.contains("password\"") || raw.contains("passwordHash") || raw.contains("arcNumber"), "不得輸出密碼雜湊或證號");
        JsonNode farmer = null, w006 = null;
        for (JsonNode a : json(r).get("data")) {
            if ("farmer".equals(a.get("identifier").asText())) farmer = a;
            if ("W006".equals(a.get("identifier").asText())) w006 = a;
        }
        assertNotNull(farmer);
        assertEquals("farmer", farmer.get("role").asText());
        assertEquals("user", farmer.get("kind").asText());
        assertTrue(farmer.get("hasPassword").asBoolean());
        assertNotNull(w006);
        assertEquals("worker", w006.get("kind").asText());
        assertTrue(w006.get("hasPassword").asBoolean());
    }

    @Test
    void adminResetPasswordInvalidatesTargetTokens() throws Exception {
        resetPassword("W001", "Worker#2026pw");
        String worker = login("AD88123451", "Worker#2026pw");
        resetPassword("farmer_yilan", "Yilan#2026pw");
        String farmer = login("farmer_yilan", "Yilan#2026pw");
        assertEquals(200, meStatus(worker));

        resetPassword("W001", "Worker#2026reset");
        resetPassword("farmer_yilan", "Yilan#2026reset");
        assertEquals(401, meStatus(worker));
        assertEquals(401, meStatus(farmer));
        assertEquals(200, meStatus(adminToken));
    }

    @Test
    void deletedWorkerTokenIsRejected() throws Exception {
        MvcResult created = call("POST", "/api/workers", adminToken,
                Map.of("name", "刪除測試工", "tier", "tier2_local_flex", "phone", "0900-000-111"));
        assertEquals(201, created.getResponse().getStatus());
        String id = json(created).get("data").get("id").asText();
        resetPassword(id, "Temp#2026pw");
        String t = login(id, "Temp#2026pw");
        assertEquals(200, meStatus(t));

        assertEquals(200, call("DELETE", "/api/workers/" + id, adminToken, null).getResponse().getStatus());
        assertEquals(401, meStatus(t));
        assertEquals(401, call("GET", "/api/workers/" + id, t, null).getResponse().getStatus());
    }

    @Test
    void workerTierChangeInvalidatesTokenButOtherEditsDoNot() throws Exception {
        MvcResult created = call("POST", "/api/workers", adminToken,
                Map.of("name", "角色變更測試工", "tier", "tier2_local_flex", "phone", "0900-000-222"));
        String id = json(created).get("data").get("id").asText();
        resetPassword(id, "Temp#2026pw");
        String t = login(id, "Temp#2026pw");
        assertEquals("worker_local", json(call("GET", "/api/auth/me", t, null)).get("user").get("role").asText());

        // 一般欄位修改不影響 token
        assertEquals(200, call("PUT", "/api/workers/" + id, adminToken, Map.of("phone", "0900-000-333")).getResponse().getStatus());
        assertEquals(200, meStatus(t));
        // 同一 tier 再送一次也不影響
        assertEquals(200, call("PUT", "/api/workers/" + id, adminToken, Map.of("tier", "tier2_local_flex")).getResponse().getStatus());
        assertEquals(200, meStatus(t));

        // tier 變更（worker_local → worker_foreign）→ 舊 token 失效
        assertEquals(200, call("PUT", "/api/workers/" + id, adminToken, Map.of("tier", "tier1_foreign")).getResponse().getStatus());
        assertEquals(401, meStatus(t));
        String t2 = login(id, "Temp#2026pw");
        assertEquals("worker_foreign", json(call("GET", "/api/auth/me", t2, null)).get("user").get("role").asText());
    }

    @Test
    void userRoleChangeInvalidatesToken() throws Exception {
        resetPassword("worker_labor", "Labor#2026pw");
        String t = login("worker_labor", "Labor#2026pw");
        assertEquals(200, meStatus(t));

        User u = userRepository.findById("worker_labor").orElseThrow();
        u.setRole("worker_local");          // 角色相同：不變更版本
        userRepository.save(u);
        assertEquals(200, meStatus(t));

        u = userRepository.findById("worker_labor").orElseThrow();
        u.setRole("worker_foreign");
        userRepository.save(u);
        assertEquals(401, meStatus(t));

        u = userRepository.findById("worker_labor").orElseThrow();
        u.setRole("worker_local");
        userRepository.save(u);
    }

    @Test
    void tokenOfDeletedUserOrStaleVersionIsRejected() throws Exception {
        // 帳號不存在（例：已刪除）的 token 不可使用
        String ghost = tokenService.issueWithVersion("ghost_user", "admin_tech", "ADM99", false, 0);
        assertEquals(401, call("GET", "/api/workers", ghost, null).getResponse().getStatus());

        // 版本不符（舊版本號）的 token 不可使用；版本相符則可
        int current = userRepository.findById("admin_tech").orElseThrow().getTokenVersion();
        String stale = tokenService.issueWithVersion("admin_tech", "admin_tech", "ADM01", false, current + 1);
        assertEquals(401, call("GET", "/api/workers", stale, null).getResponse().getStatus());
        String fresh = tokenService.issueWithVersion("admin_tech", "admin_tech", "ADM01", false, current);
        assertEquals(200, call("GET", "/api/workers", fresh, null).getResponse().getStatus());

        // token 版本不輸出到 API 回應
        String body = call("GET", "/api/workers/W001", adminToken, null).getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertFalse(body.contains("tokenVersion"));
    }
}
