package com.agridispatch;

import com.agridispatch.entity.Order;
import com.agridispatch.repository.OrderRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 工人本人排班 GET /api/workers/{id}/schedule（production 模式）
 * 種子：W006、W008、W026 同屬工班 B；ORD-2026-005 工期 10/04～10/07
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:agri_worker_schedule_test;DB_CLOSE_DELAY=-1",
        "agri.demo.enabled=false",
        "agri.security.token-secret=unit-test-token-secret-0123456789abcdef",
        "agri.security.admin-password=Admin#Pass2026"
})
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class WorkerScheduleApiTest extends ApiTestSupport {

    @Autowired OrderRepository orderRepository;

    private String admin;

    @BeforeEach
    void setUp() throws Exception {
        admin = login("admin_tech", "Admin#Pass2026");
    }

    private String workerToken(String id) throws Exception {
        assertEquals(200, call("POST", "/api/auth/reset-password", admin,
                Map.of("identifier", id, "newPassword", "Worker#2026pw")).getResponse().getStatus());
        return login(id, "Worker#2026pw");
    }

    private JsonNode schedule(String id, String token, String query) throws Exception {
        var r = call("GET", "/api/workers/" + id + "/schedule" + query, token, null);
        assertEquals(200, r.getResponse().getStatus());
        return json(r).get("data");
    }

    private static Set<String> ids(JsonNode data) {
        Set<String> s = new LinkedHashSet<>();
        for (JsonNode i : data.get("items")) s.add(i.get("orderId").asText());
        return s;
    }

    @Test
    @org.junit.jupiter.api.Order(1)
    void teamOrdersOnlyAndNoPricingFields() throws Exception {
        JsonNode data = schedule("W006", workerToken("W006"), "");
        assertEquals("team_b", data.get("teamId").asText());
        assertEquals("team_b", data.get("team").get("id").asText());

        // 預期：指派給工班 B、沒有出工名單、未取消且已排工期的訂單
        Set<String> expected = new TreeSet<>();
        for (Order o : orderRepository.findAll()) {
            boolean noCrew = o.getCrewWorkerIds() == null || o.getCrewWorkerIds().isBlank();
            if ("team_b".equals(o.getAssignedTeamId()) && noCrew && !"cancelled".equals(o.getStatus()) && o.getStartDate() != null) {
                expected.add(o.getId());
            }
        }
        assertFalse(expected.isEmpty(), "種子資料工班 B 應有排定工期的訂單");
        assertEquals(expected, new TreeSet<>(ids(data)));

        String prev = "";
        for (JsonNode i : data.get("items")) {
            assertEquals("team", i.get("assignedVia").asText());
            assertTrue(i.get("startDate").asText().compareTo(prev) >= 0, "依開工日排序");
            prev = i.get("startDate").asText();
            for (String hidden : List.of("finalAmount", "depositAmount", "standardAmount", "unitRate", "phone", "farmerId", "plan"))
                assertFalse(i.has(hidden), "不應回傳 " + hidden);
        }
    }

    @Test
    @org.junit.jupiter.api.Order(2)
    void onlySelfOrAdmin() throws Exception {
        String w006 = workerToken("W006");
        assertEquals(403, call("GET", "/api/workers/W008/schedule", w006, null).getResponse().getStatus());
        assertEquals(401, call("GET", "/api/workers/W006/schedule", null, null).getResponse().getStatus());
        assertEquals(200, call("GET", "/api/workers/W008/schedule", admin, null).getResponse().getStatus());
        assertEquals(404, call("GET", "/api/workers/W999/schedule", admin, null).getResponse().getStatus());
    }

    @Test
    @org.junit.jupiter.api.Order(3)
    void crewListDecidesWhoIsScheduled() throws Exception {
        Order o = orderRepository.findById("ORD-2026-005").orElseThrow();
        o.setAssignedTeamId("team_b");
        o.setCrewWorkerIds("W026,W011");
        orderRepository.save(o);

        JsonNode mine = schedule("W026", workerToken("W026"), "");
        assertTrue(ids(mine).contains("ORD-2026-005"));
        for (JsonNode i : mine.get("items"))
            if ("ORD-2026-005".equals(i.get("orderId").asText())) assertEquals("crew", i.get("assignedVia").asText());

        // 同工班但不在出工名單：看不到
        assertFalse(ids(schedule("W008", workerToken("W008"), "")).contains("ORD-2026-005"));
    }

    @Test
    @org.junit.jupiter.api.Order(4)
    void dateRangeFilterAndValidation() throws Exception {
        String t = workerToken("W026");
        // 10/05 一天：與 005（10/04～10/07）重疊
        assertTrue(ids(schedule("W026", t, "?from=2026-10-05&to=2026-10-05")).contains("ORD-2026-005"));
        assertFalse(ids(schedule("W026", t, "?from=2026-10-08&to=2026-10-31")).contains("ORD-2026-005"));
        JsonNode ranged = schedule("W026", t, "?from=2026-10-01&to=2026-10-31");
        for (JsonNode i : ranged.get("items")) {
            assertTrue(i.get("endDate").asText().compareTo("2026-10-01") >= 0);
            assertTrue(i.get("startDate").asText().compareTo("2026-10-31") <= 0);
        }
        assertEquals(400, call("GET", "/api/workers/W026/schedule?from=2026-13-01", t, null).getResponse().getStatus());
        assertEquals(400, call("GET", "/api/workers/W026/schedule?from=2026-10-10&to=2026-10-01", t, null).getResponse().getStatus());
        assertEquals(400, call("GET", "/api/workers/W026/schedule?from=2026-01-01&to=2027-06-01", t, null).getResponse().getStatus());
    }
}
