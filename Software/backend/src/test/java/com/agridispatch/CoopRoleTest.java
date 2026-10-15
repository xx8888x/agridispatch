package com.agridispatch;

import com.agridispatch.entity.User;
import com.agridispatch.repository.UserRepository;
import com.agridispatch.security.PasswordHasher;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 合作社角色 coop_admin（規格 11）：雇主責任範圍可操作，系統設定與科技公司專屬功能一律 403，
 * 密碼重設不能動後台帳號，工人證件號碼仍遮罩（production 模式）。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:agri_coop_test;DB_CLOSE_DELAY=-1",
        "agri.demo.enabled=false",
        "agri.security.token-secret=unit-test-token-secret-0123456789abcdef",
        "agri.security.admin-password=Admin#Pass2026"
})
@AutoConfigureMockMvc
class CoopRoleTest extends ApiTestSupport {

    static final String COOP_PW = "Coop#2026pw";

    @Autowired UserRepository userRepository;
    @Autowired PasswordHasher passwordHasher;

    private String admin;
    private String coop;

    @BeforeEach
    void setUp() throws Exception {
        if (!userRepository.existsById("coop_test")) {
            userRepository.save(new User("coop_test", passwordHasher.hash(COOP_PW), "合作社測試員", "coop_admin", "COOP01", "合作社後台人員"));
        }
        admin = login("admin_tech", "Admin#Pass2026");
        coop = login("coop_test", COOP_PW);
    }

    private int status(String method, String url, String token, Object body) throws Exception {
        return call(method, url, token, body).getResponse().getStatus();
    }

    @Test
    void loginReturnsCoopRole() throws Exception {
        JsonNode me = json(call("GET", "/api/auth/me", coop, null)).get("user");
        assertEquals("coop_admin", me.get("role").asText());
        assertEquals("COOP01", me.get("refId").asText());
    }

    @Test
    void employerResponsibilitiesAreAllowed() throws Exception {
        assertEquals(200, status("GET", "/api/workers", coop, null), "工人名冊");
        assertEquals(200, status("GET", "/api/leaves", coop, null), "請假審核清單");
        assertEquals(200, status("GET", "/api/payroll/months", coop, null), "月結算");
        assertEquals(200, status("GET", "/api/insurance/workers", coop, null), "加退保");
        assertEquals(200, status("GET", "/api/matching/orders/ORD-2026-005", coop, null), "派工媒合");
        assertEquals(200, status("GET", "/api/rosters", coop, null), "共享班表");
        assertEquals(200, status("GET", "/api/skills/workers", coop, null), "熟手標籤");
        assertEquals(200, status("GET", "/api/orders", coop, null), "訂單（唯讀）");
        assertEquals(200, status("GET", "/api/auth/accounts", coop, null), "帳號清單");
        // 工人資料（本人或後台人員）
        assertEquals(200, status("GET", "/api/workers/W026/schedule", coop, null));
        assertEquals(200, status("GET", "/api/workers/W026/leaves", coop, null));
    }

    @Test
    void techOnlyFeaturesAreForbidden() throws Exception {
        assertEquals(403, status("POST", "/api/crops", coop, Map.of("id", "x", "name", "x")), "作物基準");
        assertEquals(403, status("POST", "/api/brochures", coop, Map.of("id", "x")), "文宣");
        assertEquals(403, status("GET", "/api/talents", coop, null), "人才庫");
        assertEquals(403, status("POST", "/api/dispatch/weather-scenario", coop, Map.of("scenarioId", "sunny")), "切換天候");
        assertEquals(403, status("GET", "/api/line/config", coop, null), "LINE 設定");
        assertEquals(403, status("GET", "/api/line/bindings", coop, null), "LINE 綁定清單");
        assertEquals(403, status("POST", "/api/line/notify/tomorrow", coop, Map.of()), "LINE 推播");
        assertEquals(403, status("POST", "/api/notifications/send", coop, Map.of("title", "x", "message", "x")), "App 推播");
        assertEquals(403, status("PUT", "/api/orders/ORD-2026-005/status", coop, Map.of("status", "cancelled")), "訂單狀態");
        assertEquals(403, status("GET", "/api/account-deletions", coop, null), "刪除帳號審核");
        assertEquals(403, status("POST", "/api/orders", coop, Map.of("farmerName", "x")), "代建訂單");
    }

    @Test
    void passwordResetCannotTouchBackOfficeAccounts() throws Exception {
        assertEquals(200, status("POST", "/api/auth/reset-password", coop, Map.of("identifier", "farmer", "newPassword", "Farmer#2026pw")));
        assertEquals(200, status("POST", "/api/auth/reset-password", coop, Map.of("identifier", "W006", "newPassword", "Worker#2026pw")));
        assertEquals(403, status("POST", "/api/auth/reset-password", coop, Map.of("identifier", "admin_tech", "newPassword", "Hacked#2026pw")));
        assertEquals(403, status("POST", "/api/auth/reset-password", coop, Map.of("identifier", "coop_test", "newPassword", "Hacked#2026pw")));
        // 農場主、工人不能重設
        String farmer = login("farmer", "Farmer#2026pw");
        assertEquals(403, status("POST", "/api/auth/reset-password", farmer, Map.of("identifier", "W006", "newPassword", "Worker#2026pw2")));
        // 管理員仍可重設合作社帳號
        assertEquals(200, status("POST", "/api/auth/reset-password", admin, Map.of("identifier", "coop_test", "newPassword", COOP_PW)));
        assertEquals(200, status("GET", "/api/auth/me", login("coop_test", COOP_PW), null));
    }

    @Test
    void accountListHidesBackOfficeAccountsFromCoop() throws Exception {
        JsonNode forCoop = json(call("GET", "/api/auth/accounts", coop, null)).get("data");
        JsonNode forAdmin = json(call("GET", "/api/auth/accounts", admin, null)).get("data");
        boolean adminSeesBackOffice = false;
        for (JsonNode a : forAdmin) if (a.get("role").asText().equals("admin_tech") || a.get("role").asText().equals("coop_admin")) adminSeesBackOffice = true;
        assertTrue(adminSeesBackOffice);
        for (JsonNode a : forCoop) {
            assertNotEquals("admin_tech", a.get("role").asText());
            assertNotEquals("coop_admin", a.get("role").asText());
        }
        assertTrue(forCoop.size() > 0);
    }

    @Test
    void leaveApprovalAndWorkerIdMasking() throws Exception {
        assertEquals(200, status("POST", "/api/auth/reset-password", admin, Map.of("identifier", "W033", "newPassword", "Worker#2026pw")));
        String w33 = login("W033", "Worker#2026pw");
        LocalDate d = LocalDate.now().plusDays(45);
        MvcResult r = call("POST", "/api/workers/W033/leaves", w33, Map.of("leaveType", "personal", "startDate", d.toString()));
        assertEquals(201, r.getResponse().getStatus());
        String id = json(r).get("data").get("id").asText();
        assertEquals(200, status("POST", "/api/leaves/" + id + "/approve", coop, Map.of("note", "合作社核准")));
        assertEquals(403, status("POST", "/api/leaves/" + id + "/approve", w33, Map.of()));

        // 證件號碼：科技後台取得完整值，合作社維持遮罩
        JsonNode wAdmin = json(call("GET", "/api/workers/W001", admin, null)).get("data");
        JsonNode wCoop = json(call("GET", "/api/workers/W001", coop, null)).get("data");
        if (wAdmin.hasNonNull("arcNumber")) {
            assertNotEquals(wAdmin.get("arcNumber").asText(), wCoop.get("arcNumber").asText(), "合作社看到的證號必須遮罩");
        }
    }
}
