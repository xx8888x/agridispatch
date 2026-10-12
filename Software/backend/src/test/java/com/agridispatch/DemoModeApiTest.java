package com.agridispatch;

import com.agridispatch.entity.Attendance;
import com.agridispatch.entity.Worker;
import com.agridispatch.repository.AttendanceRepository;
import com.agridispatch.repository.DispatchTeamRepository;
import com.agridispatch.repository.OrderRepository;
import com.agridispatch.repository.TeamRatingRepository;
import com.agridispatch.repository.WeatherStateRepository;
import com.agridispatch.repository.WorkerRepository;
import com.agridispatch.service.DispatchService;
import com.agridispatch.service.SeedService;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * demo 模式（agri.demo.enabled=true、mock 金流）整合測試：
 * demo-login / quick-profiles / 示範帳號登入 / 付款冪等 / 打卡 / 種子補缺 / 天候狀態讀回
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:agri_demo_test;DB_CLOSE_DELAY=-1",
        "agri.demo.enabled=true",
        "agri.security.token-secret=unit-test-token-secret-0123456789abcdef",
        "agri.payment.provider=mock"
})
@AutoConfigureMockMvc
class DemoModeApiTest extends ApiTestSupport {

    @Autowired WorkerRepository workerRepository;
    @Autowired AttendanceRepository attendanceRepository;
    @Autowired OrderRepository orderRepository;
    @Autowired SeedService seedService;
    @Autowired DispatchService dispatchService;
    @Autowired DispatchTeamRepository teamRepository;
    @Autowired WeatherStateRepository weatherStateRepository;
    @Autowired TeamRatingRepository teamRatingRepository;

    // ------------------------------------------------------------------ demo 登入

    @Test
    void demoLoginWorksAndActsAsAdmin() throws Exception {
        MvcResult r = call("POST", "/api/auth/demo-login", null, Map.of("role", "farmer"));
        assertEquals(200, r.getResponse().getStatus());
        JsonNode body = json(r);
        assertTrue(body.get("success").asBoolean());
        assertTrue(body.get("user").get("demo").asBoolean());
        assertEquals("farmer", body.get("user").get("role").asText());
        assertEquals("F001", body.get("user").get("refId").asText());
        String token = body.get("token").asText();

        // demo token 在 demo 模式視同 admin_tech
        MvcResult workers = call("GET", "/api/workers", token, null);
        assertEquals(200, workers.getResponse().getStatus());
        assertFalse(workers.getResponse().getContentAsString(StandardCharsets.UTF_8).contains("AD88123451"));

        MvcResult me = call("GET", "/api/auth/me", token, null);
        assertTrue(json(me).get("user").get("demo").asBoolean());

        JsonNode local = json(call("POST", "/api/auth/demo-login", null, Map.of("role", "worker_local")));
        assertEquals("foreman", local.get("user").get("subRole").asText());
        assertEquals(400, call("POST", "/api/auth/demo-login", null, Map.of("role", "root")).getResponse().getStatus());

        MvcResult cfg = call("GET", "/api/public/config", null, null);
        assertTrue(json(cfg).get("demoEnabled").asBoolean());
        assertEquals("mock", json(cfg).get("paymentProvider").asText());
    }

    @Test
    void quickProfilesHaveNoSecrets() throws Exception {
        MvcResult r = call("GET", "/api/auth/quick-profiles", null, null);
        assertEquals(200, r.getResponse().getStatus());
        String body = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertFalse(body.contains("password"));
        assertFalse(body.contains("123456"));
        assertFalse(body.contains("\"arc\""));
        assertFalse(body.contains("passport"));
        assertFalse(body.contains("AD88123451"));
    }

    @Test
    void demoAccountsLoginByAllIdentifiers() throws Exception {
        assertNotNull(login("W001", "123456"));
        assertNotNull(login("w001", "123456"));
        assertNotNull(login("AD88123451", "123456"));     // ARC
        assertNotNull(login("C9823101", "123456"));       // 護照（與 seed 一致）
        MvcResult labor = call("POST", "/api/auth/login", null, Map.of("identifier", "0975-123-456", "password", "123456"));
        assertEquals(200, labor.getResponse().getStatus());
        JsonNode user = json(labor).get("user");
        assertEquals("W030", user.get("refId").asText());
        assertEquals("labor", user.get("subRole").asText());
        assertTrue(user.get("roleDesc").asText().contains("29,500"), "最低工資已更新為 29,500");
        assertFalse(labor.getResponse().getContentAsString(StandardCharsets.UTF_8).contains("passwordHash"));

        assertNotNull(login("admin_tech", "admin888"));
        assertNotNull(login("farmer", "123456"));
        assertEquals(401, call("POST", "/api/auth/login", null,
                Map.of("identifier", "W001", "password", "wrong")).getResponse().getStatus());
    }

    // ------------------------------------------------------------------ 付款

    private String createOrder(String token, String plan) throws Exception {
        MvcResult r = call("POST", "/api/orders", token,
                Map.of("farmerName", "付款測試", "cropId", "taoyuan_veg", "area", 5.0, "plan", plan));
        assertEquals(201, r.getResponse().getStatus(), r.getResponse().getContentAsString(StandardCharsets.UTF_8));
        return json(r).get("data").get("id").asText();
    }

    @Test
    void mockPaymentIsIdempotent() throws Exception {
        String farmer = login("farmer", "123456");
        String orderId = createOrder(farmer, "early_bird");
        assertEquals("F001", orderRepository.findById(orderId).orElseThrow().getFarmerId(), "farmer 下單自動綁定 farmerId");
        assertEquals("pending", orderRepository.findById(orderId).orElseThrow().getStatus());

        JsonNode c1 = json(call("POST", "/api/payments/checkout", farmer, Map.of("orderId", orderId, "amount", 1)));
        assertEquals("PENDING", c1.get("status").asText());
        assertEquals(6300, c1.get("amount").asInt());
        String txId = c1.get("transactionId").asText();
        JsonNode c2 = json(call("POST", "/api/payments/checkout", farmer, Map.of("orderId", orderId)));
        assertEquals(txId, c2.get("transactionId").asText(), "未付款前重複結帳沿用同一筆交易");

        MvcResult confirm = call("POST", "/api/payments/" + txId + "/mock-confirm", farmer, null);
        assertEquals(200, confirm.getResponse().getStatus());
        JsonNode paid = json(confirm);
        assertEquals("SUCCESS", paid.get("status").asText());
        assertEquals(6300, paid.get("amountPaid").asInt());
        assertEquals("confirmed", paid.get("orderStatus").asText(), "pending → confirmed");
        assertFalse(paid.get("alreadyPaid").asBoolean());

        JsonNode again = json(call("POST", "/api/payments/checkout", farmer, Map.of("orderId", orderId)));
        assertTrue(again.get("alreadyPaid").asBoolean());
        assertEquals(txId, again.get("transactionId").asText(), "已付款回傳既有交易");
        JsonNode confirmAgain = json(call("POST", "/api/payments/" + txId + "/mock-confirm", farmer, null));
        assertTrue(confirmAgain.get("alreadyPaid").asBoolean());

        JsonNode status = json(call("GET", "/api/payments/" + orderId + "/status", farmer, null)).get("data");
        assertTrue(status.get("depositPaid").asBoolean());
        assertEquals(1, status.get("transactions").size());
    }

    @Test
    void cancelledOrderRejectsPayment() throws Exception {
        String admin = login("admin_tech", "admin888");
        MvcResult r = call("POST", "/api/payments/checkout", admin, Map.of("orderId", "ORD-2026-010")); // seed：cancelled
        assertEquals(409, r.getResponse().getStatus());
        assertTrue(json(r).get("error").asText().contains("取消"));

        String farmer = login("farmer", "123456");
        String orderId = createOrder(farmer, "early_bird");
        String txId = json(call("POST", "/api/payments/checkout", farmer, Map.of("orderId", orderId))).get("transactionId").asText();
        assertEquals(200, call("DELETE", "/api/orders/" + orderId, farmer, null).getResponse().getStatus(), "farmer 取消自己的訂單");
        assertEquals("cancelled", orderRepository.findById(orderId).orElseThrow().getStatus());
        assertEquals(409, call("POST", "/api/payments/" + txId + "/mock-confirm", farmer, null).getResponse().getStatus());
        assertFalse(orderRepository.findById(orderId).orElseThrow().isDepositPaid());
    }

    @Test
    void zeroDepositPlanCannotBeCharged() throws Exception {
        String farmer = login("farmer", "123456");
        String orderId = createOrder(farmer, "hourly_flex");
        assertEquals(0, orderRepository.findById(orderId).orElseThrow().getDepositAmount());
        assertEquals("confirmed", orderRepository.findById(orderId).orElseThrow().getStatus(), "免定金方案直接 confirmed");
        MvcResult r = call("POST", "/api/payments/checkout", farmer, Map.of("orderId", orderId));
        assertEquals(400, r.getResponse().getStatus());
        assertFalse(r.getResponse().getContentAsString(StandardCharsets.UTF_8).contains("4480"));
        assertFalse(orderRepository.findById(orderId).orElseThrow().isDepositPaid());
    }

    // ------------------------------------------------------------------ 打卡

    @Test
    void clockInTwiceIsRejected() throws Exception {
        String admin = login("admin_tech", "admin888");
        MvcResult first = call("POST", "/api/attendance/clock-in", admin, Map.of("workerId", "W002", "location", "八德"));
        assertEquals(200, first.getResponse().getStatus());
        MvcResult second = call("POST", "/api/workers/W002/clock-in", admin, Map.of());
        assertEquals(409, second.getResponse().getStatus());
        assertEquals(1, attendanceRepository.findByWorkerIdAndClockOutTimeIsNull("W002").size(), "同一工人不得有兩筆未下班紀錄");

        assertEquals(404, call("POST", "/api/workers/W999/clock-in", admin, Map.of()).getResponse().getStatus(), "查無工人回 404");
        assertEquals(404, call("GET", "/api/workers/W999/attendance", admin, null).getResponse().getStatus());
        assertEquals(409, call("POST", "/api/workers/W004/clock-out", admin, Map.of()).getResponse().getStatus(),
                "沒有上班紀錄不可捏造工時");
    }

    @Test
    void clockOutClosesOpenShiftAcrossDays() throws Exception {
        LocalDateTime yesterday = LocalDateTime.now().minusDays(1).withHour(22).withMinute(0).withSecond(0).withNano(0);
        attendanceRepository.save(new Attendance("W003", yesterday, "夜間搶收", 3.0));

        String token = login("W003", "123456");
        MvcResult r = call("POST", "/api/attendance/clock-out", token, Map.of("workerId", "W003", "foremanSignature", "阮文雄"));
        assertEquals(200, r.getResponse().getStatus(), r.getResponse().getContentAsString(StandardCharsets.UTF_8));
        JsonNode data = json(r).get("data");
        assertEquals("completed", data.get("status").asText());
        assertEquals(yesterday.toLocalDate().toString(), data.get("workDate").asText(), "紀錄仍屬上班當日");
        assertTrue(data.get("actualHours").asDouble() > 0);
        assertTrue(attendanceRepository.findByWorkerIdAndClockOutTimeIsNull("W003").isEmpty());

        assertEquals(403, call("POST", "/api/attendance/clock-out", token, Map.of("workerId", "W001")).getResponse().getStatus(),
                "worker 只能替自己打卡");
    }

    // ------------------------------------------------------------------ 種子補缺 / 天候讀回

    @Test
    void brochureResetRestoresStandardDmsAndKeepsCustomOnes() throws Exception {
        String token = json(call("POST", "/api/auth/demo-login", null, Map.of("role", "admin_tech"))).get("token").asText();
        // 先確保 4 大標準 DM 存在，再竄改其中一筆、另建一筆自訂 DM
        assertEquals(200, call("POST", "/api/brochures/seed", token, null).getResponse().getStatus());
        JsonNode standard = json(call("GET", "/api/brochures/dm-mango-2026", null, null)).get("data");
        String originalTitle = standard.get("title").asText();
        ((com.fasterxml.jackson.databind.node.ObjectNode) standard).put("title", "被改過的標題");
        assertEquals(200, call("PUT", "/api/brochures/dm-mango-2026", token, standard.toString()).getResponse().getStatus());
        assertEquals(201, call("POST", "/api/brochures", token,
                Map.of("id", "dm-custom-test", "title", "自訂 DM", "status", "draft")).getResponse().getStatus());

        assertEquals(200, call("POST", "/api/brochures/seed", token, null).getResponse().getStatus());

        assertEquals(originalTitle, json(call("GET", "/api/brochures/dm-mango-2026", null, null)).get("data").get("title").asText(),
                "重置應覆寫標準 DM");
        assertEquals(200, call("GET", "/api/brochures/dm-custom-test", null, null).getResponse().getStatus(),
                "重置不可刪除自訂 DM");
    }

    @Test
    void seedFillsMissingIdsWithoutOverwriting() {
        Worker w001 = workerRepository.findById("W001").orElseThrow();
        w001.setCurrentTask("自訂任務（不可被種子覆蓋）");
        workerRepository.save(w001);
        workerRepository.deleteById("W026");

        seedService.seedAll(false);

        assertTrue(workerRepository.existsById("W026"), "缺少的 ID 應補回");
        assertTrue(workerRepository.existsById("W030"));
        assertEquals("自訂任務（不可被種子覆蓋）", workerRepository.findById("W001").orElseThrow().getCurrentTask());
        List<Worker> locals = workerRepository.findAllByArcNumber("本國身分證");
        assertTrue(locals.isEmpty(), "本國工 ARC 佔位字已清為 null");
        assertEquals(29_500, workerRepository.findById("W002").orElseThrow().getBaseSalary());
    }

    @Test
    void weatherStateRestoredFromDb() {
        dispatchService.applyWeatherScenario("typhoon");
        DispatchService fresh = new DispatchService(teamRepository, weatherStateRepository,
                workerRepository, orderRepository, teamRatingRepository);
        assertEquals("cloudy", fresh.getCurrentScenarioId());
        fresh.restoreStateFromDb();
        assertEquals("typhoon", fresh.getCurrentScenarioId());
        assertTrue(fresh.isRainMode());
        assertEquals("danger", fresh.getAlertLevel());
        dispatchService.applyWeatherScenario("cloudy");
    }

    @Test
    void lineWebhookGoneInDemo() throws Exception {
        MvcResult r = mvc.perform(post("/api/line/webhook").contentType(MediaType.APPLICATION_JSON)
                .content("{\"events\":[{\"type\":\"message\",\"userId\":\"U1\",\"message\":{\"text\":\"查訂單\"}}]}")
                .header("X-Line-Signature", "mock_signature")).andReturn();
        assertEquals(410, r.getResponse().getStatus(), "舊 webhook 已停用，demo 模式同樣回 410");
        assertEquals("GONE", json(r).get("error").asText());
        assertFalse(json(r).has("replies"));
    }

    @Test
    void payrollUsesWorkerDataAndIsMarkedSimulated() throws Exception {
        String token = login("W030", "123456");
        JsonNode data = json(call("GET", "/api/workers/W030/payroll", token, null)).get("data");
        assertTrue(data.get("simulated").asBoolean());
        assertEquals(29_500, data.get("currentMonth").get("baseSalary").asInt());
        assertEquals(403, call("GET", "/api/workers/W001/payroll", token, null).getResponse().getStatus());
    }

    @Test
    void demoFarmerTokenOnlySeesOwnLegalizeRosterAndCanReset() throws Exception {
        // demo-login 的 token 在 demo 模式視同 admin，但農場主身分仍只看自己的黑轉白清冊
        String farmer = json(call("POST", "/api/auth/demo-login", null, Map.of("role", "farmer"))).get("token").asText();
        JsonNode rows = json(call("GET", "/api/legalize/roster", farmer, null)).get("data");
        assertEquals(7, rows.size());
        for (JsonNode w : rows) assertEquals("F001", w.get("farmerId").asText());
        assertEquals(403, call("GET", "/api/legalize/summary", farmer, null).getResponse().getStatus());

        // 新增不必帶 farmerId、重設回 7 筆（demo 才有）
        JsonNode added = json(call("POST", "/api/legalize/roster", farmer, Map.of("type", "local", "name", "阿財", "daysPerWeek", 2))).get("data");
        assertEquals("F001", added.get("farmerId").asText());
        JsonNode reset = json(call("POST", "/api/legalize/roster/reset", farmer, Map.of())).get("data");
        assertEquals(7, reset.get("count").asInt());
        assertEquals(7, json(call("GET", "/api/legalize/roster", farmer, null)).get("data").size());

        // admin 看全部
        String admin = json(call("POST", "/api/auth/demo-login", null, Map.of("role", "admin_tech"))).get("token").asText();
        assertEquals(9, json(call("GET", "/api/legalize/roster", admin, null)).get("data").size());
        assertEquals(200, call("GET", "/api/legalize/summary", admin, null).getResponse().getStatus());
    }
}
