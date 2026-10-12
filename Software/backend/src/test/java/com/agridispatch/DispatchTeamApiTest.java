package com.agridispatch;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 工班管理：admin 新增／刪除工班（正式庫沒有工班就建不了工人），
 * 非 admin 讀工班只拿到公開欄位（不含領班電話、司機、車輛）。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:agri_dispatch_team_test;DB_CLOSE_DELAY=-1",
        "agri.demo.enabled=false",
        "agri.security.token-secret=unit-test-token-secret-0123456789abcdef",
        "agri.security.admin-password=Admin#Pass2026"
})
@AutoConfigureMockMvc
class DispatchTeamApiTest extends ApiTestSupport {

    private String admin;

    @BeforeEach
    void setUp() throws Exception {
        admin = login("admin_tech", "Admin#Pass2026");
    }

    private String farmerToken() throws Exception {
        call("POST", "/api/auth/reset-password", admin, Map.of("identifier", "farmer", "newPassword", "Farmer#2026pw"));
        return login("farmer", "Farmer#2026pw");
    }

    private MvcResult expect(int status, MvcResult r) throws Exception {
        assertEquals(status, r.getResponse().getStatus(), r.getResponse().getContentAsString());
        return r;
    }

    @Test
    void adminCreatesTeamThenWorkerCanJoinIt() throws Exception {
        JsonNode team = json(expect(201, call("POST", "/api/dispatch/teams", admin,
                Map.of("name", "新竹芎林工班", "leader", "王領班", "leaderPhone", "0912-000-111")))).get("data");
        String id = team.get("id").asText();
        assertTrue(id.matches("team_\\d{2,}"), "未給代號時自動編號：" + id);
        assertEquals("active_field", team.get("status").asText());
        assertEquals("待安排", team.get("vehicle").asText(), "Oracle NOT NULL 欄位未填存「待安排」");
        assertEquals("待安排", team.get("hedgeTask").asText());

        // 新工班可以掛工人
        expect(201, call("POST", "/api/workers", admin, Map.of("name", "新工班組員", "tier", "tier2_local_flex",
                "teamId", id, "phone", "0912-000-222")));

        // 有工人時不能刪
        expect(409, call("DELETE", "/api/dispatch/teams/" + id, admin, null));
    }

    @Test
    void createValidatesInput() throws Exception {
        expect(400, call("POST", "/api/dispatch/teams", admin, Map.of("leader", "王領班", "leaderPhone", "0912")));
        expect(400, call("POST", "/api/dispatch/teams", admin, Map.of("name", "X", "leaderPhone", "0912")));
        expect(400, call("POST", "/api/dispatch/teams", admin,
                Map.of("name", "X", "leader", "Y", "leaderPhone", "0912", "status", "flying")));
        expect(400, call("POST", "/api/dispatch/teams", admin,
                Map.of("id", "bad id!", "name", "X", "leader", "Y", "leaderPhone", "0912")));

        expect(201, call("POST", "/api/dispatch/teams", admin,
                Map.of("id", "team_custom", "name", "X", "leader", "Y", "leaderPhone", "0912")));
        expect(409, call("POST", "/api/dispatch/teams", admin,
                Map.of("id", "team_custom", "name", "X", "leader", "Y", "leaderPhone", "0912")));
    }

    @Test
    void deleteEmptyTeamAndRejectMissing() throws Exception {
        String id = json(expect(201, call("POST", "/api/dispatch/teams", admin,
                Map.of("name", "暫時工班", "leader", "李領班", "leaderPhone", "0912-333-444")))).get("data").get("id").asText();
        expect(200, call("DELETE", "/api/dispatch/teams/" + id, admin, null));
        expect(404, call("GET", "/api/dispatch/teams/" + id, admin, null));
        expect(404, call("DELETE", "/api/dispatch/teams/" + id, admin, null));
    }

    @Test
    void teamWithOrdersCannotBeDeleted() throws Exception {
        String id = json(expect(201, call("POST", "/api/dispatch/teams", admin,
                Map.of("name", "有單工班", "leader", "陳領班", "leaderPhone", "0912-555-666")))).get("data").get("id").asText();
        expect(201, call("POST", "/api/orders", admin, Map.of("farmerName", "測試農場", "cropId", "mango",
                "area", 1.0, "plan", "hourly_flex", "assignedTeamId", id)));
        JsonNode err = json(expect(409, call("DELETE", "/api/dispatch/teams/" + id, admin, null)));
        assertTrue(err.get("error").asText().contains("1 張訂單"), err.toString());
    }

    @Test
    void onlyAdminCanCreateOrDelete() throws Exception {
        String farmer = farmerToken();
        expect(403, call("POST", "/api/dispatch/teams", farmer,
                Map.of("name", "X", "leader", "Y", "leaderPhone", "0912")));
        expect(403, call("DELETE", "/api/dispatch/teams/team_a", farmer, null));
        expect(401, call("POST", "/api/dispatch/teams", null,
                Map.of("name", "X", "leader", "Y", "leaderPhone", "0912")));
    }

    @Test
    void nonAdminSeesOnlyPublicTeamFields() throws Exception {
        String farmer = farmerToken();
        JsonNode list = json(expect(200, call("GET", "/api/dispatch/teams", farmer, null))).get("data");
        assertTrue(list.size() > 0);
        for (JsonNode t : list) {
            assertTrue(t.hasNonNull("name"));
            for (String f : new String[]{"leader", "leaderPhone", "driver", "vehicle"}) {
                assertFalse(t.has(f), "農場主不應看到 " + f);
            }
        }
        String id = list.get(0).get("id").asText();
        JsonNode one = json(expect(200, call("GET", "/api/dispatch/teams/" + id, farmer, null))).get("data");
        assertFalse(one.has("leaderPhone"));

        JsonNode full = json(expect(200, call("GET", "/api/dispatch/teams/" + id, admin, null))).get("data");
        assertTrue(full.hasNonNull("leaderPhone"), "admin 看得到完整工班資料");
        expect(401, call("GET", "/api/dispatch/teams", null, null));
    }
}
