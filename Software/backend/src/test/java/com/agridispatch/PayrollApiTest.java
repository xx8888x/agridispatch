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
 * 出勤補登／修正與月結算（/api/payroll/**，production 模式）
 * 用兩個月前（已過完、工人薪資頁也會列出）的月份走完整流程：補登 → 有未下班擋結算 → 修正 → 結算 → 鎖定 → 重新開放
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:agri_payroll_test;DB_CLOSE_DELAY=-1",
        "agri.demo.enabled=false",
        "agri.security.token-secret=unit-test-token-secret-0123456789abcdef",
        "agri.security.admin-password=Admin#Pass2026"
})
@AutoConfigureMockMvc
class PayrollApiTest extends ApiTestSupport {

    @Autowired AttendanceRepository attendanceRepository;

    private static final YearMonth MONTH = YearMonth.now().minusMonths(2);
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

    private static Map<String, Object> shift(String workerId, LocalDate date, String in, String out, String reason) {
        Map<String, Object> m = new HashMap<>();
        m.put("workerId", workerId);
        m.put("workDate", date.toString());
        m.put("clockIn", in);
        m.put("clockOut", out);
        if (reason != null) m.put("reason", reason);
        return m;
    }

    private int status(MvcResult r) {
        return r.getResponse().getStatus();
    }

    private JsonNode data(MvcResult r) throws Exception {
        assertEquals(200, status(r), r.getResponse().getContentAsString());
        return json(r).get("data");
    }

    @Test
    void onlyAdmin() throws Exception {
        String w = workerToken("W008");
        assertEquals(403, status(call("GET", "/api/payroll/months", w, null)));
        assertEquals(403, status(call("POST", "/api/payroll/attendance", w, shift("W008", MONTH.atDay(3), "08:00", "17:00", "x"))));
        assertEquals(401, status(call("GET", "/api/payroll/months", null, null)));
        assertEquals(12, data(call("GET", "/api/payroll/months", admin, null)).size());
    }

    @Test
    void correctSettleLockReopen() throws Exception {
        String m = MONTH.toString();
        LocalDate day = MONTH.atDay(10);

        // 補登：8:00～17:30 → 9.5 小時、加班 1.5
        JsonNode rec = data(call("POST", "/api/payroll/attendance", admin, shift("W026", day, "08:00", "17:30", "工人手機沒電，帶班師傅證明")));
        assertEquals(9.5, rec.get("actualHours").asDouble());
        assertEquals(1.5, rec.get("overtimeHours").asDouble());
        assertTrue(rec.get("manual").asBoolean());
        assertEquals(1, rec.get("corrections").asInt());

        // 不合理的輸入
        assertEquals(409, status(call("POST", "/api/payroll/attendance", admin, shift("W026", day, "12:00", "13:00", "重複"))), "與同日出勤重疊");
        assertEquals(400, status(call("POST", "/api/payroll/attendance", admin, shift("W026", day.plusDays(1), "08:00", "17:00", null))), "原因必填");
        assertEquals(400, status(call("POST", "/api/payroll/attendance", admin, shift("W026", LocalDate.now().plusDays(1), "08:00", "17:00", "x"))), "未來日期");
        assertEquals(400, status(call("POST", "/api/payroll/attendance", admin, shift("W026", day.plusDays(1), "8點", "17:00", "x"))), "時間格式");
        assertEquals(404, status(call("POST", "/api/payroll/attendance", admin, shift("W999", day, "08:00", "17:00", "x"))));

        // 跨日：22:00 上班、隔天 06:00 下班 → 8 小時
        JsonNode night = data(call("POST", "/api/payroll/attendance", admin, shift("W008", day, "22:00", "06:00", "夜間採收")));
        assertEquals(8.0, night.get("actualHours").asDouble());

        // 忘記下班打卡（直接寫入一筆未下班紀錄）→ 擋結算
        Attendance open = attendanceRepository.save(new Attendance("W008", MONTH.atDay(12).atTime(7, 30), "枋山", 10.0));
        JsonNode ov = data(call("GET", "/api/payroll/months/" + m, admin, null));
        assertFalse(ov.get("canSettle").asBoolean());
        assertTrue(ov.get("blockers").asInt() >= 1);
        MvcResult blocked = call("POST", "/api/payroll/months/" + m + "/settle", admin, null);
        assertEquals(409, status(blocked));
        assertTrue(blocked.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8).contains("W008"));

        // 修正：補上下班時間（未下班的紀錄不給下班時間 → 400）
        assertEquals(400, status(call("PUT", "/api/payroll/attendance/" + open.getId(), admin, Map.of("reason", "忘記打下班卡"))));
        JsonNode fixed = data(call("PUT", "/api/payroll/attendance/" + open.getId(), admin, Map.of("clockOut", "16:30", "reason", "忘記打下班卡")));
        assertEquals(9.0, fixed.get("actualHours").asDouble());
        assertEquals(1, fixed.get("corrections").asInt());
        JsonNode history = data(call("GET", "/api/payroll/attendance/" + open.getId() + "/corrections", admin, null));
        assertEquals("update", history.get(0).get("action").asText());
        assertTrue(history.get(0).get("beforeClockOut").isNull());
        assertEquals("admin_tech", history.get(0).get("operator").asText());

        // 其他（種子）未下班紀錄也先修正，才能結算
        for (JsonNode a : data(call("GET", "/api/payroll/attendance?month=" + m, admin, null))) {
            if (a.get("open").asBoolean()) {
                assertEquals(200, status(call("PUT", "/api/payroll/attendance/" + a.get("id").asLong(), admin,
                        Map.of("clockOut", "23:59", "reason", "測試前清理"))));
            }
        }

        // 結算
        assertEquals(400, status(call("POST", "/api/payroll/months/" + YearMonth.now() + "/settle", admin, null)), "本月尚未結束");
        JsonNode settled = data(call("POST", "/api/payroll/months/" + m + "/settle", admin, null));
        assertTrue(settled.get("settled").asBoolean());
        assertEquals("admin_tech", settled.get("settledBy").asText());
        JsonNode w026 = null;
        for (JsonNode w : settled.get("workers")) if ("W026".equals(w.get("workerId").asText())) w026 = w;
        assertNotNull(w026, settled.toString());
        assertEquals("PS-" + m.replace("-", "") + "-W026", w026.get("slipId").asText());
        assertFalse(w026.get("simulated").asBoolean());
        assertEquals(409, status(call("POST", "/api/payroll/months/" + m + "/settle", admin, null)), "不可重複結算");

        // 結算後鎖定
        assertEquals(409, status(call("PUT", "/api/payroll/attendance/" + open.getId(), admin, Map.of("clockOut", "17:00", "reason", "再改"))));
        assertEquals(409, status(call("POST", "/api/payroll/attendance", admin, shift("W026", MONTH.atDay(20), "08:00", "12:00", "補登"))));

        // 工人薪資頁：該月顯示結算快照
        JsonNode payroll = data(call("GET", "/api/workers/W026/payroll", workerToken("W026"), null));
        JsonNode slip = null;
        for (JsonNode s : payroll.get("historicalSlips")) if (s.get("slipId").asText().equals(w026.get("slipId").asText())) slip = s;
        assertNotNull(slip, payroll.toString());
        assertEquals("已結算", slip.get("status").asText());
        assertEquals(w026.get("netPay").asInt(), slip.get("netPay").asInt());

        // 重新開放：原因必填；開放後可再修正，薪資頁回到試算
        assertEquals(400, status(call("POST", "/api/payroll/months/" + m + "/reopen", admin, Map.of())));
        JsonNode reopened = data(call("POST", "/api/payroll/months/" + m + "/reopen", admin, Map.of("reason", "補發加班費")));
        assertEquals("reopened", reopened.get("status").asText());
        assertEquals("補發加班費", reopened.get("reopenReason").asText());
        assertEquals(200, status(call("PUT", "/api/payroll/attendance/" + open.getId(), admin, Map.of("clockOut", "17:00", "reason", "補發加班費"))));
        JsonNode after = data(call("GET", "/api/workers/W026/payroll", workerToken("W026"), null));
        for (JsonNode s : after.get("historicalSlips")) assertNotEquals(w026.get("slipId").asText(), s.get("slipId").asText());
    }
}
