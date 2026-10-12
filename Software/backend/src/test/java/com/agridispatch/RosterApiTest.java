package com.agridispatch;

import com.agridispatch.service.RosterService;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 社區共享班表（production 模式，外籍移工功能關閉）
 * 種子：工班 B 的本國工人 W026（領班）、W033、W034 完整加保；芒果作物存在；F001＝farmer、F002＝farmer_yilan
 * 每個測試用不同期間，避免班表互相佔用名單
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:agri_roster_test;DB_CLOSE_DELAY=-1",
        "agri.demo.enabled=false",
        "agri.features.foreign-workers=false",
        "agri.security.token-secret=unit-test-token-secret-0123456789abcdef",
        "agri.security.admin-password=Admin#Pass2026"
})
@AutoConfigureMockMvc
class RosterApiTest extends ApiTestSupport {

    private static final List<String> CREW = List.of("W026", "W033", "W034");

    @Autowired RosterService rosterService;

    private String admin;

    @BeforeEach
    void setUp() throws Exception {
        admin = login("admin_tech", "Admin#Pass2026");
    }

    private String farmer(String username, String password) throws Exception {
        MvcResult r = call("POST", "/api/auth/reset-password", admin, Map.of("identifier", username, "newPassword", password));
        assertEquals(200, r.getResponse().getStatus());
        return login(username, password);
    }

    private static String body(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private JsonNode createRoster(LocalDate start, int days) throws Exception {
        Map<String, Object> req = new HashMap<>();
        req.put("title", "枋山芒果產銷班共享班表");
        req.put("region", "屏東縣枋山鄉善餘村");
        req.put("teamId", "team_b");
        req.put("crew", CREW);
        req.put("dispatchCategory", "harvest_grading");
        req.put("startDate", start.toString());
        req.put("endDate", start.plusDays(days - 1).toString());
        req.put("minClaimPercent", 60);
        MvcResult r = call("POST", "/api/rosters", admin, req);
        assertEquals(201, r.getResponse().getStatus(), body(r));
        return json(r).get("data");
    }

    private static Map<String, Object> claim(LocalDate day, String slot) {
        Map<String, Object> c = new HashMap<>();
        c.put("date", day.toString());
        c.put("timeSlot", slot);
        c.put("farmerName", "陳建龍");
        c.put("phone", "0912-345-678");
        c.put("farmLocation", "屏東縣枋山鄉善餘村 12 號");
        c.put("cropId", "mango");
        c.put("area", 1.5);
        c.put("plan", "early_bird");
        return c;
    }

    @Test
    void openClaimAndCancelWhenBelowThreshold() throws Exception {
        String f001 = farmer("farmer", "Farmer#2026pw");
        String f002 = farmer("farmer_yilan", "Yilan#2026pw");
        LocalDate start = LocalDate.now().plusDays(20);
        JsonNode r = createRoster(start, 5);
        String id = r.get("id").asText();
        String url = "/api/rosters/" + id;
        assertEquals("open", r.get("status").asText());
        assertEquals(10, r.get("totalSlots").asInt());
        assertEquals(3, r.get("crewSize").asInt());
        assertEquals(start.minusDays(3).toString(), r.get("claimDeadline").asText());
        assertEquals(3, r.get("crew").size(), "admin 看得到名單");
        // 每格報價：3 人 × 4 小時 × 315 = 3,780，定金 20%
        assertEquals(3780, r.get("prices").get("halfDay").get("early_bird").get("total").asInt());
        assertEquals(756, r.get("prices").get("halfDay").get("early_bird").get("deposit").asInt());
        assertEquals(0, r.get("prices").get("halfDay").get("hourly_flex").get("deposit").asInt());

        // 農戶看得到班表，看不到名單
        JsonNode list = json(call("GET", "/api/rosters", f001, null)).get("data");
        JsonNode mine = null;
        for (JsonNode x : list) if (id.equals(x.get("id").asText())) mine = x;
        assertNotNull(mine, list.toString());
        assertFalse(mine.has("crew"));
        assertEquals("林小祥", mine.get("leaderName").asText());
        assertEquals("free", mine.get("days").get(0).get("morning").get("state").asText());

        // 認領第一天上午
        MvcResult c1 = call("POST", url + "/claims", f001, claim(start, "morning"));
        assertEquals(201, c1.getResponse().getStatus(), body(c1));
        JsonNode o1 = json(c1).get("data");
        assertEquals(id, o1.get("sharedRosterId").asText());
        assertEquals("morning", o1.get("timeSlot").asText());
        assertEquals(12, o1.get("totalHours").asInt());
        assertEquals(3780, o1.get("finalAmount").asInt());
        assertEquals(756, o1.get("depositAmount").asInt());
        assertEquals("pending", o1.get("status").asText());
        assertEquals("team_b", o1.get("assignedTeamId").asText());
        assertEquals(String.join(",", CREW), o1.get("crewWorkerIds").asText());
        assertEquals(start.toString(), o1.get("startDate").asText());
        assertEquals(start.toString(), o1.get("endDate").asText());
        String o1Id = o1.get("id").asText();

        // 同一格、整天都撞；下午可以
        assertEquals(409, call("POST", url + "/claims", f002, claim(start, "morning")).getResponse().getStatus());
        assertEquals(409, call("POST", url + "/claims", f002, claim(start, "full_day")).getResponse().getStatus());
        MvcResult c2 = call("POST", url + "/claims", f002, claim(start, "afternoon"));
        assertEquals(201, c2.getResponse().getStatus(), body(c2));

        // 農戶看到自己的格與別人的格（看不到是誰）；admin 看得到
        JsonNode day0 = json(call("GET", url, f001, null)).get("data").get("days").get(0);
        assertEquals("mine", day0.get("morning").get("state").asText());
        assertEquals(o1Id, day0.get("morning").get("orderId").asText());
        assertEquals("taken", day0.get("afternoon").get("state").asText());
        assertFalse(day0.get("afternoon").has("farmerName"));
        assertFalse(day0.get("afternoon").has("orderId"));
        JsonNode adminDay0 = json(call("GET", url, admin, null)).get("data").get("days").get(0);
        assertEquals("陳建龍", adminDay0.get("afternoon").get("farmerName").asText());

        // 其他驗證：期間外、成果統包、工人與農戶權限
        assertEquals(400, call("POST", url + "/claims", f001, claim(start.plusDays(9), "morning")).getResponse().getStatus());
        Map<String, Object> turnkey = claim(start.plusDays(1), "morning");
        turnkey.put("plan", "turnkey");
        assertEquals(400, call("POST", url + "/claims", f001, turnkey).getResponse().getStatus());
        assertEquals(403, call("POST", "/api/rosters", f001, Map.of("title", "x")).getResponse().getStatus());
        assertEquals(403, call("POST", url + "/claims", admin, claim(start.plusDays(1), "morning")).getResponse().getStatus());

        // 認領訂單不可改規格，可改現場交辦
        assertEquals(409, call("PUT", "/api/orders/" + o1Id, f001, Map.of("area", 3.0)).getResponse().getStatus());
        assertEquals(409, call("PUT", "/api/orders/" + o1Id, f001, Map.of("timeSlot", "afternoon")).getResponse().getStatus());
        assertEquals(200, call("PUT", "/api/orders/" + o1Id, f001, Map.of("foremanNotes", "從東側產業道路進來")).getResponse().getStatus());

        // 名單整段期間被班表佔用：其他訂單派不到、其他班表也開不了
        Map<String, Object> other = new HashMap<>(Map.of("farmerName", "別的農友", "cropId", "mango", "area", 1.0,
                "startDate", start.plusDays(2).toString(), "endDate", start.plusDays(2).toString()));
        String otherId = json(call("POST", "/api/orders", admin, other)).get("data").get("id").asText();
        MvcResult clash = call("PUT", "/api/matching/orders/" + otherId, admin, Map.of("teamId", "team_b", "crew", List.of("W026")));
        assertEquals(409, clash.getResponse().getStatus());
        assertTrue(body(clash).contains(id), body(clash));
        Map<String, Object> overlap = new HashMap<>(Map.of("title", "重疊", "region", "枋山", "teamId", "team_b", "crew", List.of("W033"),
                "startDate", start.plusDays(4).toString(), "endDate", start.plusDays(6).toString()));
        assertEquals(409, call("POST", "/api/rosters", admin, overlap).getResponse().getStatus());

        // 2/10 = 20% 未達 60%：不能手動開班
        assertEquals(409, call("POST", url + "/confirm", admin, null).getResponse().getStatus());

        // 退出時段（取消訂單）後格子空出來
        assertEquals(200, call("DELETE", "/api/orders/" + o1Id, f001, null).getResponse().getStatus());
        assertEquals("free", json(call("GET", url, f001, null)).get("data").get("days").get(0).get("morning").get("state").asText());

        // 截止日過後未達門檻 → 自動取消，連帶取消認領訂單
        JsonNode result = om.valueToTree(rosterService.evaluateDeadlines(start.minusDays(2)));
        assertTrue(result.get("cancelled").toString().contains(id), result.toString());
        JsonNode after = json(call("GET", url, admin, null)).get("data");
        assertEquals("cancelled", after.get("status").asText());
        assertTrue(after.get("cancelReason").asText().contains("未達門檻"));
        assertEquals("cancelled", json(call("GET", "/api/orders/" + json(c2).get("data").get("id").asText(), admin, null))
                .get("data").get("status").asText());
        assertEquals(404, call("GET", url, f001, null).getResponse().getStatus(), "農戶看不到已取消的班表");
        assertEquals(409, call("POST", url + "/claims", f001, claim(start.plusDays(1), "morning")).getResponse().getStatus());
    }

    @Test
    void reachingThresholdConfirmsThenCompletes() throws Exception {
        String f001 = farmer("farmer", "Farmer#2026pw");
        LocalDate start = LocalDate.now().plusDays(60);
        JsonNode r = createRoster(start, 5);
        String id = r.get("id").asText();
        String url = "/api/rosters/" + id;

        // 認領 3 個整天 = 6/10 = 60%
        for (int d = 0; d < 3; d++) {
            MvcResult c = call("POST", url + "/claims", f001, claim(start.plusDays(d), "full_day"));
            assertEquals(201, c.getResponse().getStatus(), body(c));
            assertEquals(24, json(c).get("data").get("totalHours").asInt(), "3 人 × 8 小時");
        }
        JsonNode v = json(call("GET", url, f001, null)).get("data");
        assertEquals(6, v.get("claimedSlots").asInt());
        assertEquals(60, v.get("claimPercent").asInt());
        assertTrue(v.get("thresholdMet").asBoolean());

        // 截止日後自動開班；開班後剩下的空檔仍可認領
        JsonNode result = om.valueToTree(rosterService.evaluateDeadlines(start.minusDays(2)));
        assertTrue(result.get("confirmed").toString().contains(id), result.toString());
        assertEquals("confirmed", json(call("GET", url, f001, null)).get("data").get("status").asText());
        assertEquals(201, call("POST", url + "/claims", f001, claim(start.plusDays(4), "afternoon")).getResponse().getStatus());

        // 工人行事曆看得到班表時段
        JsonNode items = json(call("GET", "/api/workers/W033/schedule?from=" + start + "&to=" + start.plusDays(4), admin, null))
                .get("data").get("items");
        assertEquals(4, items.size(), items.toString());

        // 期間過後改為已結束；已結束、已取消的班表不能再取消
        result = om.valueToTree(rosterService.evaluateDeadlines(start.plusDays(5)));
        assertTrue(result.get("completed").toString().contains(id), result.toString());
        assertEquals("completed", json(call("GET", url, admin, null)).get("data").get("status").asText());
        assertEquals(409, call("POST", url + "/cancel", admin, Map.of("reason", "test")).getResponse().getStatus());
    }

    @Test
    void adminCancelAndValidation() throws Exception {
        LocalDate start = LocalDate.now().plusDays(100);
        String f001 = farmer("farmer", "Farmer#2026pw");
        JsonNode r = createRoster(start, 3);
        String id = r.get("id").asText();
        String orderId = json(call("POST", "/api/rosters/" + id + "/claims", f001, claim(start, "morning"))).get("data").get("id").asText();
        MvcResult c = call("POST", "/api/rosters/" + id + "/cancel", admin, Map.of("reason", "颱風季調度"));
        assertEquals(200, c.getResponse().getStatus(), body(c));
        assertEquals(List.of(orderId), List.of(json(c).get("data").get("cancelledOrders").get(0).asText()));
        assertEquals("颱風季調度", json(c).get("data").get("roster").get("cancelReason").asText());

        // 開班驗證
        Map<String, Object> bad = new HashMap<>(Map.of("title", "x", "region", "y", "teamId", "team_b", "crew", CREW,
                "startDate", start.toString(), "endDate", start.plusDays(40).toString()));
        assertEquals(400, call("POST", "/api/rosters", admin, bad).getResponse().getStatus(), "超過 31 天");
        bad.put("endDate", start.toString());
        bad.put("dispatchCategory", "typhoon_emergency");
        assertEquals(400, call("POST", "/api/rosters", admin, bad).getResponse().getStatus());
        bad.remove("dispatchCategory");
        bad.put("crew", List.of("W001"));
        assertEquals(400, call("POST", "/api/rosters", admin, bad).getResponse().getStatus(), "非工班成員");
        bad.put("crew", CREW);
        bad.put("startDate", LocalDate.now().toString());
        assertEquals(400, call("POST", "/api/rosters", admin, bad).getResponse().getStatus(), "開工日須在今天之後");
        assertEquals(403, call("GET", "/api/rosters", farmer("W026", "Worker#2026pw"), null).getResponse().getStatus(), "工人不能看班表");
    }
}
