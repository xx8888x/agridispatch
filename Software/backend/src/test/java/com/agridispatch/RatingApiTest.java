package com.agridispatch;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 工班評價 API（production 模式，驗證擁有者權限）
 * 種子：ORD-2026-013（F001、in_progress、team_b、尚無評價）、ORD-2026-014（F001、pending）、
 *       ORD-2026-002（非 F001、in_progress、已有評價）
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:agri_rating_test;DB_CLOSE_DELAY=-1",
        "agri.demo.enabled=false",
        "agri.security.token-secret=unit-test-token-secret-0123456789abcdef",
        "agri.security.admin-password=Admin#Pass2026"
})
@AutoConfigureMockMvc
class RatingApiTest extends ApiTestSupport {

    private String adminToken;

    @BeforeEach
    void setUp() throws Exception {
        adminToken = login("admin_tech", "Admin#Pass2026");
    }

    private String farmerToken() throws Exception {
        MvcResult r = call("POST", "/api/auth/reset-password", adminToken,
                Map.of("identifier", "farmer", "newPassword", "Farmer#2026pw"));
        assertEquals(200, r.getResponse().getStatus());
        return login("farmer", "Farmer#2026pw");
    }

    private int status(String method, String url, String token, Object body) throws Exception {
        return call(method, url, token, body).getResponse().getStatus();
    }

    @Test
    void ownerCanRateAndUpdateOwnOrder() throws Exception {
        String farmer = farmerToken();
        MvcResult r = call("PUT", "/api/ratings/orders/ORD-2026-013", farmer,
                Map.of("score", 5, "tags", List.of("準時到場", "做工細心"), "comment", "芒果採收很仔細"));
        assertEquals(200, r.getResponse().getStatus());
        JsonNode d = json(r).get("data");
        assertEquals("RAT-ORD-2026-013", d.get("id").asText());
        assertEquals("team_b", d.get("teamId").asText());
        assertEquals(5, d.get("score").asInt());
        assertEquals(2, d.get("tags").size());
        assertFalse(d.has("farmerId"), "不回傳評分者編號");

        // 同一張訂單再次送出 → 修改而非新增
        call("PUT", "/api/ratings/orders/ORD-2026-013", farmer, Map.of("score", 4, "tags", List.of()));
        JsonNode got = json(call("GET", "/api/ratings/orders/ORD-2026-013", farmer, null)).get("data");
        assertEquals(4, got.get("score").asInt());
        assertEquals(0, got.get("tags").size());

        JsonNode teamB = json(call("GET", "/api/ratings/teams/team_b", farmer, null)).get("data");
        assertTrue(teamB.get("count").asInt() >= 2, "含種子的 ORD-2026-002 與剛評的 013");
        assertFalse(teamB.get("recent").get(0).has("orderId"), "非 admin 不回傳訂單編號");
    }

    @Test
    void cannotRateBeforeWorkStartsOrOthersOrders() throws Exception {
        String farmer = farmerToken();
        assertEquals(409, status("PUT", "/api/ratings/orders/ORD-2026-014", farmer, Map.of("score", 5)), "待付款未出工");
        assertEquals(403, status("PUT", "/api/ratings/orders/ORD-2026-002", farmer, Map.of("score", 1)), "他人訂單");
        assertEquals(403, status("GET", "/api/ratings/orders/ORD-2026-002", farmer, null));
        assertEquals(404, status("PUT", "/api/ratings/orders/ORD-9999-999", farmer, Map.of("score", 3)));
        assertEquals(401, status("PUT", "/api/ratings/orders/ORD-2026-013", null, Map.of("score", 5)));
        assertEquals(401, status("GET", "/api/ratings/teams", null, null));
    }

    @Test
    void invalidInputRejected() throws Exception {
        String farmer = farmerToken();
        String url = "/api/ratings/orders/ORD-2026-013";
        assertEquals(400, status("PUT", url, farmer, Map.of("score", 6)));
        assertEquals(400, status("PUT", url, farmer, Map.of("score", 0)));
        assertEquals(400, status("PUT", url, farmer, Map.of("score", 4.5)));
        assertEquals(400, status("PUT", url, farmer, Map.of("score", "abc")));
        assertEquals(400, status("PUT", url, farmer, Map.of()), "score 必填");
        assertEquals(400, status("PUT", url, farmer, Map.of("score", 5, "tags", List.of("亂寫的標籤"))));
        assertEquals(400, status("PUT", url, farmer, Map.of("score", 5, "tags", "準時到場")), "tags 必須為陣列");
        assertEquals(400, status("PUT", url, farmer, Map.of("score", 5, "comment", "字".repeat(301))));
    }

    @Test
    void adminSeesTeamSummariesWithOrderIds() throws Exception {
        JsonNode teams = json(call("GET", "/api/ratings/teams", adminToken, null)).get("data");
        assertTrue(teams.size() >= 4);
        JsonNode teamC = null;
        for (JsonNode t : teams) if ("team_c".equals(t.get("teamId").asText())) teamC = t;
        assertNotNull(teamC);
        assertEquals(2, teamC.get("count").asInt(), "種子：ORD-2026-006、012");
        assertEquals(4.5, teamC.get("average").asDouble(), 0.001);

        JsonNode detail = json(call("GET", "/api/ratings/teams/team_c", adminToken, null)).get("data");
        assertTrue(detail.get("recent").get(0).has("orderId"));
        assertEquals(404, status("GET", "/api/ratings/teams/team_x", adminToken, null));
        assertEquals(6, json(call("GET", "/api/ratings/tags", adminToken, null)).get("data").size());
    }
}
