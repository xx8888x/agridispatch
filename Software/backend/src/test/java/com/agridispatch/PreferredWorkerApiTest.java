package com.agridispatch;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 指名回頭工人（production 模式，外籍移工功能關閉）
 * 種子：F001（farmer）的 ORD-2026-013 有 W006、W007、W008、W011、W023、W026 的出勤；
 *       外籍移工下架時只剩本國帶工領班 W026（林小祥，工班 B）可指名
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:agri_preferred_worker_test;DB_CLOSE_DELAY=-1",
        "agri.demo.enabled=false",
        "agri.features.foreign-workers=false",
        "agri.security.token-secret=unit-test-token-secret-0123456789abcdef",
        "agri.security.admin-password=Admin#Pass2026"
})
@AutoConfigureMockMvc
class PreferredWorkerApiTest extends ApiTestSupport {

    private static final String PAST = "/api/farmers/me/past-workers";

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

    private static Map<String, Object> order(String preferred, LocalDate start, LocalDate end) {
        Map<String, Object> o = new HashMap<>();
        o.put("farmerName", "陳建龍");
        o.put("phone", "0912-345-678");
        o.put("farmLocation", "屏東縣枋山鄉");
        o.put("cropId", "mango");
        o.put("area", 2.0);
        o.put("plan", "early_bird");
        o.put("dispatchCategory", "harvest_grading");
        o.put("requestedWorkers", 1);
        if (preferred != null) o.put("preferredWorkerId", preferred);
        o.put("preferredWorkerName", "前端亂填的名字");
        if (start != null) o.put("startDate", start.toString());
        if (end != null) o.put("endDate", end.toString());
        return o;
    }

    private static String body(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    @Test
    void farmerRebooksPastWorkerAndMatchingHonoursIt() throws Exception {
        String f001 = farmer("farmer", "Farmer#2026pw");
        LocalDate start = LocalDate.now().plusDays(60);
        LocalDate end = start.plusDays(2);

        // 合作過的工人：外籍移工下架，只剩 W026
        JsonNode past = json(call("GET", PAST, f001, null)).get("data");
        assertEquals(1, past.size(), past.toString());
        JsonNode w026 = past.get(0);
        assertEquals("W026", w026.get("workerId").asText());
        assertEquals("林小祥", w026.get("name").asText());
        assertTrue(w026.get("leader").asBoolean());
        assertEquals(1, w026.get("timesWorked").asInt());
        assertFalse(w026.get("lastWorkedOn").isNull());
        assertTrue(w026.get("available").isNull(), "沒給工期不判斷能否排班");
        assertFalse(w026.has("phone"), "不回電話等個資");

        JsonNode free = json(call("GET", PAST + "?start=" + start + "&end=" + end, f001, null)).get("data").get(0);
        assertTrue(free.get("available").asBoolean(), free.toString());

        // 指名下單：姓名、工班由後端帶入
        MvcResult created = call("POST", "/api/orders", f001, order("W026", start, end));
        assertEquals(201, created.getResponse().getStatus(), body(created));
        JsonNode o = json(created).get("data");
        assertEquals("W026", o.get("preferredWorkerId").asText());
        assertEquals("林小祥", o.get("preferredWorkerName").asText());
        assertEquals("team_b", o.get("assignedTeamId").asText());
        String orderId = o.get("id").asText();

        // 尚未排出工名單的指名也佔用該工期
        JsonNode held = json(call("GET", PAST + "?start=" + start.plusDays(1) + "&end=" + end.plusDays(3), f001, null)).get("data").get(0);
        assertFalse(held.get("available").asBoolean());
        assertEquals("該期間已有排班", held.get("unavailableReason").asText());
        MvcResult clash = call("POST", "/api/orders", f001, order("W026", start.plusDays(1), end.plusDays(1)));
        assertEquals(409, clash.getResponse().getStatus(), body(clash));
        assertTrue(body(clash).contains("已有排班"));
        assertFalse(body(clash).contains(orderId), "不透露其他訂單");

        // 媒合：指名工人的工班排第一、指名工人排在出工名單第一位
        JsonNode rec = json(call("GET", "/api/matching/orders/" + orderId, admin, null)).get("data");
        assertEquals("W026", rec.get("order").get("preferredWorkerId").asText());
        JsonNode crew = rec.get("crew");
        assertEquals("team_b", crew.get("teamId").asText());
        assertTrue(crew.get("preferredWarning").isNull(), crew.toString());
        JsonNode first = crew.get("candidates").get(0);
        assertEquals("W026", first.get("workerId").asText(), crew.toString());
        assertTrue(first.get("recommended").asBoolean());
        assertEquals("農場主指名・帶隊領班", first.get("role").asText());
        for (JsonNode t : rec.get("teams")) {
            assertEquals("team_b".equals(t.get("teamId").asText()), t.get("hasPreferred").asBoolean());
        }

        // 工人行事曆標示「農場主指名」
        JsonNode items = json(call("GET", "/api/workers/W026/schedule?from=" + start + "&to=" + end, admin, null)).get("data").get("items");
        boolean found = false;
        for (JsonNode it : items) {
            if (orderId.equals(it.get("orderId").asText())) {
                found = true;
                assertTrue(it.get("preferredByFarmer").asBoolean());
            }
        }
        assertTrue(found, items.toString());
    }

    @Test
    void onlyPastWorkersWithPeriodCanBePreferred() throws Exception {
        String f001 = farmer("farmer", "Farmer#2026pw");
        String f002 = farmer("farmer_yilan", "Yilan#2026pw");
        LocalDate start = LocalDate.now().plusDays(120);

        // 沒合作過
        assertTrue(json(call("GET", PAST, f002, null)).get("data").isEmpty());
        MvcResult stranger = call("POST", "/api/orders", f002, order("W026", start, start));
        assertEquals(400, stranger.getResponse().getStatus(), body(stranger));
        assertTrue(body(stranger).contains("曾到您農場出工"));
        // 外籍移工下架中：有出勤紀錄也不能指名
        assertEquals(400, call("POST", "/api/orders", f001, order("W006", start, start)).getResponse().getStatus());
        // 不存在的工號
        assertEquals(400, call("POST", "/api/orders", f001, order("W999", start, start)).getResponse().getStatus());
        // 指名須先選起工日
        MvcResult noDate = call("POST", "/api/orders", f001, order("W026", null, null));
        assertEquals(400, noDate.getResponse().getStatus(), body(noDate));
        // 不指名照常建單
        MvcResult plain = call("POST", "/api/orders", f001, order(null, start, start));
        assertEquals(201, plain.getResponse().getStatus(), body(plain));
        assertTrue(json(plain).get("data").get("preferredWorkerId").isNull());
        assertTrue(json(plain).get("data").get("preferredWorkerName").isNull(), "前端傳的姓名不採用");

        // 只有農場主能查；日期格式
        assertEquals(403, call("GET", PAST, admin, null).getResponse().getStatus());
        assertEquals(401, call("GET", PAST, null, null).getResponse().getStatus());
        assertEquals(400, call("GET", PAST + "?start=2026/10/01", f001, null).getResponse().getStatus());
        assertEquals(400, call("GET", PAST + "?start=" + start + "&end=" + start.minusDays(1), f001, null).getResponse().getStatus());
    }
}
