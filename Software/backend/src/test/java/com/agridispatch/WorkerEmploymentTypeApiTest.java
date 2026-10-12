package com.agridispatch;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 本國工人「工作型態」（employmentType：正職／兼職／中高齡／臨時打工／學生打工）
 * 種子：W031～W040 為各型態的本國示範工人；既有本國工人一併補上型態，外籍為 null
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:agri_employment_type_test;DB_CLOSE_DELAY=-1",
        "agri.demo.enabled=false",
        "agri.security.token-secret=unit-test-token-secret-0123456789abcdef",
        "agri.security.admin-password=Admin#Pass2026"
})
@AutoConfigureMockMvc
class WorkerEmploymentTypeApiTest extends ApiTestSupport {

    private String admin;

    @BeforeEach
    void setUp() throws Exception {
        admin = login("admin_tech", "Admin#Pass2026");
    }

    @Test
    void seededLocalWorkersCoverAllEmploymentTypes() throws Exception {
        JsonNode list = json(call("GET", "/api/workers", admin, null)).get("data");
        Set<String> types = new HashSet<>();
        for (JsonNode w : list) {
            JsonNode t = w.get("employmentType");
            if ("tier1_foreign".equals(w.get("tier").asText())) {
                assertTrue(t == null || t.isNull(), "外籍不設工作型態：" + w.get("id").asText());
            } else {
                assertTrue(t != null && !t.isNull(), "本國工人應有工作型態：" + w.get("id").asText());
                types.add(t.asText());
            }
        }
        assertEquals(Set.of("full_time", "part_time", "senior", "casual", "student"), types);
        assertEquals("senior", json(call("GET", "/api/workers/W031", admin, null)).get("data").get("employmentType").asText());
        assertEquals("student", json(call("GET", "/api/workers/W036", admin, null)).get("data").get("employmentType").asText());
    }

    @Test
    void adminCanChangeEmploymentTypeAndInvalidCodeIsRejected() throws Exception {
        JsonNode updated = json(call("PUT", "/api/workers/W021", admin, Map.of("employmentType", "senior"))).get("data");
        assertEquals("senior", updated.get("employmentType").asText());

        assertEquals(400, call("PUT", "/api/workers/W021", admin, Map.of("employmentType", "intern")).getResponse().getStatus());

        Map<String, Object> clear = new HashMap<>();
        clear.put("employmentType", "");
        assertTrue(json(call("PUT", "/api/workers/W021", admin, clear)).get("data").get("employmentType").isNull(), "空字串視為未設定");
        // 還原種子值（另一個測試檢查全部本國工人都有型態，執行順序不固定）
        assertEquals(200, call("PUT", "/api/workers/W021", admin, Map.of("employmentType", "part_time")).getResponse().getStatus());

        assertEquals(400, call("POST", "/api/workers", admin,
                Map.of("name", "測試工", "tier", "tier2_local_flex", "teamId", "team_a", "employmentType", "bad")).getResponse().getStatus());
    }
}
