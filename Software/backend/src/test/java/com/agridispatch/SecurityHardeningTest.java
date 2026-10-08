package com.agridispatch;

import com.agridispatch.entity.Order;
import com.agridispatch.repository.OrderRepository;
import com.agridispatch.service.payment.EcpayCheckMac;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * production 模式的越權、金流與輸入驗證回歸測試（2026-10-08 安全稽核補強）：
 * 工人只看自己工班的訂單、綠界舊交易不可少付定金、公開端點不洩漏聯絡資料與營收、
 * 模擬推播與通知紀錄的權限、DM 草稿、農場主不可自選工班、過長欄位回 400。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:agri_hardening_test;DB_CLOSE_DELAY=-1",
        "agri.demo.enabled=false",
        "agri.security.token-secret=unit-test-token-secret-0123456789abcdef",
        "agri.security.admin-password=Admin#Pass2026",
        "agri.payment.provider=ecpay",
        "agri.payment.ecpay.merchant-id=3002607",
        "agri.payment.ecpay.hash-key=pwFHCqoQZGmho4w6",
        "agri.payment.ecpay.hash-iv=EkRm7iFT261dpevs",
        "agri.payment.ecpay.return-url=https://example.com/api/payments/ecpay/notify"
})
@AutoConfigureMockMvc
class SecurityHardeningTest extends ApiTestSupport {

    private static final String HASH_KEY = "pwFHCqoQZGmho4w6";
    private static final String HASH_IV = "EkRm7iFT261dpevs";

    @Autowired OrderRepository orderRepository;

    private String adminToken;

    @BeforeEach
    void setUp() throws Exception {
        adminToken = login("admin_tech", "Admin#Pass2026");
    }

    private String farmerToken() throws Exception {
        call("POST", "/api/auth/reset-password", adminToken, Map.of("identifier", "farmer", "newPassword", "Farmer#2026pw"));
        return login("farmer", "Farmer#2026pw");
    }

    private String workerToken() throws Exception {
        call("POST", "/api/auth/reset-password", adminToken, Map.of("identifier", "W001", "newPassword", "Worker#2026pw"));
        return login("AD88123451", "Worker#2026pw");
    }

    private static boolean inCrew(JsonNode o, String workerId) {
        JsonNode crew = o.get("crewWorkerIds");
        return crew != null && !crew.isNull() && Arrays.asList(crew.asText().split(",")).contains(workerId);
    }

    @Test
    void workerSeesOnlyOwnTeamOrders() throws Exception {
        String worker = workerToken();
        String teamId = json(call("GET", "/api/workers/W001", adminToken, null)).get("data").get("teamId").asText();

        // 確保有一張別的工班的訂單
        JsonNode teams = json(call("GET", "/api/dispatch/teams", adminToken, null)).get("data");
        String otherTeam = null;
        for (JsonNode t : teams) if (!teamId.equals(t.get("id").asText())) { otherTeam = t.get("id").asText(); break; }
        assertNotNull(otherTeam);
        String otherId = json(call("POST", "/api/orders", adminToken, Map.of("farmerName", "別組訂單", "cropId", "mango",
                "area", 2.0, "plan", "hourly_flex", "assignedTeamId", otherTeam))).get("data").get("id").asText();

        JsonNode list = json(call("GET", "/api/orders", worker, null)).get("data");
        for (JsonNode o : list) {
            assertTrue(teamId.equals(o.path("assignedTeamId").asText()) || inCrew(o, "W001"),
                    "工人不應看到 " + o.get("id").asText());
        }
        assertEquals(403, call("GET", "/api/orders/" + otherId, worker, null).getResponse().getStatus());
        assertEquals(403, call("GET", "/api/ratings/orders/" + otherId, worker, null).getResponse().getStatus());
    }

    private MockHttpServletRequestBuilder form(Map<String, String> params) {
        MockHttpServletRequestBuilder b = post("/api/payments/ecpay/notify").contentType(MediaType.APPLICATION_FORM_URLENCODED);
        params.forEach(b::param);
        return b;
    }

    private Map<String, String> paidNotify(String txId, int amount) {
        Map<String, String> n = new LinkedHashMap<>();
        n.put("MerchantID", "3002607");
        n.put("MerchantTradeNo", txId);
        n.put("RtnCode", "1");
        n.put("RtnMsg", "交易成功");
        n.put("TradeNo", "2310081234567890");
        n.put("TradeAmt", String.valueOf(amount));
        n.put("PaymentDate", "2026/10/08 10:00:00");
        n.put("PaymentType", "Credit_CreditCard");
        n.put("TradeDate", "2026/10/08 09:59:00");
        n.put("SimulatePaid", "0");
        n.put("CheckMacValue", EcpayCheckMac.generate(n, HASH_KEY, HASH_IV));
        return n;
    }

    @Test
    void stalePaymentPageCannotUnderpayDeposit() throws Exception {
        String id = json(call("POST", "/api/orders", adminToken,
                Map.of("farmerName", "少付測試", "cropId", "taoyuan_veg", "area", 5.0, "plan", "early_bird"))).get("data").get("id").asText();
        JsonNode co = json(call("POST", "/api/payments/checkout", adminToken, Map.of("orderId", id)));
        String oldTx = co.get("transactionId").asText();
        int oldAmount = co.get("amount").asInt();

        // 付款前把面積改大，定金跟著變大；再回頭付舊交易頁
        assertEquals(200, call("PUT", "/api/orders/" + id, adminToken, Map.of("area", 50.0)).getResponse().getStatus());
        assertTrue(orderRepository.findById(id).orElseThrow().getDepositAmount() > oldAmount);

        MvcResult r = mvc.perform(form(paidNotify(oldTx, oldAmount))).andReturn();
        assertEquals("1|OK", r.getResponse().getContentAsString(StandardCharsets.UTF_8), "已入帳的款項仍回 1|OK，避免綠界重送");
        Order order = orderRepository.findById(id).orElseThrow();
        assertFalse(order.isDepositPaid(), "金額不足的舊交易不可把訂單標成已付定金");
        assertEquals("pending", order.getStatus());
    }

    @Test
    void cancelledOrderNotMarkedPaidByNotify() throws Exception {
        String id = json(call("POST", "/api/orders", adminToken,
                Map.of("farmerName", "取消後付款", "cropId", "taoyuan_veg", "area", 5.0, "plan", "early_bird"))).get("data").get("id").asText();
        JsonNode co = json(call("POST", "/api/payments/checkout", adminToken, Map.of("orderId", id)));
        assertEquals(200, call("PUT", "/api/orders/" + id + "/status", adminToken, Map.of("status", "cancelled")).getResponse().getStatus());

        mvc.perform(form(paidNotify(co.get("transactionId").asText(), co.get("amount").asInt()))).andReturn();
        Order order = orderRepository.findById(id).orElseThrow();
        assertFalse(order.isDepositPaid());
        assertEquals("cancelled", order.getStatus());
    }

    @Test
    void publicDispatchOverviewHidesContactsAndRevenue() throws Exception {
        JsonNode anon = json(call("GET", "/api/dispatch/overview", null, null)).get("data");
        assertFalse(anon.has("securedRevenue"));
        assertTrue(anon.get("teams").size() > 0);
        for (JsonNode t : anon.get("teams")) {
            assertFalse(t.has("leaderPhone"));
            assertFalse(t.has("driver"));
            assertTrue(t.has("status"));
        }
        assertFalse(json(call("GET", "/api/dispatch/overview", farmerToken(), null)).get("data").has("securedRevenue"));

        JsonNode admin = json(call("GET", "/api/dispatch/overview", adminToken, null)).get("data");
        assertTrue(admin.has("securedRevenue"));
        assertTrue(admin.get("teams").get(0).has("leaderPhone"));
    }

    @Test
    void simulatePushAdminOnlyInProduction() throws Exception {
        assertEquals(403, call("POST", "/api/line/simulate-push", farmerToken(), Map.of("type", "order_query")).getResponse().getStatus());
        assertEquals(403, call("POST", "/api/line/simulate-push", workerToken(), Map.of("type", "order_query")).getResponse().getStatus());
        assertEquals(200, call("POST", "/api/line/simulate-push", adminToken, Map.of("type", "order_query")).getResponse().getStatus());
    }

    @Test
    void notificationHistoryLimitedToOwnTarget() throws Exception {
        String farmer = farmerToken();
        for (String url : List.of("/api/notifications", "/api/notifications?target=W001")) {
            for (JsonNode n : json(call("GET", url, farmer, null)).get("data")) {
                String target = n.get("target").asText();
                assertTrue("all".equals(target) || "F001".equals(target), "農場主看到發給 " + target + " 的通知");
            }
        }
        boolean adminSeesWorkerNotice = false;
        for (JsonNode n : json(call("GET", "/api/notifications", adminToken, null)).get("data")) {
            if ("W001".equals(n.get("target").asText())) adminSeesWorkerNotice = true;
        }
        assertTrue(adminSeesWorkerNotice);

        // 欄位明確傳 null 不可 500
        Map<String, Object> body = new HashMap<>();
        body.put("title", null);
        body.put("message", "測試");
        assertEquals(200, call("POST", "/api/notifications/send", adminToken, body).getResponse().getStatus());
        Map<String, Object> fcm = new HashMap<>();
        fcm.put("token", null);
        assertNotEquals(500, call("POST", "/api/notifications/fcm/register", farmer, fcm).getResponse().getStatus());
        Map<String, Object> scenario = new HashMap<>();
        scenario.put("scenarioId", null);
        assertNotEquals(500, call("POST", "/api/dispatch/weather-scenario", adminToken, scenario).getResponse().getStatus());
    }

    @Test
    void brochureDraftsHiddenFromPublic() throws Exception {
        int published = json(call("GET", "/api/brochures", null, null)).get("data").size();
        assertEquals(published, json(call("GET", "/api/brochures?all=true", null, null)).get("data").size());
        assertEquals(published, json(call("GET", "/api/brochures?all=true", farmerToken(), null)).get("data").size());
    }

    @Test
    void farmerCannotPickTeam() throws Exception {
        JsonNode created = json(call("POST", "/api/orders", farmerToken(), Map.of("farmerName", "自選工班", "phone", "0912000111",
                "farmLocation", "桃園", "cropId", "mango", "area", 2.0, "plan", "hourly_flex", "assignedTeamId", "TEAM-NOT-EXIST")));
        assertNotEquals("TEAM-NOT-EXIST", created.get("data").path("assignedTeamId").asText());
    }

    @Test
    void overlongTextReturns400Not500() throws Exception {
        MvcResult order = call("POST", "/api/orders", adminToken, Map.of("farmerName", "過長", "cropId", "mango",
                "area", 2.0, "plan", "hourly_flex", "foremanNotes", "注".repeat(300)));
        assertEquals(400, order.getResponse().getStatus());

        MvcResult talent = call("POST", "/api/talents/register", null, Map.of("name", "王小明", "phone", "0912000222",
                "category", "harvest", "categoryName", "採收", "pdpaConsent", true, "experience", "經".repeat(300)));
        assertEquals(400, talent.getResponse().getStatus());
    }
}
