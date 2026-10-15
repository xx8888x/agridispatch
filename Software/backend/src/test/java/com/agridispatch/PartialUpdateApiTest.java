package com.agridispatch;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 管理端 PUT 的部分更新回歸測試：沒帶（或為 null）的欄位維持原值、基本型別不被清成 0/false、
 * 必填名稱明確傳空字串回 400、body 內的 id 不可改主鍵；送完整物件（admin.html）行為不變。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:agri_partial_update_test;DB_CLOSE_DELAY=-1",
        "agri.demo.enabled=false",
        "agri.security.token-secret=unit-test-token-secret-0123456789abcdef",
        "agri.security.admin-password=Admin#Pass2026"
})
@AutoConfigureMockMvc
class PartialUpdateApiTest extends ApiTestSupport {

    private String admin;

    @BeforeEach
    void setUp() throws Exception {
        admin = login("admin_tech", "Admin#Pass2026");
    }

    private JsonNode put(String url, Object body, int expectStatus) throws Exception {
        var r = call("PUT", url, admin, body);
        assertEquals(expectStatus, r.getResponse().getStatus(), r.getResponse().getContentAsString());
        return json(r);
    }

    private JsonNode getData(String url) throws Exception {
        return json(call("GET", url, admin, null)).get("data");
    }

    @Test
    void cropPartialUpdateKeepsOtherFields() throws Exception {
        JsonNode before = getData("/api/crops/mango");
        JsonNode after = put("/api/crops/mango", Map.of("rate", 333), 200).get("data");
        assertEquals(333, after.get("rate").asInt());
        for (String f : new String[]{"name", "season", "taskDescription", "indoorAlternative"}) {
            assertEquals(before.get(f), after.get(f), f);
        }
        assertEquals(before.get("baseHoursPerFen").asInt(), after.get("baseHoursPerFen").asInt());
        assertEquals(before.get("workersPerFen").asInt(), after.get("workersPerFen").asInt());

        // 明確 null 視為沒帶
        Map<String, Object> withNull = new HashMap<>();
        withNull.put("season", null);
        assertEquals(before.get("season"), put("/api/crops/mango", withNull, 200).get("data").get("season"));

        // 名稱明確傳空字串 → 400；數值不合法 → 400；兩者都不寫入
        put("/api/crops/mango", Map.of("name", ""), 400);
        put("/api/crops/mango", Map.of("baseHoursPerFen", 0), 400);
        put("/api/crops/mango", Map.of("rate", "abc"), 400);
        JsonNode now = getData("/api/crops/mango");
        assertEquals(before.get("name"), now.get("name"));
        assertEquals(before.get("baseHoursPerFen").asInt(), now.get("baseHoursPerFen").asInt());

        // body 內的 id 不改主鍵
        put("/api/crops/mango", Map.of("id", "hijack", "season", "4月 ~ 8月"), 200);
        assertEquals(404, call("GET", "/api/crops/hijack", null, null).getResponse().getStatus());
        assertEquals("4月 ~ 8月", getData("/api/crops/mango").get("season").asText());

        // 完整物件（前端行為）照常更新所有欄位
        Map<String, Object> full = om.convertValue(getData("/api/crops/mango"), LinkedHashMap.class);
        full.put("taskDescription", "完整物件更新");
        full.put("rate", 310);
        JsonNode fullAfter = put("/api/crops/mango", full, 200).get("data");
        assertEquals("完整物件更新", fullAfter.get("taskDescription").asText());
        assertEquals(310, fullAfter.get("rate").asInt());
    }

    @Test
    void teamPartialUpdateKeepsOtherFields() throws Exception {
        JsonNode before = getData("/api/dispatch/teams/team_a");
        JsonNode after = put("/api/dispatch/teams/team_a", Map.of("leaderPhone", "0900-123-456"), 200).get("data");
        assertEquals("0900-123-456", after.get("leaderPhone").asText());
        for (String f : new String[]{"name", "leader", "vehicle", "driver", "normalLocation", "normalTask",
                "hedgeLocation", "hedgeTask", "status"}) {
            assertEquals(before.get(f), after.get(f), f);
        }
        assertEquals(before.get("workerCount").asInt(), after.get("workerCount").asInt(), "int 欄位沒帶不可被清成 0");

        put("/api/dispatch/teams/team_a", Map.of("name", "   "), 400);
        put("/api/dispatch/teams/team_a", Map.of("workerCount", -1), 400);
        assertEquals(before.get("name"), getData("/api/dispatch/teams/team_a").get("name"));
        assertEquals(404, call("PUT", "/api/dispatch/teams/nope", admin, Map.of("name", "x")).getResponse().getStatus());
    }

    @Test
    void talentPartialUpdateKeepsOtherFields() throws Exception {
        JsonNode before = getData("/api/talents/TAL-001");
        JsonNode after = put("/api/talents/TAL-001", Map.of("experience", "曳引機 10 年"), 200).get("data");
        assertEquals("曳引機 10 年", after.get("experience").asText());
        for (String f : new String[]{"name", "category", "categoryName", "phone", "pdpaConsent", "registeredAt"}) {
            assertEquals(before.get(f), after.get(f), f);
        }
        put("/api/talents/TAL-001", Map.of("name", ""), 400);
        put("/api/talents/TAL-001", Map.of("phone", "0".repeat(31)), 400);
        put("/api/talents/TAL-001", Map.of("registeredAt", "1999-01-01 00:00:00"), 200);
        JsonNode now = getData("/api/talents/TAL-001");
        assertEquals(before.get("name"), now.get("name"));
        assertEquals(before.get("phone"), now.get("phone"));
        assertEquals(before.get("registeredAt"), now.get("registeredAt"), "登錄時間不可由 PUT 修改");
    }

    @Test
    void brochurePartialUpdateKeepsOtherFields() throws Exception {
        JsonNode list = json(call("GET", "/api/brochures?all=true", admin, null)).get("data");
        String id = list.get(0).get("id").asText();
        JsonNode before = list.get(0);

        JsonNode after = put("/api/brochures/" + id, Map.of("badge", "限時"), 200).get("data");
        assertEquals("限時", after.get("badge").asText());
        for (String f : new String[]{"title", "subtitle", "crop", "region", "status", "createdAt", "description"}) {
            assertEquals(before.get(f), after.get(f), f);
        }
        for (String f : new String[]{"originalPrice", "discountPrice", "depositRate", "depositAmount", "hours", "workers", "days"}) {
            assertEquals(before.get(f).asInt(), after.get(f).asInt(), f);
        }
        put("/api/brochures/" + id, Map.of("title", ""), 400);
        assertEquals(before.get("title"), json(call("GET", "/api/brochures?all=true", admin, null)).get("data").get(0).get("title"));
        assertEquals(404, call("PUT", "/api/brochures/nope", admin, Map.of("title", "x")).getResponse().getStatus());
    }
}
