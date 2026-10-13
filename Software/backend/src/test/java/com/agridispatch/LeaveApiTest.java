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
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 工人請假（production 模式）
 * 種子：工班 B 的本國工人 W026（領班）、W011、W033、W034 完整加保；ORD-2026-005 為工班 B 待付定金的採收訂單（需 6 人）
 * 每個測試用不同日期，避免請假互相重疊
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:agri_leave_test;DB_CLOSE_DELAY=-1",
        "agri.demo.enabled=false",
        "agri.security.token-secret=unit-test-token-secret-0123456789abcdef",
        "agri.security.admin-password=Admin#Pass2026"
})
@AutoConfigureMockMvc
class LeaveApiTest extends ApiTestSupport {

    private String admin;

    @BeforeEach
    void setUp() throws Exception {
        admin = login("admin_tech", "Admin#Pass2026");
    }

    private String worker(String id) throws Exception {
        assertEquals(200, call("POST", "/api/auth/reset-password", admin,
                Map.of("identifier", id, "newPassword", "Worker#2026pw")).getResponse().getStatus());
        return login(id, "Worker#2026pw");
    }

    private static String body(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private static int status(MvcResult r) {
        return r.getResponse().getStatus();
    }

    private static Map<String, Object> leave(String type, LocalDate start, LocalDate end, String slot) {
        Map<String, Object> m = new HashMap<>();
        m.put("leaveType", type);
        m.put("startDate", start.toString());
        if (end != null) m.put("endDate", end.toString());
        if (slot != null) m.put("timeSlot", slot);
        m.put("reason", "家中有事");
        return m;
    }

    @Test
    void workerAppliesListsAndCancels() throws Exception {
        String w33 = worker("W033");
        String w34 = worker("W034");
        LocalDate d = LocalDate.now().plusDays(40);

        MvcResult c = call("POST", "/api/workers/W033/leaves", w33, leave("personal", d, d.plusDays(1), null));
        assertEquals(201, status(c), body(c));
        JsonNode l = json(c).get("data");
        String id = l.get("id").asText();
        assertTrue(id.startsWith("LV-"));
        assertEquals("pending", l.get("status").asText());
        assertEquals("事假", l.get("leaveTypeLabel").asText());
        assertEquals(2, l.get("days").asInt());
        assertTrue(l.get("cancellable").asBoolean());

        // 只有本人與 admin 看得到、申請得了
        assertEquals(1, json(call("GET", "/api/workers/W033/leaves", w33, null)).get("data").size());
        assertEquals(403, status(call("GET", "/api/workers/W033/leaves", w34, null)));
        assertEquals(403, status(call("POST", "/api/workers/W033/leaves", w34, leave("personal", d.plusDays(5), null, null))));
        assertEquals(403, status(call("GET", "/api/leaves", w33, null)), "全部請假清單只給合作社");
        assertEquals(403, status(call("POST", "/api/leaves/" + id + "/approve", w33, Map.of())), "不能自己核准");
        assertEquals(403, status(call("POST", "/api/leaves/" + id + "/cancel", w34, Map.of())), "不能取消別人的假");

        // 與自己已申請的假重疊 → 409；上午、下午半天不重疊
        assertEquals(409, status(call("POST", "/api/workers/W033/leaves", w33, leave("sick", d.plusDays(1), null, null))));
        assertEquals(201, status(call("POST", "/api/workers/W033/leaves", w33, leave("sick", d.plusDays(3), null, "morning"))));
        assertEquals(201, status(call("POST", "/api/workers/W033/leaves", w33, leave("sick", d.plusDays(3), null, "afternoon"))));

        // admin 清單：待審核排前面，帶工人姓名
        JsonNode all = json(call("GET", "/api/leaves?status=pending", admin, null)).get("data");
        assertTrue(all.size() >= 3);
        assertEquals("pending", all.get(0).get("status").asText());
        assertNotNull(all.get(0).get("workerName").asText());

        // 本人取消待審核的假
        MvcResult cancel = call("POST", "/api/leaves/" + id + "/cancel", w33, Map.of());
        assertEquals(200, status(cancel), body(cancel));
        assertEquals("cancelled", json(cancel).get("data").get("status").asText());
        assertEquals(409, status(call("POST", "/api/leaves/" + id + "/approve", admin, Map.of())), "已取消不能再審核");
        // 取消後同一段可以重新申請
        assertEquals(201, status(call("POST", "/api/workers/W033/leaves", w33, leave("annual", d, null, null))));
    }

    @Test
    void validation() throws Exception {
        String w34 = worker("W034");
        LocalDate d = LocalDate.now().plusDays(60);
        assertEquals(400, status(call("POST", "/api/workers/W034/leaves", w34, leave("vacation", d, null, null))), "未知假別");
        assertEquals(400, status(call("POST", "/api/workers/W034/leaves", w34, leave("personal", d, d.minusDays(1), null))), "結束早於開始");
        assertEquals(400, status(call("POST", "/api/workers/W034/leaves", w34, leave("personal", d, d.plusDays(1), "morning"))), "半天假只能單日");
        assertEquals(400, status(call("POST", "/api/workers/W034/leaves", w34, leave("personal", d, d.plusDays(31), null))), "超過 31 天");
        assertEquals(400, status(call("POST", "/api/workers/W034/leaves", w34,
                leave("sick", LocalDate.now().minusDays(31), null, null))), "補請超過 30 天");
        assertEquals(201, status(call("POST", "/api/workers/W034/leaves", w34,
                leave("sick", LocalDate.now().minusDays(3), null, null))), "30 天內可補請");
        Map<String, Object> noStart = new HashMap<>(Map.of("leaveType", "personal"));
        assertEquals(400, status(call("POST", "/api/workers/W034/leaves", w34, noStart)));
        assertEquals(404, status(call("POST", "/api/workers/W999/leaves", admin, leave("personal", d, null, null))));
        assertEquals(12, json(call("GET", "/api/leaves/types", w34, null)).get("data").size());
    }

    @Test
    void approvedLeaveBlocksDispatchAndReportsConflicts() throws Exception {
        LocalDate d = LocalDate.now().plusDays(80);
        // ORD-2026-005 工期 d～d+2，先排好出工名單（含 W026）
        assertEquals(200, status(call("PUT", "/api/orders/ORD-2026-005", admin,
                Map.of("startDate", d.toString(), "endDate", d.plusDays(2).toString()))));
        MvcResult assigned = call("PUT", "/api/matching/orders/ORD-2026-005", admin,
                Map.of("teamId", "team_b", "crew", List.of("W026", "W033")));
        assertEquals(200, status(assigned), body(assigned));

        // W026 申請時就提醒已排入的訂單；核准後仍回報，交由合作社改派
        String w26 = worker("W026");
        JsonNode l26 = json(call("POST", "/api/workers/W026/leaves", w26, leave("personal", d.plusDays(1), null, null))).get("data");
        assertEquals("ORD-2026-005", l26.get("conflicts").get(0).get("id").asText());
        assertEquals(400, status(call("POST", "/api/leaves/" + l26.get("id").asText() + "/reject", admin, Map.of())), "駁回須填原因");
        MvcResult ok = call("POST", "/api/leaves/" + l26.get("id").asText() + "/approve", admin, Map.of("note", "請領班協調代班"));
        assertEquals(200, status(ok), body(ok));
        assertEquals("approved", json(ok).get("data").get("status").asText());
        assertEquals("ORD-2026-005", json(ok).get("data").get("conflicts").get(0).get("id").asText());

        // W011 核准半天假（上午）→ 整天的訂單也算撞期
        JsonNode l11 = json(call("POST", "/api/workers/W011/leaves", admin, leave("sick", d.plusDays(2), null, "morning"))).get("data");
        assertEquals(200, status(call("POST", "/api/leaves/" + l11.get("id").asText() + "/approve", admin, Map.of())));
        // 待審核的假不擋派工
        String w34 = worker("W034");
        assertEquals(201, status(call("POST", "/api/workers/W034/leaves", w34, leave("personal", d, null, null))));

        JsonNode crew = json(call("GET", "/api/matching/orders/ORD-2026-005/crew?teamId=team_b", admin, null)).get("data");
        Map<String, String> excluded = new HashMap<>();
        for (JsonNode x : crew.get("excluded")) excluded.put(x.get("workerId").asText(), x.get("excludedReason").asText());
        assertTrue(excluded.getOrDefault("W026", "").startsWith("請假中：LV-"), excluded.toString());
        assertTrue(excluded.getOrDefault("W011", "").contains("上午"), excluded.toString());
        assertFalse(excluded.containsKey("W034"), "待審核不擋派工");

        // 確認派工時把請假中的人排進名單 → 409
        MvcResult clash = call("PUT", "/api/matching/orders/ORD-2026-005", admin,
                Map.of("teamId", "team_b", "crew", List.of("W026", "W033")));
        assertEquals(409, status(clash));
        assertTrue(body(clash).contains("請假中"), body(clash));

        // 工人排班帶出請假（行事曆用）
        JsonNode sched = json(call("GET", "/api/workers/W026/schedule?from=" + d + "&to=" + d.plusDays(5), w26, null)).get("data");
        assertEquals(1, sched.get("leaves").size());
        assertEquals("approved", sched.get("leaves").get(0).get("status").asText());

        // 已核准、尚未開始：本人可取消；取消後不再擋派工
        assertEquals(200, status(call("POST", "/api/leaves/" + l26.get("id").asText() + "/cancel", w26, Map.of())));
        crew = json(call("GET", "/api/matching/orders/ORD-2026-005/crew?teamId=team_b", admin, null)).get("data");
        for (JsonNode x : crew.get("excluded")) assertNotEquals("W026", x.get("workerId").asText());
    }

    @Test
    void startedLeaveOnlyAdminCanCancel() throws Exception {
        String w33 = worker("W033");
        LocalDate today = LocalDate.now();
        JsonNode l = json(call("POST", "/api/workers/W033/leaves", w33, leave("sick", today, null, null))).get("data");
        String id = l.get("id").asText();
        assertEquals(200, status(call("POST", "/api/leaves/" + id + "/approve", admin, Map.of())));
        assertEquals(409, status(call("POST", "/api/leaves/" + id + "/cancel", w33, Map.of())), "假期已開始，本人不能取消");
        assertEquals(200, status(call("POST", "/api/leaves/" + id + "/cancel", admin, Map.of("note", "提早回來上班"))));

        // 駁回後不能取消
        JsonNode r = json(call("POST", "/api/workers/W033/leaves", w33, leave("personal", today.plusDays(100), null, null))).get("data");
        MvcResult rej = call("POST", "/api/leaves/" + r.get("id").asText() + "/reject", admin, Map.of("note", "產季人力不足"));
        assertEquals("rejected", json(rej).get("data").get("status").asText());
        assertEquals("產季人力不足", json(rej).get("data").get("decisionNote").asText());
        assertEquals(409, status(call("POST", "/api/leaves/" + r.get("id").asText() + "/cancel", w33, Map.of())));
    }
}
