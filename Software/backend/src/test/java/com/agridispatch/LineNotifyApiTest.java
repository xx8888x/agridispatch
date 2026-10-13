package com.agridispatch;

import com.agridispatch.entity.Order;
import com.agridispatch.repository.LineUserRepository;
import com.agridispatch.repository.OrderRepository;
import com.fasterxml.jackson.databind.JsonNode;
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
 * LINE 主動推播（規格 文件/07_系統分析及文件/05_LINE官方帳號拆分規格.md 第柒章）：
 * 推給誰（已綁定、未封鎖、出工名單／訂單農場主）、依語言、工作者帳號不 broadcast。
 * LINE Bot 以錄製型替身取代，同步執行以便檢查呼叫內容。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:agri_line_notify_test;DB_CLOSE_DELAY=-1",
        "agri.demo.enabled=true",
        "agri.security.token-secret=unit-test-token-secret-0123456789abcdef",
        "agri.payment.provider=mock",
        "agri.line.notify.async=false",
        "agri.line.notify.worker-hours=00:00-24:00"
})
@AutoConfigureMockMvc
@Import(RecordingLineBotClient.Config.class)
class LineNotifyApiTest extends ApiTestSupport {

    @Autowired RecordingLineBotClient bot;
    @Autowired LineUserRepository lineUserRepository;
    @Autowired OrderRepository orderRepository;

    static final String FARMER_UID = "U" + "f".repeat(31) + "1";
    static final String VN_UID = "U" + "a".repeat(31) + "1";
    static final String ID_UID = "U" + "b".repeat(31) + "1";

    private void bind(String login, String channel, String uid) throws Exception {
        MvcResult r = call("POST", "/api/line/bind", login(login, "123456"), Map.of("channel", channel, "idToken", "mock:" + uid));
        assertEquals(200, r.getResponse().getStatus(), r.getResponse().getContentAsString());
    }

    /** ORD-2026-001：F001 的訂單，出工名單 W001（越南）、W006（印尼）、W002（未綁定） */
    private void prepareOrder(String status, LocalDate day) {
        Order o = orderRepository.findById("ORD-2026-001").orElseThrow();
        o.setFarmerId("F001");
        o.setStatus(status);
        o.setStartDate(day);
        o.setEndDate(day);
        o.setCrewWorkerIds("W001,W006,W002");
        orderRepository.save(o);
    }

    @BeforeEach
    void setUp() throws Exception {
        lineUserRepository.deleteAll();
        bind("farmer", "farmer", FARMER_UID);
        bind("W001", "worker", VN_UID);
        bind("W006", "worker", ID_UID);
        bot.clear();
    }

    @Test
    void tomorrowTaskGoesToBoundCrewInTheirLanguage() throws Exception {
        prepareOrder("confirmed", LocalDate.now().plusDays(1));
        MvcResult r = call("POST", "/api/line/notify/tomorrow", login("admin_tech", "admin888"), Map.of());
        assertEquals(200, r.getResponse().getStatus());
        JsonNode s = json(r);
        assertTrue(s.get("orders").asInt() >= 1);

        Map<String, String> to = bot.recipients("worker", "tomorrow_task");
        assertEquals("vi", to.get(VN_UID));
        assertEquals("id", to.get(ID_UID));
        assertFalse(to.containsKey(FARMER_UID));   // 農場主不在工作者帳號名單
        assertEquals(2, to.size());                // W002 未綁定不推
        assertTrue(bot.of("farmer", "tomorrow_task").isEmpty());
        Map<String, Object> p = bot.of("worker", "tomorrow_task").stream()
                .filter(c -> "ORD-2026-001".equals(c.params().get("orderId"))).findFirst().orElseThrow().params();
        assertEquals(LocalDate.now().plusDays(1).toString(), p.get("date"));
    }

    @Test
    void blockedUserIsExcluded() throws Exception {
        prepareOrder("confirmed", LocalDate.now().plusDays(1));
        var u = lineUserRepository.findByChannelAndLineUserId("worker", ID_UID).orElseThrow();
        u.setFollowed("N");
        lineUserRepository.save(u);
        call("POST", "/api/line/notify/tomorrow", login("admin_tech", "admin888"), Map.of());
        Map<String, String> to = bot.recipients("worker", "tomorrow_task");
        assertTrue(to.containsKey(VN_UID));
        assertFalse(to.containsKey(ID_UID));
    }

    @Test
    void orderStatusChangeNotifiesOwnerFarmer() throws Exception {
        prepareOrder("in_progress", LocalDate.now());
        MvcResult r = call("PUT", "/api/orders/ORD-2026-001/status", login("admin_tech", "admin888"), Map.of("status", "completed"));
        assertEquals(200, r.getResponse().getStatus(), r.getResponse().getContentAsString());
        List<RecordingLineBotClient.Call> calls = bot.of("farmer", "order_status");
        assertEquals(1, calls.size());
        assertEquals("completed", calls.get(0).params().get("status"));
        assertEquals("ORD-2026-001", calls.get(0).params().get("orderId"));
        assertEquals(List.of(FARMER_UID), calls.get(0).recipients().stream().map(m -> m.get("lineUserId")).toList());
        // 同狀態再送一次：沒有變更，不通知
        call("PUT", "/api/orders/ORD-2026-001/status", login("admin_tech", "admin888"), Map.of("status", "completed"));
        assertEquals(1, bot.of("farmer", "order_status").size());
    }

    @Test
    void depositPaidNotifiesOwnerFarmer() throws Exception {
        Order o = orderRepository.findById("ORD-2026-014").orElseThrow(); // F001、待付定金
        o.setFarmerId("F001");
        o.setStatus("pending");
        o.setDepositPaid(false);
        orderRepository.save(o);
        String farmer = login("farmer", "123456");
        JsonNode checkout = json(call("POST", "/api/payments/checkout", farmer, Map.of("orderId", "ORD-2026-014")));
        String txId = checkout.path("data").path("transactionId").asText(checkout.path("transactionId").asText());
        MvcResult r = call("POST", "/api/payments/" + txId + "/mock-confirm", farmer, null);
        assertEquals(200, r.getResponse().getStatus(), r.getResponse().getContentAsString());
        assertEquals(Map.of(FARMER_UID, "zh"), bot.recipients("farmer", "deposit_paid"));
    }

    @Test
    void weatherAlertNotifiesFarmerAndCrewButNotForNormalWeather() throws Exception {
        prepareOrder("in_progress", LocalDate.now());
        String admin = login("admin_tech", "admin888");
        call("POST", "/api/dispatch/weather-scenario", admin, Map.of("scenarioId", "sunny"));
        bot.clear();

        call("POST", "/api/dispatch/weather-scenario", admin, Map.of("scenarioId", "heavy_rain"));
        assertTrue(bot.recipients("farmer", "weather_farmer").containsKey(FARMER_UID));
        Map<String, String> crew = bot.recipients("worker", "weather_worker");
        assertEquals("vi", crew.get(VN_UID));
        assertEquals("id", crew.get(ID_UID));
        assertNotNull(bot.of("worker", "weather_worker").get(0).params().get("indoor"));

        // 同一情境重複切換、切回晴天：不再通知
        bot.clear();
        call("POST", "/api/dispatch/weather-scenario", admin, Map.of("scenarioId", "heavy_rain"));
        call("POST", "/api/dispatch/weather-scenario", admin, Map.of("scenarioId", "sunny"));
        assertTrue(bot.calls.isEmpty());
    }

    @Test
    void farmerBroadcastOnlyByAdmin() throws Exception {
        assertEquals(403, call("POST", "/api/line/notify/farmer-broadcast", login("farmer", "123456"), Map.of("text", "早鳥開跑")).getResponse().getStatus());
        MvcResult r = call("POST", "/api/line/notify/farmer-broadcast", login("admin_tech", "admin888"), Map.of("text", "早鳥開跑"));
        assertEquals(200, r.getResponse().getStatus());
        assertTrue(json(r).get("success").asBoolean());
        assertEquals(List.of("早鳥開跑"), bot.broadcasts);
        assertEquals(400, call("POST", "/api/line/notify/farmer-broadcast", login("admin_tech", "admin888"), Map.of("text", " ")).getResponse().getStatus());
    }

    @Test
    void salaryPaidGoesToEachWorkerWithTheirOwnAmount() throws Exception {
        String admin = login("admin_tech", "admin888");
        Map<String, Object> body = Map.of("month", "2026-09", "payDate", "2026-10-05", "items", List.of(
                Map.of("workerId", "W001", "netPay", 36850),
                Map.of("workerId", "w006", "netPay", 35200),
                Map.of("workerId", "W002", "netPay", 30000),
                Map.of("workerId", "W999", "netPay", 30000)));
        MvcResult r = call("POST", "/api/line/notify/salary-paid", admin, body);
        assertEquals(200, r.getResponse().getStatus(), r.getResponse().getContentAsString());
        JsonNode s = json(r);
        assertEquals(2, s.get("workerRecipients").asInt());
        assertEquals("W002", s.get("notReached").get(0).asText());
        assertEquals("W999", s.get("unknownWorkers").get(0).asText());

        // 一人一則，金額是各自的實發數字，語言依本人
        List<RecordingLineBotClient.Call> calls = bot.of("worker", "salary_paid");
        assertEquals(2, calls.size());
        for (RecordingLineBotClient.Call c : calls) {
            assertEquals(1, c.recipients().size());
            String uid = c.recipients().get(0).get("lineUserId");
            assertEquals(uid.equals(VN_UID) ? 36850L : 35200L, ((Number) c.params().get("netPay")).longValue());
            assertEquals(uid.equals(VN_UID) ? "vi" : "id", c.recipients().get(0).get("lang"));
            assertEquals("2026-09", c.params().get("month"));
            assertEquals("2026-10-05", c.params().get("payDate"));
        }

        assertEquals(403, call("POST", "/api/line/notify/salary-paid", login("W001", "123456"), body).getResponse().getStatus());
        for (Map<String, Object> bad : List.<Map<String, Object>>of(
                Map.of("month", "2026-13", "items", List.of(Map.of("workerId", "W001", "netPay", 1))),
                Map.of("month", "2026-09", "items", List.of()),
                Map.of("month", "2026-09", "items", "W001"),
                Map.of("month", "2026-09", "items", List.of("W001")),
                Map.of("month", "2026-09", "items", List.of(Map.of("workerId", "W001", "netPay", -1))),
                Map.of("month", "2026-09", "payDate", "10/05", "items", List.of(Map.of("workerId", "W001", "netPay", 1))),
                Map.of("month", "2026-09", "items", List.of(Map.of("workerId", "W001", "netPay", 1), Map.of("workerId", "w001", "netPay", 2))))) {
            assertEquals(400, call("POST", "/api/line/notify/salary-paid", admin, bad).getResponse().getStatus(), bad.toString());
        }
    }

    @Test
    void notifyEndpointsAreAdminOnly() throws Exception {
        assertEquals(403, call("POST", "/api/line/notify/tomorrow", login("W001", "123456"), Map.of()).getResponse().getStatus());
        assertEquals(403, call("GET", "/api/line/notify/status", login("farmer", "123456"), null).getResponse().getStatus());
        MvcResult r = call("GET", "/api/line/notify/status", login("admin_tech", "admin888"), null);
        assertEquals("00:00-24:00", json(r).get("workerHours").asText());
        assertTrue(json(r).get("inWorkerHours").asBoolean());
    }
}
