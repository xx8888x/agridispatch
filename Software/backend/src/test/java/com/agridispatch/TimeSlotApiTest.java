package com.agridispatch;

import com.agridispatch.entity.Order;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 半日時段（production 模式，外籍移工功能關閉）
 * - 上午 07–11、下午 13–17，每人每天 4 小時；計費工時 = 人數 × 天數 × 4（最低計費）
 * - 同一天上午、下午不算撞期，整天與任一時段撞期
 * 種子：愛文芒果每分地 28 小時、早鳥 315 元/hr；F001 合作過的本國工人只有 W026（工班 B 領班）
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:agri_time_slot_test;DB_CLOSE_DELAY=-1",
        "agri.demo.enabled=false",
        "agri.features.foreign-workers=false",
        "agri.security.token-secret=unit-test-token-secret-0123456789abcdef",
        "agri.security.admin-password=Admin#Pass2026"
})
@AutoConfigureMockMvc
class TimeSlotApiTest extends ApiTestSupport {

    private String admin;

    @BeforeEach
    void setUp() throws Exception {
        admin = login("admin_tech", "Admin#Pass2026");
    }

    private String farmer() throws Exception {
        MvcResult r = call("POST", "/api/auth/reset-password", admin, Map.of("identifier", "farmer", "newPassword", "Farmer#2026pw"));
        assertEquals(200, r.getResponse().getStatus());
        return login("farmer", "Farmer#2026pw");
    }

    private JsonNode quote(Map<String, Object> req) throws Exception {
        MvcResult r = call("POST", "/api/orders/calculate", null, req);
        assertEquals(200, r.getResponse().getStatus(), body(r));
        return json(r).get("data");
    }

    private static Map<String, Object> order(String slot, LocalDate day, String preferred) {
        Map<String, Object> o = new HashMap<>();
        o.put("farmerName", "陳建龍");
        o.put("phone", "0912-345-678");
        o.put("farmLocation", "屏東縣枋山鄉");
        o.put("cropId", "mango");
        o.put("area", 0.1);   // 3 小時：1 人半日 1 天，計費 4 小時
        o.put("plan", "early_bird");
        o.put("dispatchCategory", "harvest_grading");
        o.put("requestedWorkers", 1);
        o.put("startDate", day.toString());
        o.put("endDate", day.toString());
        if (slot != null) o.put("timeSlot", slot);
        if (preferred != null) o.put("preferredWorkerId", preferred);
        return o;
    }

    private static String body(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    @Test
    void halfDayQuoteBillsAtLeastFourHoursPerWorkerPerDay() throws Exception {
        // 0.5 分地 = 14 小時；2 人半日 → 14 ÷ 8 進位 = 2 天 → 計費 16 小時（補足 2 小時）
        JsonNode half = quote(Map.of("cropId", "mango", "area", 0.5, "plan", "early_bird", "workers", 2, "timeSlot", "morning"));
        assertEquals(14, half.get("workHours").asInt());
        assertEquals(16, half.get("totalHours").asInt());
        assertEquals(2, half.get("minimumTopUpHours").asInt());
        assertEquals(2.0, half.get("estDays").asDouble(), 0.001);
        assertEquals(4, half.get("hoursPerDay").asInt());
        assertEquals("上午 07:00–11:00", half.get("timeSlotLabel").asText());
        assertEquals(16 * 315, half.get("actualTotal").asInt());
        assertEquals(Math.round(16 * 315 * 0.2), half.get("deposit").asInt());

        // 整天不受影響：14 小時照計，天數 = 14 ÷ 16
        JsonNode full = quote(Map.of("cropId", "mango", "area", 0.5, "plan", "early_bird", "workers", 2));
        assertEquals("full_day", full.get("timeSlot").asText());
        assertEquals(14, full.get("totalHours").asInt());
        assertEquals(0, full.get("minimumTopUpHours").asInt());
        assertEquals(14 * 315, full.get("actualTotal").asInt());

        // 剛好整除不補：6 分地 168 小時、3 人半日 = 14 天
        JsonNode exact = quote(Map.of("cropId", "mango", "area", 6, "plan", "early_bird", "workers", 3, "timeSlot", "afternoon"));
        assertEquals(168, exact.get("totalHours").asInt());
        assertEquals(0, exact.get("minimumTopUpHours").asInt());
        assertEquals(14.0, exact.get("estDays").asDouble(), 0.001);

        // 不認得的時段、工時包配半日 → 400
        assertEquals(400, call("POST", "/api/orders/calculate", null, Map.of("cropId", "mango", "area", 1, "timeSlot", "evening"))
                .getResponse().getStatus());
        assertEquals(400, call("POST", "/api/orders/calculate", null, Map.of("packageId", "dm-mango-2026", "timeSlot", "morning"))
                .getResponse().getStatus());
    }

    @Test
    void morningAndAfternoonDoNotClashButFullDayDoes() throws Exception {
        String f001 = farmer();
        LocalDate day = LocalDate.now().plusDays(90);
        String past = "/api/farmers/me/past-workers?start=" + day + "&end=" + day;

        // 上午指名 W026
        MvcResult am = call("POST", "/api/orders", f001, order("morning", day, "W026"));
        assertEquals(201, am.getResponse().getStatus(), body(am));
        JsonNode amOrder = json(am).get("data");
        assertEquals("morning", amOrder.get("timeSlot").asText());
        assertEquals(4, amOrder.get("totalHours").asInt());
        assertEquals(4 * 315, amOrder.get("finalAmount").asInt());
        String amId = amOrder.get("id").asText();

        // 上午已被佔用；下午還可以約
        assertFalse(json(call("GET", past + "&timeSlot=morning", f001, null)).get("data").get(0).get("available").asBoolean());
        assertTrue(json(call("GET", past + "&timeSlot=afternoon", f001, null)).get("data").get(0).get("available").asBoolean());
        assertFalse(json(call("GET", past, f001, null)).get("data").get(0).get("available").asBoolean(), "整天與上午衝突");

        MvcResult pm = call("POST", "/api/orders", f001, order("afternoon", day, "W026"));
        assertEquals(201, pm.getResponse().getStatus(), body(pm));
        String pmId = json(pm).get("data").get("id").asText();
        MvcResult full = call("POST", "/api/orders", f001, order("full_day", day, "W026"));
        assertEquals(409, full.getResponse().getStatus(), body(full));
        MvcResult am2 = call("POST", "/api/orders", f001, order("morning", day, "W026"));
        assertEquals(409, am2.getResponse().getStatus());
        assertTrue(body(am2).contains("上午 07:00–11:00"), body(am2));

        // 媒合：上午排入 W026 後，下午仍可排他；同一天的整天單不行
        assertEquals(200, call("PUT", "/api/matching/orders/" + amId, admin, Map.of("teamId", "team_b", "crew", List.of("W026")))
                .getResponse().getStatus());
        MvcResult pmAssign = call("PUT", "/api/matching/orders/" + pmId, admin, Map.of("teamId", "team_b", "crew", List.of("W026")));
        assertEquals(200, pmAssign.getResponse().getStatus(), body(pmAssign));
        // 指定另一個工班：未指定時會依媒合排序自動分配（可能也是 team_b），行事曆筆數就不只兩張半日單
        Map<String, Object> otherBody = order("full_day", day, null);
        otherBody.put("assignedTeamId", "team_a");
        MvcResult other = call("POST", "/api/orders", admin, otherBody);
        assertEquals(201, other.getResponse().getStatus(), body(other));
        String otherId = json(other).get("data").get("id").asText();
        MvcResult clash = call("PUT", "/api/matching/orders/" + otherId, admin, Map.of("teamId", "team_b", "crew", List.of("W026")));
        assertEquals(409, clash.getResponse().getStatus());
        assertTrue(body(clash).contains("工期撞期"), body(clash));
        JsonNode rec = json(call("GET", "/api/matching/orders/" + pmId, admin, null)).get("data");
        assertEquals("afternoon", rec.get("order").get("timeSlot").asText());
        assertEquals("下午 13:00–17:00", rec.get("order").get("timeSlotLabel").asText());

        // 工人行事曆：同一天兩張半日單
        JsonNode items = json(call("GET", "/api/workers/W026/schedule?from=" + day + "&to=" + day, admin, null)).get("data").get("items");
        assertEquals(2, items.size(), items.toString());
        assertEquals(List.of("morning", "afternoon"), List.of(slotOf(items, amId), slotOf(items, pmId)));
    }

    private static String slotOf(JsonNode items, String orderId) {
        for (JsonNode i : items) if (orderId.equals(i.get("orderId").asText())) return i.get("timeSlot").asText();
        return null;
    }

    @Test
    void farmerCanChangeSlotOnlyWhilePending() throws Exception {
        String f001 = farmer();
        LocalDate day = LocalDate.now().plusDays(150);
        Map<String, Object> req = order("full_day", day, null);
        req.put("area", 0.5);   // 14 小時
        MvcResult created = call("POST", "/api/orders", f001, req);
        assertEquals(201, created.getResponse().getStatus(), body(created));
        JsonNode o = json(created).get("data");
        assertEquals("pending", o.get("status").asText());
        assertEquals(14 * 315, o.get("finalAmount").asInt());
        String id = o.get("id").asText();

        // 改成下午：1 人半日 → 4 天 × 4 = 16 小時，重算金額
        MvcResult changed = call("PUT", "/api/orders/" + id, f001, Map.of("timeSlot", "afternoon"));
        assertEquals(200, changed.getResponse().getStatus(), body(changed));
        assertEquals(16, json(changed).get("data").get("totalHours").asInt());
        assertEquals(16 * 315, json(changed).get("data").get("finalAmount").asInt());
        assertEquals(400, call("PUT", "/api/orders/" + id, f001, Map.of("timeSlot", "night")).getResponse().getStatus());

        // 已排定（免定金的彈性計時一建立就是已排定）後農場主不能改時段，admin 可以
        Map<String, Object> flex = order("full_day", day.plusDays(1), null);
        flex.put("plan", "hourly_flex");
        JsonNode confirmed = json(call("POST", "/api/orders", f001, flex)).get("data");
        assertEquals("confirmed", confirmed.get("status").asText());
        String cid = confirmed.get("id").asText();
        assertEquals(409, call("PUT", "/api/orders/" + cid, f001, Map.of("timeSlot", "morning")).getResponse().getStatus());
        MvcResult byAdmin = call("PUT", "/api/orders/" + cid, admin, Map.of("timeSlot", "morning"));
        assertEquals(200, byAdmin.getResponse().getStatus(), body(byAdmin));
        assertEquals("morning", json(byAdmin).get("data").get("timeSlot").asText());
    }

    @Test
    void clockInTimePicksMatchingSlot() {
        Order am = new Order();
        am.setTimeSlot(Order.MORNING);
        Order pm = new Order();
        pm.setTimeSlot(Order.AFTERNOON);
        Order legacy = new Order();   // 舊資料 null = 整天
        assertTrue(am.slotCovers(LocalTime.of(6, 50)));
        assertFalse(am.slotCovers(LocalTime.of(12, 50)));
        assertTrue(pm.slotCovers(LocalTime.of(12, 50)));
        assertFalse(pm.slotCovers(LocalTime.of(10, 0)));
        assertTrue(legacy.slotCovers(LocalTime.of(15, 0)));
        assertFalse(legacy.isHalfDay());
        assertTrue(Order.slotsOverlap(null, Order.MORNING));
        assertFalse(Order.slotsOverlap(Order.MORNING, Order.AFTERNOON));
    }
}
