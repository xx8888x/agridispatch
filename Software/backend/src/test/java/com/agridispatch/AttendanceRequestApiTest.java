package com.agridispatch;

import com.agridispatch.entity.Attendance;
import com.agridispatch.repository.AttendanceRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 工人出勤補登／修正申請（/api/workers/{id}/attendance-requests、/api/payroll/attendance-requests，production 模式）
 * 工人送申請 → 後台核准（寫入出勤並留補登紀錄）／駁回；待審申請擋該月結算。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:agri_attreq_test;DB_CLOSE_DELAY=-1",
        "agri.demo.enabled=false",
        "agri.security.token-secret=unit-test-token-secret-0123456789abcdef",
        "agri.security.admin-password=Admin#Pass2026"
})
@AutoConfigureMockMvc
class AttendanceRequestApiTest extends ApiTestSupport {

    @Autowired AttendanceRepository attendanceRepository;

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

    private static Map<String, Object> req(LocalDate date, String in, String out, String reason) {
        Map<String, Object> m = new HashMap<>();
        m.put("workDate", date.toString());
        m.put("clockIn", in);
        m.put("clockOut", out);
        if (reason != null) m.put("reason", reason);
        return m;
    }

    private static int status(MvcResult r) {
        return r.getResponse().getStatus();
    }

    private JsonNode data(MvcResult r, int expected) throws Exception {
        assertEquals(expected, status(r), r.getResponse().getContentAsString());
        return json(r).get("data");
    }

    @Test
    void workerRequestsCreateThenAdminApproves() throws Exception {
        String w8 = workerToken("W008");
        String w9 = workerToken("W009");
        LocalDate day = LocalDate.now().minusDays(10);
        String url = "/api/workers/W008/attendance-requests";

        // 申請的輸入檢查
        assertEquals(400, status(call("POST", url, w8, req(day, "03:00", "05:00", null))), "原因必填");
        assertEquals(400, status(call("POST", url, w8, req(LocalDate.now().plusDays(1), "03:00", "05:00", "x"))), "未來日期");
        assertEquals(400, status(call("POST", url, w8, req(LocalDate.now().minusDays(45), "03:00", "05:00", "x"))), "超過 30 天");
        assertEquals(400, status(call("POST", url, w8, req(day, "3點", "05:00", "x"))), "時間格式");
        assertEquals(403, status(call("POST", url, w9, req(day, "03:00", "05:00", "代別人申請"))), "不能替別人申請");

        JsonNode r = data(call("POST", url, w8, req(day, "03:00", "05:00", "手機沒電，領班確認到場")), 201);
        String id = r.get("id").asText();
        assertEquals("pending", r.get("status").asText());
        assertEquals("create", r.get("kind").asText());
        assertEquals(409, status(call("POST", url, w8, req(day, "06:00", "07:00", "重複"))), "同一天只能有一筆待審");

        // 申請不改出勤
        JsonNode before = data(call("GET", "/api/payroll/attendance?month=" + YearMonth.from(day) + "&workerId=W008", admin, null), 200);
        for (JsonNode a : before) assertFalse(a.get("manual").asBoolean() && a.get("workDate").asText().equals(day.toString()));

        // 權限：工人看不到全部申請、不能審核、別人看不到我的紀錄
        assertEquals(403, status(call("GET", "/api/payroll/attendance-requests", w8, null)));
        assertEquals(403, status(call("POST", "/api/payroll/attendance-requests/" + id + "/approve", w8, Map.of())));
        assertEquals(403, status(call("GET", url, w9, null)));
        assertEquals(1, data(call("GET", url, w8, null), 200).size());
        JsonNode pending = data(call("GET", "/api/payroll/attendance-requests?status=pending", admin, null), 200);
        assertEquals(id, pending.get(0).get("id").asText());

        // 該月還有待審申請 → overview 顯示並擋結算（月份已結束時）
        JsonNode ov = data(call("GET", "/api/payroll/months/" + YearMonth.from(day), admin, null), 200);
        assertEquals(1, ov.get("pendingRequests").asInt());
        assertFalse(ov.get("canSettle").asBoolean());
        if (YearMonth.from(day).isBefore(YearMonth.now())) {
            assertEquals(409, status(call("POST", "/api/payroll/months/" + YearMonth.from(day) + "/settle", admin, null)));
        }

        // 核准時調整下班時間 → 補登一筆 manual 出勤、留補登紀錄
        JsonNode ok = data(call("POST", "/api/payroll/attendance-requests/" + id + "/approve", admin,
                Map.of("clockOut", "05:30", "note", "已向領班確認")), 200);
        assertEquals("approved", ok.get("status").asText());
        assertEquals("05:30", ok.get("clockOut").asText());
        long attId = ok.get("resultAttendanceId").asLong();
        JsonNode history = data(call("GET", "/api/payroll/attendance/" + attId + "/corrections", admin, null), 200);
        assertEquals("create", history.get(0).get("action").asText());
        assertTrue(history.get(0).get("reason").asText().contains(id));
        JsonNode after = data(call("GET", "/api/payroll/attendance?month=" + YearMonth.from(day) + "&workerId=W008", admin, null), 200);
        boolean found = false;
        for (JsonNode a : after) {
            if (a.get("id").asLong() == attId) {
                found = true;
                assertTrue(a.get("manual").asBoolean());
                assertEquals(2.5, a.get("actualHours").asDouble());
            }
        }
        assertTrue(found);
        assertEquals(409, status(call("POST", "/api/payroll/attendance-requests/" + id + "/approve", admin, Map.of())), "不可重複審核");
        assertEquals(409, status(call("POST", "/api/attendance-requests/" + id + "/cancel", w8, null)), "已核准不能取消");
    }

    @Test
    void workerRequestsUpdateForgottenClockOut() throws Exception {
        String w8 = workerToken("W008");
        String w9 = workerToken("W009");
        LocalDate day = LocalDate.now().minusDays(5);
        Attendance open = attendanceRepository.save(new Attendance("W008", day.atTime(2, 10), "枋山", 10.0));
        String url = "/api/workers/W008/attendance-requests";

        Map<String, Object> body = new HashMap<>();
        body.put("attendanceId", open.getId());
        body.put("clockOut", "02:55");
        body.put("reason", "忘記打下班卡");
        assertEquals(403, status(call("POST", "/api/workers/W009/attendance-requests", w9, body)), "不能修正別人的出勤");
        assertEquals(400, status(call("POST", url, w8, Map.of("attendanceId", open.getId(), "reason", "忘記下班"))), "未下班的出勤須填下班時間");

        JsonNode r = data(call("POST", url, w8, body), 201);
        assertEquals("update", r.get("kind").asText());
        assertEquals(day.toString(), r.get("workDate").asText());
        assertEquals("02:10", r.get("clockIn").asText(), "上班時間沿用原值");
        assertNull(attendanceRepository.findById(open.getId()).orElseThrow().getClockOutTime());
        assertEquals(409, status(call("POST", url, w8, body)), "同一筆出勤只能有一筆待審");

        JsonNode ok = data(call("POST", "/api/payroll/attendance-requests/" + r.get("id").asText() + "/approve", admin, Map.of()), 200);
        assertEquals(open.getId(), ok.get("resultAttendanceId").asLong());
        assertNotNull(attendanceRepository.findById(open.getId()).orElseThrow().getClockOutTime());
        assertEquals(400, status(call("POST", url, w8, body)), "時間沒有變更");
    }

    @Test
    void rejectNeedsNoteAndWorkerCanCancel() throws Exception {
        String w8 = workerToken("W008");
        LocalDate day = LocalDate.now().minusDays(7);
        String url = "/api/workers/W008/attendance-requests";

        String id1 = data(call("POST", url, w8, req(day, "03:10", "04:00", "漏打卡")), 201).get("id").asText();
        assertEquals(400, status(call("POST", "/api/payroll/attendance-requests/" + id1 + "/reject", admin, Map.of())), "駁回須填原因");
        JsonNode rej = data(call("POST", "/api/payroll/attendance-requests/" + id1 + "/reject", admin, Map.of("note", "查無到場紀錄")), 200);
        assertEquals("rejected", rej.get("status").asText());
        assertEquals("查無到場紀錄", rej.get("decisionNote").asText());
        assertEquals(409, status(call("POST", "/api/payroll/attendance-requests/" + id1 + "/approve", admin, Map.of())));

        // 駁回後可重新申請；待審時本人可取消，取消後出勤與月結算不受影響
        String id2 = data(call("POST", url, w8, req(day, "03:10", "04:00", "補充說明後重送")), 201).get("id").asText();
        JsonNode cancelled = data(call("POST", "/api/attendance-requests/" + id2 + "/cancel", w8, null), 200);
        assertEquals("cancelled", cancelled.get("status").asText());
        JsonNode ov = data(call("GET", "/api/payroll/months/" + YearMonth.from(day), admin, null), 200);
        assertEquals(0, ov.get("pendingRequests").asInt());
    }
}
