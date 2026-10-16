package com.agridispatch;

import com.agridispatch.repository.LineUserRepository;
import com.agridispatch.repository.OrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 請假審核結果的 LINE 通知：核准、駁回、合作社取消已核准的假 → 推給請假的工人本人；本人取消不通知。
 * 另外領班（同工班領班）、受影響的農場主也要收到通知（申請、核准、駁回、取消已核准的假）。
 * LINE Bot 以錄製型替身取代，同步執行以便檢查呼叫內容。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:agri_leave_notify_test;DB_CLOSE_DELAY=-1",
        "agri.demo.enabled=true",
        "agri.security.token-secret=unit-test-token-secret-0123456789abcdef",
        "agri.line.notify.async=false",
        "agri.line.notify.worker-hours=00:00-24:00"
})
@AutoConfigureMockMvc
@Import(RecordingLineBotClient.Config.class)
class LeaveNotifyTest extends ApiTestSupport {

    @Autowired RecordingLineBotClient bot;
    @Autowired LineUserRepository lineUserRepository;
    @Autowired OrderRepository orderRepository;

    static final String W026_UID = "U" + "c".repeat(31) + "6";

    private String admin;
    private String w026;

    @BeforeEach
    void setUp() throws Exception {
        lineUserRepository.deleteAll();
        w026 = login("W026", "123456");
        MvcResult b = call("POST", "/api/line/bind", w026, Map.of("channel", "worker", "idToken", "mock:" + W026_UID));
        assertEquals(200, b.getResponse().getStatus(), b.getResponse().getContentAsString());
        admin = login("admin_tech", "admin888");
        bot.clear();
    }

    private String apply(LocalDate day, String slot) throws Exception {
        MvcResult r = call("POST", "/api/workers/W026/leaves", w026,
                Map.of("leaveType", "personal", "startDate", day.toString(), "timeSlot", slot));
        assertEquals(201, r.getResponse().getStatus(), r.getResponse().getContentAsString());
        return json(r).get("data").get("id").asText();
    }

    @Test
    void decisionsAreSentToTheWorker() throws Exception {
        LocalDate d = LocalDate.now().plusDays(50);
        String a = apply(d, "full_day");
        assertTrue(bot.of("worker", "leave_decided").isEmpty(), "申請時不通知工人");

        assertEquals(200, call("POST", "/api/leaves/" + a + "/approve", admin, Map.of("note", "請領班安排代班")).getResponse().getStatus());
        List<RecordingLineBotClient.Call> calls = bot.of("worker", "leave_decided");
        assertEquals(1, calls.size());
        Map<String, Object> p = calls.get(0).params();
        assertEquals(a, p.get("leaveId"));
        assertEquals("approved", p.get("status"));
        assertEquals("事假", p.get("leaveType"));
        assertEquals("personal", p.get("leaveTypeCode"));
        assertEquals(d.toString(), p.get("period"));
        assertEquals("請領班安排代班", p.get("note"));
        assertEquals(W026_UID, calls.get(0).recipients().get(0).get("lineUserId"));

        String r = apply(d.plusDays(1), "morning");
        assertEquals(200, call("POST", "/api/leaves/" + r + "/reject", admin, Map.of("note", "產季人力不足")).getResponse().getStatus());
        calls = bot.of("worker", "leave_decided");
        assertEquals(2, calls.size());
        assertEquals("rejected", calls.get(1).params().get("status"));
        assertEquals(d.plusDays(1) + " 上午", calls.get(1).params().get("period"));
        assertEquals("產季人力不足", calls.get(1).params().get("note"));

        // 本人取消待審核的假：不通知
        String c = apply(d.plusDays(2), "full_day");
        assertEquals(200, call("POST", "/api/leaves/" + c + "/cancel", w026, Map.of()).getResponse().getStatus());
        assertEquals(2, bot.of("worker", "leave_decided").size());

        // 合作社取消已核准的假：通知
        assertEquals(200, call("POST", "/api/leaves/" + a + "/cancel", admin, Map.of("note", "訂單取消，回復排班")).getResponse().getStatus());
        calls = bot.of("worker", "leave_decided");
        assertEquals(3, calls.size());
        assertEquals("cancelled", calls.get(2).params().get("status"));
    }

    @Test
    void foremanAndFarmerAreNotifiedToo() throws Exception {
        String farmerUid = "U" + "f".repeat(31) + "1";
        assertEquals(200, call("POST", "/api/line/bind", login("farmer", "123456"),
                Map.of("channel", "farmer", "idToken", "mock:" + farmerUid)).getResponse().getStatus());
        LocalDate d = LocalDate.now().plusDays(120);
        assertEquals(200, call("PUT", "/api/orders/ORD-2026-005", admin,
                Map.of("startDate", d.toString(), "endDate", d.plusDays(2).toString())).getResponse().getStatus());
        var order = orderRepository.findById("ORD-2026-005").orElseThrow();
        order.setFarmerId("F001");
        orderRepository.save(order);
        MvcResult assigned = call("PUT", "/api/matching/orders/ORD-2026-005", admin,
                Map.of("teamId", "team_b", "crew", List.of("W026", "W033")));
        assertEquals(200, assigned.getResponse().getStatus(), assigned.getResponse().getContentAsString());
        bot.clear();

        // W033 請假：領班 W026 收到「申請」，此時還沒核准，農場主不通知
        MvcResult r = call("POST", "/api/workers/W033/leaves", admin,
                Map.of("leaveType", "sick", "startDate", d.plusDays(1).toString()));
        assertEquals(201, r.getResponse().getStatus(), r.getResponse().getContentAsString());
        String id = json(r).get("data").get("id").asText();
        List<RecordingLineBotClient.Call> team = bot.of("worker", "leave_team");
        assertEquals(1, team.size());
        assertEquals("pending", team.get(0).params().get("status"));
        assertEquals(W026_UID, team.get(0).recipients().get(0).get("lineUserId"));
        assertTrue(bot.of("farmer", "leave_farmer").isEmpty(), "未核准不通知農場主");

        // 核准：領班收到核准，農場主收到受影響訂單
        assertEquals(200, call("POST", "/api/leaves/" + id + "/approve", admin, Map.of()).getResponse().getStatus());
        team = bot.of("worker", "leave_team");
        assertEquals(2, team.size());
        assertEquals("approved", team.get(1).params().get("status"));
        List<RecordingLineBotClient.Call> farmer = bot.of("farmer", "leave_farmer");
        assertEquals(1, farmer.size());
        assertEquals(List.of("ORD-2026-005"), farmer.get(0).params().get("orders"));
        assertEquals("approved", farmer.get(0).params().get("status"));
        assertEquals(farmerUid, farmer.get(0).recipients().get(0).get("lineUserId"));

        // 取消已核准的假：領班與農場主都收到取消
        assertEquals(200, call("POST", "/api/leaves/" + id + "/cancel", admin, Map.of("note", "回復排班")).getResponse().getStatus());
        assertEquals("cancelled", bot.of("worker", "leave_team").get(2).params().get("status"));
        farmer = bot.of("farmer", "leave_farmer");
        assertEquals(2, farmer.size());
        assertEquals("cancelled", farmer.get(1).params().get("status"));

        // 沒有重疊訂單的假：農場主不通知，領班照常
        MvcResult other = call("POST", "/api/workers/W033/leaves", admin,
                Map.of("leaveType", "personal", "startDate", d.plusDays(30).toString()));
        String oid = json(other).get("data").get("id").asText();
        assertEquals(200, call("POST", "/api/leaves/" + oid + "/approve", admin, Map.of()).getResponse().getStatus());
        assertEquals(2, bot.of("farmer", "leave_farmer").size());
        assertEquals(5, bot.of("worker", "leave_team").size());
    }
}
