package com.agridispatch;

import com.agridispatch.repository.LineUserRepository;
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
}
