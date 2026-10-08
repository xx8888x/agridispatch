package com.agridispatch;

import com.agridispatch.security.LoginRateLimiter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 測試期開關 AGRI_LOGIN_LOCK_ADMIN_ONLY=true：只有科技後台（admin_tech）登入失敗會鎖定，其餘角色與 IP 不鎖。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:agri_login_lock_admin_only_test;DB_CLOSE_DELAY=-1",
        "agri.demo.enabled=false",
        "agri.security.token-secret=unit-test-token-secret-0123456789abcdef",
        "agri.security.admin-password=Admin#Pass2026",
        "agri.security.login-lock-admin-only=true"
})
@AutoConfigureMockMvc
class LoginLockAdminOnlyTest extends ApiTestSupport {

    @Autowired LoginRateLimiter loginRateLimiter;

    @BeforeEach
    void setUp() { loginRateLimiter.reset(); }

    private int loginFrom(String ip, String identifier, String password) throws Exception {
        return mvc.perform(post("/api/auth/login")
                .with(req -> { req.setRemoteAddr(ip); return req; })
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of("identifier", identifier, "password", password))))
                .andReturn().getResponse().getStatus();
    }

    @Test
    void nonAdminAccountsAndIpNeverLocked() throws Exception {
        String admin = login("admin_tech", "Admin#Pass2026");
        assertEquals(200, call("POST", "/api/auth/reset-password", admin,
                Map.of("identifier", "farmer", "newPassword", "Farmer#2026pw")).getResponse().getStatus());
        for (int i = 0; i < 40; i++) {
            assertEquals(401, loginFrom("10.1.0.1", "farmer", "wrong-" + i));
        }
        assertEquals(200, loginFrom("10.1.0.1", "farmer", "Farmer#2026pw"));
        // 同 IP 的科技後台登入也不受影響（非 admin 失敗不計 IP）
        assertEquals(200, loginFrom("10.1.0.1", "admin_tech", "Admin#Pass2026"));
    }

    @Test
    void adminStillLockedAfterFiveFailures() throws Exception {
        for (int i = 0; i < 5; i++) {
            assertEquals(401, loginFrom("10.2.0.1", "admin_tech", "wrong-" + i));
        }
        assertEquals(429, loginFrom("10.2.0.2", "admin_tech", "Admin#Pass2026"));
    }
}
