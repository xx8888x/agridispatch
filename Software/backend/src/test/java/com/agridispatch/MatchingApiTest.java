package com.agridispatch;

import com.agridispatch.entity.Attendance;
import com.agridispatch.repository.AttendanceRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 派工媒合（production 模式）
 * 種子：ORD-2026-005（防颱搶收＝採收類、待付定金、需 6 人）；工班 B 有採收熟手 W011、W026（W026 為領班），
 *       工班 C、D 各 5 人（人數不足）；工班 B 同時有 002、013 兩張施工中訂單
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:agri_matching_test;DB_CLOSE_DELAY=-1",
        "agri.demo.enabled=false",
        "agri.security.token-secret=unit-test-token-secret-0123456789abcdef",
        "agri.security.admin-password=Admin#Pass2026"
})
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MatchingApiTest extends ApiTestSupport {

    @Autowired AttendanceRepository attendanceRepository;

    private String admin;

    @BeforeEach
    void setUp() throws Exception {
        admin = login("admin_tech", "Admin#Pass2026");
    }

    private int status(String method, String url, String token, Object body) throws Exception {
        return call(method, url, token, body).getResponse().getStatus();
    }

    private static Set<String> recommendedIds(JsonNode crew) {
        Set<String> ids = new LinkedHashSet<>();
        for (JsonNode c : crew.get("candidates")) if (c.get("recommended").asBoolean()) ids.add(c.get("workerId").asText());
        return ids;
    }

    @Test
    @Order(1)
    void rankTeamsRecommendCrewAndAssign() throws Exception {
        JsonNode rec = json(call("GET", "/api/matching/orders/ORD-2026-005", admin, null)).get("data");
        assertEquals("harvest", rec.get("order").get("skill").asText());
        JsonNode teams = rec.get("teams");
        assertEquals("team_b", teams.get(0).get("teamId").asText(), teams.toString());
        assertEquals(2, teams.get(0).get("skilled").asInt());
        for (JsonNode t : teams) {
            if (List.of("team_c", "team_d").contains(t.get("teamId").asText())) {
                assertFalse(t.get("eligible").asBoolean());
                assertTrue(t.get("blockers").toString().contains("少於需求 6 人"));
            }
        }

        JsonNode crew = rec.get("crew");
        assertEquals("team_b", crew.get("teamId").asText());
        assertEquals(Set.of("W026", "W011", "W009", "W010", "W006", "W007"), recommendedIds(crew));
        for (JsonNode c : crew.get("candidates")) {
            if ("W026".equals(c.get("workerId").asText())) assertEquals("帶隊領班", c.get("role").asText());
            if ("W009".equals(c.get("workerId").asText())) assertEquals("培養名額", c.get("role").asText());
        }

        // 確認派工
        List<String> picks = new ArrayList<>(recommendedIds(crew));
        MvcResult saved = call("PUT", "/api/matching/orders/ORD-2026-005", admin, Map.of("teamId", "team_b", "crew", picks));
        assertEquals(200, saved.getResponse().getStatus(), saved.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertEquals(6, json(call("GET", "/api/matching/orders/ORD-2026-005", admin, null)).get("data").get("order").get("crew").size());

        // 名單驗證：非工班成員 400；未完整加保 409；空名單 400
        assertEquals(400, status("PUT", "/api/matching/orders/ORD-2026-005", admin, Map.of("teamId", "team_b", "crew", List.of("W001"))));
        assertEquals(400, status("PUT", "/api/matching/orders/ORD-2026-005", admin, Map.of("teamId", "team_b", "crew", List.of())));
        assertEquals(200, status("POST", "/api/insurance/records/INS-W024-health-20260301/withdraw", admin,
                Map.of("withdrawDate", LocalDate.now().toString())));
        assertEquals(409, status("PUT", "/api/matching/orders/ORD-2026-005", admin, Map.of("teamId", "team_b", "crew", List.of("W026", "W024"))));
        assertEquals(409, status("PUT", "/api/matching/orders/ORD-2026-010", admin, Map.of("teamId", "team_b", "crew", List.of("W026"))),
                "已取消訂單不可派工");
    }

    @Test
    @Order(2)
    void crewDisambiguatesClockInAndScopesInsuranceCheck() throws Exception {
        // 013 排定出工名單後，同時有 002、013 施工中的工班 B 成員打卡可正確連結
        assertEquals(200, status("PUT", "/api/matching/orders/ORD-2026-013", admin,
                Map.of("teamId", "team_b", "crew", List.of("W006", "W007", "W008", "W011", "W023", "W026"))));
        MvcResult in = call("POST", "/api/workers/W007/clock-in", admin, Map.of("location", "枋山"));
        assertEquals("ORD-2026-013", json(in).get("data").get("orderId").asText());
        assertEquals(200, status("POST", "/api/workers/W007/clock-out", admin, Map.of()));
        MvcResult other = call("POST", "/api/workers/W009/clock-in", admin, Map.of("location", "宜蘭"));
        assertTrue(json(other).get("data").get("orderId").isNull(), "不在任何出工名單、工班又有兩張施工中訂單 → 不連結");
        assertEquals(200, status("POST", "/api/workers/W009/clock-out", admin, Map.of()));
        assertEquals(400, status("POST", "/api/workers/W009/clock-in", admin, Map.of("orderId", "ORD-2026-013")), "不在 013 出工名單");

        // 有出工名單時，派工前加保檢查只看名單成員
        assertEquals(200, status("PUT", "/api/matching/orders/ORD-2026-009", admin,
                Map.of("teamId", "team_d", "crew", List.of("W016", "W017", "W019"))));
        assertEquals(200, status("POST", "/api/insurance/records/INS-W018-labor-20260301/withdraw", admin,
                Map.of("withdrawDate", LocalDate.now().toString())));
        assertEquals(200, status("PUT", "/api/orders/ORD-2026-009/status", admin, Map.of("status", "in_progress")),
                "W018 不在名單上，不擋出工");
    }

    @Test
    @Order(3)
    void traineeSlotKeepsOnePlaceForNewcomers() throws Exception {
        // 讓 W006、W007、W008、W009 也成為採收熟手 → 工班 B 合格的前 6 名全是熟手
        List<Attendance> list = new ArrayList<>();
        LocalDate start = LocalDate.of(2026, 8, 1);
        for (String w : List.of("W006", "W007", "W008", "W009")) {
            String orderId = "W009".equals(w) ? "ORD-2026-005" : "ORD-2026-002";
            for (int d = 0; d < 8; d++) {
                LocalDate day = start.plusDays(d);
                Attendance a = new Attendance(w, day.atTime(7, 0), "測試", 1.0);
                a.completeShift(day.atTime(15, 0), "測試領班");
                a.setOrderId(orderId);
                list.add(a);
            }
        }
        attendanceRepository.saveAll(list);

        JsonNode crew = json(call("GET", "/api/matching/orders/ORD-2026-005/crew?teamId=team_b", admin, null)).get("data");
        Set<String> picks = recommendedIds(crew);
        assertEquals(6, picks.size());
        assertTrue(picks.contains("W026"), "必含領班");
        assertTrue(picks.contains("W010"), "保留 1 個培養名額給時數最多的培養中工人：" + picks);
        assertFalse(picks.contains("W023"), "標籤暫停中的工人不佔培養名額：" + picks);
        assertFalse(picks.contains("W024"), "未完整加保者不推薦");
        boolean w024Excluded = false;
        for (JsonNode e : crew.get("excluded")) if ("W024".equals(e.get("workerId").asText())) w024Excluded = true;
        assertTrue(w024Excluded);
    }

    @Test
    @Order(4)
    void adminOnly() throws Exception {
        MvcResult r = call("POST", "/api/auth/reset-password", admin, Map.of("identifier", "farmer", "newPassword", "Farmer#2026pw"));
        assertEquals(200, r.getResponse().getStatus());
        String farmer = login("farmer", "Farmer#2026pw");
        assertEquals(403, status("GET", "/api/matching/orders/ORD-2026-013", farmer, null));
        assertEquals(403, status("PUT", "/api/matching/orders/ORD-2026-013", farmer, Map.of("teamId", "team_b", "crew", List.of("W026"))));
        assertEquals(401, status("GET", "/api/matching/orders/ORD-2026-013", null, null));
        assertEquals(404, status("GET", "/api/matching/orders/ORD-9999-999", admin, null));
    }
}
