package com.agridispatch;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 保險登錄 API（production 模式）
 * 種子：所有工人自 2026-03-01 起依身分加保（本國 4 種、外籍 3 種）；
 *       ORD-2026-009 為 confirmed、team_d（成員 W016～W020）
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:agri_insurance_test;DB_CLOSE_DELAY=-1",
        "agri.demo.enabled=false",
        "agri.security.token-secret=unit-test-token-secret-0123456789abcdef",
        "agri.security.admin-password=Admin#Pass2026"
})
@AutoConfigureMockMvc
class InsuranceApiTest extends ApiTestSupport {

    private String admin;

    @BeforeEach
    void setUp() throws Exception {
        admin = login("admin_tech", "Admin#Pass2026");
    }

    private String tokenFor(String username) throws Exception {
        MvcResult r = call("POST", "/api/auth/reset-password", admin,
                Map.of("identifier", username, "newPassword", "Test#2026pw"));
        assertEquals(200, r.getResponse().getStatus(), r.getResponse().getContentAsString(StandardCharsets.UTF_8));
        return login(username, "Test#2026pw");
    }

    private int status(String method, String url, String token, Object body) throws Exception {
        return call(method, url, token, body).getResponse().getStatus();
    }

    @Test
    void seededWorkersAreFullyInsuredByNationality() throws Exception {
        JsonNode list = json(call("GET", "/api/insurance/workers", admin, null)).get("data");
        assertTrue(list.size() >= 27, "種子 27 位工人");
        for (JsonNode w : list) assertTrue(w.get("fullyInsured").asBoolean(), w.get("workerId").asText());

        JsonNode foreign = json(call("GET", "/api/insurance/workers/W001", admin, null)).get("data");
        assertEquals(3, foreign.get("required").size(), "外籍：勞保／職保／健保");
        assertFalse(foreign.get("required").toString().contains("employment"));
        JsonNode local = json(call("GET", "/api/insurance/workers/W012", admin, null)).get("data");
        assertEquals(4, local.get("required").size(), "本國：含就保");
        assertTrue(local.get("records").get(0).get("insuredSalary").asInt() >= 29_500);
    }

    @Test
    void enrollValidation() throws Exception {
        String url = "/api/insurance/workers/W001/enroll";
        assertEquals(400, status("POST", url, admin, Map.of("type", "employment", "insuredSalary", 29500, "enrollDate", "2026-10-01")),
                "外籍不適用就保");
        assertEquals(409, status("POST", url, admin, Map.of("type", "labor", "insuredSalary", 29500, "enrollDate", "2026-10-01")),
                "已在保不可重複加保");
        assertEquals(400, status("POST", url, admin, Map.of("type", "dental", "insuredSalary", 29500, "enrollDate", "2026-10-01")));
        assertEquals(400, status("POST", url, admin, Map.of("type", "labor", "insuredSalary", 0, "enrollDate", "2026-10-01")));
        assertEquals(400, status("POST", url, admin, Map.of("type", "labor", "insuredSalary", 29500, "enrollDate", "2026/10/01")));
        assertEquals(404, status("POST", "/api/insurance/workers/W999/enroll", admin,
                Map.of("type", "labor", "insuredSalary", 29500, "enrollDate", "2026-10-01")));
    }

    @Test
    void withdrawBlocksDispatchUntilReEnrolled() throws Exception {
        LocalDate today = LocalDate.now();
        // W016（范氏紅，team_d）健保退保 → ORD-2026-009（team_d）不可轉為 in_progress
        String recordId = "INS-W016-health-20260301";
        assertEquals(400, status("POST", "/api/insurance/records/" + recordId + "/withdraw", admin,
                Map.of("withdrawDate", "2026-02-01")), "退保日早於加保日");
        assertEquals(200, status("POST", "/api/insurance/records/" + recordId + "/withdraw", admin,
                Map.of("withdrawDate", today.toString(), "reason", "測試退保")));
        assertEquals(409, status("POST", "/api/insurance/records/" + recordId + "/withdraw", admin,
                Map.of("withdrawDate", today.toString())), "不可重複退保");

        JsonNode w = json(call("GET", "/api/insurance/workers/W016", admin, null)).get("data");
        assertFalse(w.get("fullyInsured").asBoolean());
        JsonNode team = json(call("GET", "/api/insurance/teams/team_d", admin, null)).get("data");
        assertFalse(team.get("allInsured").asBoolean());
        assertEquals("W016", team.get("gaps").get(0).get("workerId").asText());

        MvcResult blocked = call("PUT", "/api/orders/ORD-2026-009/status", admin, Map.of("status", "in_progress"));
        assertEquals(409, blocked.getResponse().getStatus());
        assertTrue(json(blocked).get("error").asText().contains("范氏紅"));

        // 補登健保（低於月薪會有警告但仍成功）→ 可出工
        MvcResult enrolled = call("POST", "/api/insurance/workers/W016/enroll", admin,
                Map.of("type", "health", "insuredSalary", 27000, "enrollDate", today.toString(), "filingRef", "NHI-TEST-001"));
        assertEquals(200, enrolled.getResponse().getStatus());
        assertTrue(json(enrolled).get("warnings").size() >= 1);
        assertEquals(200, status("PUT", "/api/orders/ORD-2026-009/status", admin, Map.of("status", "in_progress")));
    }

    @Test
    void permissions() throws Exception {
        String farmer = tokenFor("farmer");
        assertEquals(403, status("GET", "/api/insurance/workers", farmer, null));
        assertEquals(403, status("GET", "/api/insurance/workers/W001", farmer, null));
        assertEquals(403, status("POST", "/api/insurance/workers/W001/enroll", farmer,
                Map.of("type", "labor", "insuredSalary", 29500, "enrollDate", "2026-10-01")));
        JsonNode team = json(call("GET", "/api/insurance/teams/team_b", farmer, null)).get("data");
        assertTrue(team.has("allInsured"));
        assertFalse(team.has("gaps"), "農場主看不到缺保名單");

        String worker = tokenFor("worker_labor"); // W030
        assertEquals(200, status("GET", "/api/insurance/workers/W030", worker, null), "本人可查");
        assertEquals(403, status("GET", "/api/insurance/workers/W001", worker, null), "不可查他人");
        assertEquals(401, status("GET", "/api/insurance/teams/team_b", null, null));
    }
}
