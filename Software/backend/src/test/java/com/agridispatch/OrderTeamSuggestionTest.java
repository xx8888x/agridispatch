package com.agridispatch;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 訂單建議工班取自實際存在的工班（與媒合頁同一套排序），不再寫死 team_a～team_d：
 * 空資料庫（正式環境）從零建工班，驗證「沒有工班＝待分派」「合格工班優先於類別慣用代號」「慣用代號只在同分時偏好」。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:agri_order_team_suggestion_test;DB_CLOSE_DELAY=-1",
        "agri.demo.enabled=false",
        "agri.seed.demo-data=false",
        "agri.security.token-secret=unit-test-token-secret-0123456789abcdef",
        "agri.security.admin-password=Admin#Pass2026"
})
@AutoConfigureMockMvc
class OrderTeamSuggestionTest extends ApiTestSupport {

    private String admin;

    @BeforeEach
    void setUp() throws Exception {
        admin = login("admin_tech", "Admin#Pass2026");
    }

    private MvcResult expect(int status, MvcResult r) throws Exception {
        assertEquals(status, r.getResponse().getStatus(), r.getResponse().getContentAsString());
        return r;
    }

    private JsonNode calc(String category) throws Exception {
        return json(expect(200, call("POST", "/api/orders/calculate", null, Map.of(
                "cropId", "suggest_crop", "area", 1.0, "plan", "hourly_flex", "dispatchCategory", category, "workers", 2))))
                .get("data");
    }

    private JsonNode createOrder(String category) throws Exception {
        return json(expect(201, call("POST", "/api/orders", admin, Map.of("farmerName", "建議工班測試", "cropId", "suggest_crop",
                "area", 1.0, "plan", "hourly_flex", "dispatchCategory", category, "requestedWorkers", 2)))).get("data");
    }

    private void team(String id, String name) throws Exception {
        expect(201, call("POST", "/api/dispatch/teams", admin, Map.of("id", id, "name", name, "leader", "領班", "leaderPhone", "0912")));
    }

    /** 本國工人＋四種應加保險種，讓工班成為合格工班 */
    private void insuredWorker(String id, String teamId) throws Exception {
        expect(201, call("POST", "/api/workers", admin, Map.of("id", id, "name", "工人" + id, "tier", "tier2_local_flex",
                "nationality", "本國", "teamId", teamId, "phone", "0912-000-" + id.substring(1))));
        for (String type : new String[]{"labor", "employment", "occupational", "health"}) {
            Map<String, Object> body = new HashMap<>(Map.of("type", type, "insuredSalary", 29500,
                    "enrollDate", LocalDate.now().minusDays(1).toString()));
            expect(200, call("POST", "/api/insurance/workers/" + id + "/enroll", admin, body));
        }
    }

    @Test
    void suggestionComesFromRealTeams() throws Exception {
        expect(201, call("POST", "/api/crops", admin, Map.of("id", "suggest_crop", "name", "測試作物",
                "baseHoursPerFen", 8, "workersPerFen", 2, "rate", 300)));

        // 1. 還沒有任何工班：待分派
        JsonNode none = calc("cold_chain_pack");
        assertTrue(none.get("suggestedTeam").isNull(), none.toString());
        assertTrue(none.get("suggestedTeamName").asText().contains("待分派"));
        assertTrue(createOrder("cold_chain_pack").get("assignedTeamId").isNull(), "沒有工班時訂單為待分派");

        // 2. 兩個工班都沒有人手（同分）：類別慣用代號 team_d 只在同分時優先；其他類別依代號
        team("team_d", "冷鏈組");
        team("T-north", "北區工班");
        assertEquals("team_d", calc("cold_chain_pack").get("suggestedTeam").asText());
        JsonNode harvest = calc("harvest_grading");
        assertEquals("T-north", harvest.get("suggestedTeam").asText(), "慣用的 team_a 不存在：改依實際工班排序");
        assertEquals("北區工班", harvest.get("suggestedTeamName").asText());

        // 3. 北區工班有 2 位完整加保的工人＝合格：勝過慣用代號 team_d
        insuredWorker("W801", "T-north");
        insuredWorker("W802", "T-north");
        assertEquals("T-north", calc("cold_chain_pack").get("suggestedTeam").asText(), "合格工班優先於類別慣用工班");
        assertEquals("T-north", createOrder("cold_chain_pack").get("assignedTeamId").asText(), "建立訂單也用同一套排序");

        // 4. 類別清單不再帶寫死的建議工班
        JsonNode categories = json(expect(200, call("GET", "/api/orders/categories", null, null))).get("data");
        for (JsonNode c : categories) assertFalse(c.has("suggestedTeam"), c.toString());
    }
}
