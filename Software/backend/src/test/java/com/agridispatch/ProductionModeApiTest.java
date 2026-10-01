package com.agridispatch;

import com.agridispatch.entity.Order;
import com.agridispatch.repository.OrderRepository;
import com.agridispatch.repository.UserRepository;
import com.agridispatch.security.PasswordHasher;
import com.agridispatch.security.TokenService;
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

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * production 模式（agri.demo.enabled=false）整合測試：
 * 認證 / 授權 / 遮罩 / demo 端點關閉 / 輸入驗證 / 訂單 / ECPay / LINE 簽章
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:agri_prod_test;DB_CLOSE_DELAY=-1",
        "agri.demo.enabled=false",
        "agri.security.token-secret=unit-test-token-secret-0123456789abcdef",
        "agri.security.admin-password=Admin#Pass2026",
        "agri.line.channel-secret=test-line-channel-secret",
        "agri.payment.provider=ecpay",
        "agri.payment.ecpay.merchant-id=3002607",
        "agri.payment.ecpay.hash-key=pwFHCqoQZGmho4w6",
        "agri.payment.ecpay.hash-iv=EkRm7iFT261dpevs",
        "agri.payment.ecpay.return-url=https://example.com/api/payments/ecpay/notify"
})
@AutoConfigureMockMvc
class ProductionModeApiTest extends ApiTestSupport {

    private static final String HASH_KEY = "pwFHCqoQZGmho4w6";
    private static final String HASH_IV = "EkRm7iFT261dpevs";

    @Autowired TokenService tokenService;
    @Autowired OrderRepository orderRepository;
    @Autowired UserRepository userRepository;

    private String adminToken;

    @BeforeEach
    void setUp() throws Exception {
        adminToken = login("admin_tech", "Admin#Pass2026");
    }

    private String farmerToken() throws Exception {
        MvcResult r = call("POST", "/api/auth/reset-password", adminToken,
                Map.of("identifier", "farmer", "newPassword", "Farmer#2026pw"));
        assertEquals(200, r.getResponse().getStatus());
        return login("farmer", "Farmer#2026pw");
    }

    // ------------------------------------------------------------------ 認證

    @Test
    void unauthenticatedRequestReturns401() throws Exception {
        MvcResult r = call("GET", "/api/workers", null, null);
        assertEquals(401, r.getResponse().getStatus());
        assertEquals("UNAUTHORIZED", json(r).get("error").asText());
        assertFalse(json(r).get("success").asBoolean());

        assertEquals(401, call("GET", "/api/orders", "jwt-token-admin_tech-123", null).getResponse().getStatus(),
                "舊版假 token 不可通過");
    }

    @Test
    void publicEndpointsDoNotNeedToken() throws Exception {
        assertEquals(200, call("GET", "/api/crops", null, null).getResponse().getStatus());
        assertEquals(200, call("GET", "/api/payments/methods", null, null).getResponse().getStatus());
        assertEquals(200, call("GET", "/api/dispatch/weather-state", null, null).getResponse().getStatus());
        MvcResult cfg = call("GET", "/api/public/config", null, null);
        assertEquals(200, cfg.getResponse().getStatus());
        assertFalse(json(cfg).get("demoEnabled").asBoolean());
        assertEquals("ecpay", json(cfg).get("paymentProvider").asText());
    }

    @Test
    void loginFailureDoesNotHintPassword() throws Exception {
        MvcResult r = call("POST", "/api/auth/login", null, Map.of("identifier", "admin_tech", "password", "wrong"));
        assertEquals(401, r.getResponse().getStatus());
        String body = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(body.contains("帳號或密碼錯誤"));
        assertFalse(body.contains("123456"));

        // 舊版後門分支已移除：farmer / 0912-345-678 / worker_labor / W030 + 123456 不可登入
        for (String id : List.of("farmer", "0912-345-678", "worker_labor", "W030", "W001", "AD88123451")) {
            assertEquals(401, call("POST", "/api/auth/login", null,
                    Map.of("identifier", id, "password", "123456")).getResponse().getStatus(), id);
        }
        // 本國工證號重複值不可造成例外
        assertEquals(401, call("POST", "/api/auth/login", null,
                Map.of("identifier", "本國身分證", "password", "x")).getResponse().getStatus());
    }

    @Test
    void passwordsStoredAsPbkdf2() {
        userRepository.findAll().forEach(u -> {
            if (u.getPassword() != null) {
                assertTrue(PasswordHasher.isHashed(u.getPassword()), u.getUsername());
            }
        });
    }

    @Test
    void meReturnsUser() throws Exception {
        MvcResult r = call("GET", "/api/auth/me", adminToken, null);
        assertEquals(200, r.getResponse().getStatus());
        assertEquals("admin_tech", json(r).get("user").get("role").asText());
    }

    @Test
    void demoEndpointsReturn404InProduction() throws Exception {
        assertEquals(404, call("POST", "/api/auth/demo-login", null, Map.of("role", "admin_tech")).getResponse().getStatus());
        assertEquals(404, call("GET", "/api/auth/quick-profiles", null, null).getResponse().getStatus());
        assertEquals(404, call("POST", "/api/dev/seed", adminToken, null).getResponse().getStatus());
        assertEquals(404, call("GET", "/h2-console", null, null).getResponse().getStatus());
        assertEquals(404, call("POST", "/api/payments/AG0000/mock-confirm", adminToken, null).getResponse().getStatus());
    }

    @Test
    void demoTokenRejectedInProduction() throws Exception {
        String demoToken = tokenService.issue("admin_tech", "admin_tech", "ADM01", true);
        assertEquals(401, call("GET", "/api/workers", demoToken, null).getResponse().getStatus());
    }

    // ------------------------------------------------------------------ 授權 / 遮罩

    @Test
    void workersListMaskedForAdmin() throws Exception {
        MvcResult r = call("GET", "/api/workers", adminToken, null);
        assertEquals(200, r.getResponse().getStatus());
        String body = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertFalse(body.contains("AD88123451"), "ARC 必須遮罩");
        assertFalse(body.contains("C9823101"), "護照必須遮罩");
        assertFalse(body.contains("passwordHash"));
        JsonNode w001 = null;
        for (JsonNode n : json(r).get("data")) if ("W001".equals(n.get("id").asText())) w001 = n;
        assertNotNull(w001);
        assertEquals("AD****51", w001.get("arcNumber").asText());
        assertEquals("C9****01", w001.get("passportNumber").asText());

        // 本國工 ARC 種子為 null
        for (JsonNode n : json(r).get("data")) {
            if ("W012".equals(n.get("id").asText())) assertTrue(n.get("arcNumber").isNull());
        }

        MvcResult full = call("GET", "/api/workers/W001", adminToken, null);
        assertEquals("AD88123451", json(full).get("data").get("arcNumber").asText(), "admin 查單筆取得完整值");
    }

    @Test
    void workerCanReadSelfMaskedButNotOthers() throws Exception {
        call("POST", "/api/auth/reset-password", adminToken, Map.of("identifier", "W001", "newPassword", "Worker#2026pw"));
        String workerToken = login("AD88123451", "Worker#2026pw"); // 以 ARC 登入

        MvcResult self = call("GET", "/api/workers/W001", workerToken, null);
        assertEquals(200, self.getResponse().getStatus());
        assertEquals("AD****51", json(self).get("data").get("arcNumber").asText());

        assertEquals(403, call("GET", "/api/workers", workerToken, null).getResponse().getStatus());
        assertEquals(403, call("GET", "/api/workers/W002", workerToken, null).getResponse().getStatus());
        assertEquals(403, call("GET", "/api/attendance/history/W002", workerToken, null).getResponse().getStatus());
        assertEquals(403, call("POST", "/api/attendance/clock-in", workerToken, Map.of("workerId", "W002")).getResponse().getStatus());
        assertEquals(403, call("PUT", "/api/workers/W001", workerToken, Map.of("name", "x")).getResponse().getStatus());
        assertEquals("FORBIDDEN", json(call("GET", "/api/workers", workerToken, null)).get("error").asText());
    }

    @Test
    void farmerForbiddenFromAdminEndpointsAndOthersOrders() throws Exception {
        String farmer = farmerToken();
        assertEquals(403, call("GET", "/api/workers", farmer, null).getResponse().getStatus());
        assertEquals(403, call("POST", "/api/crops", farmer, Map.of("id", "x", "name", "x")).getResponse().getStatus());
        assertEquals(403, call("GET", "/api/line/config", farmer, null).getResponse().getStatus());
        assertEquals(403, call("POST", "/api/notifications/send", farmer, Map.of("message", "hi")).getResponse().getStatus());
        assertEquals(403, call("POST", "/api/dispatch/weather-scenario", farmer, Map.of("scenarioId", "sunny")).getResponse().getStatus());

        // 只看得到自己的訂單（production 模式下無 farmerId 的舊資料不放行）
        MvcResult list = call("GET", "/api/orders", farmer, null);
        for (JsonNode o : json(list).get("data")) assertEquals("F001", o.get("farmerId").asText());
        assertEquals(200, call("GET", "/api/orders/ORD-2026-001", farmer, null).getResponse().getStatus());
        assertEquals(403, call("GET", "/api/orders/ORD-2026-002", farmer, null).getResponse().getStatus());
        assertEquals(403, call("POST", "/api/payments/checkout", farmer, Map.of("orderId", "ORD-2026-005")).getResponse().getStatus());
    }

    @Test
    void lineConfigAdminOnlyAndNoPlainSecret() throws Exception {
        MvcResult r = call("GET", "/api/line/config", adminToken, null);
        assertEquals(200, r.getResponse().getStatus());
        assertFalse(r.getResponse().getContentAsString(StandardCharsets.UTF_8).contains("test-line-channel-secret"));
        assertEquals(401, call("GET", "/api/line/config", null, null).getResponse().getStatus());
    }

    // ------------------------------------------------------------------ 輸入驗證

    @Test
    void invalidInputReturns400() throws Exception {
        assertEquals(400, call("POST", "/api/orders/calculate", null, Map.of("area", "abc")).getResponse().getStatus());
        assertEquals(400, call("POST", "/api/orders/calculate", null, Map.of("area", -1)).getResponse().getStatus());
        assertEquals(400, call("POST", "/api/orders/calculate", null, Map.of("area", 0)).getResponse().getStatus());
        assertEquals(400, call("POST", "/api/orders/calculate", null, Map.of("cropId", "no_such_crop", "area", 2)).getResponse().getStatus());
        assertEquals(400, call("POST", "/api/orders/calculate", null, Map.of("plan", "free_lunch", "area", 2)).getResponse().getStatus());
        MvcResult bad = call("POST", "/api/orders/calculate", null, "{not json");
        assertEquals(400, bad.getResponse().getStatus());
        assertFalse(json(bad).get("success").asBoolean());

        assertEquals(400, call("POST", "/api/orders", adminToken,
                Map.of("farmerName", "測試", "cropId", "taoyuan_veg", "area", -3)).getResponse().getStatus());
        assertEquals(400, call("POST", "/api/orders", adminToken,
                Map.of("cropId", "taoyuan_veg", "area", 3)).getResponse().getStatus(), "farmerName 必填");
        assertEquals(400, call("POST", "/api/orders", adminToken,
                Map.of("farmerName", "測試", "cropId", "no_such_crop", "area", 3)).getResponse().getStatus());
        assertEquals(400, call("POST", "/api/orders", adminToken,
                "{\"farmerName\":\"x\",\"cropId\":\"taoyuan_veg\",\"area\":\"abc\"}").getResponse().getStatus());
        assertEquals(400, call("GET", "/api/workers/W001/attendance?year=abc&month=1", adminToken, null).getResponse().getStatus());
        assertEquals(400, call("POST", "/api/talents/register", null, Map.of("name", "王小明")).getResponse().getStatus(),
                "公開報名需電話與個資同意");
    }

    // ------------------------------------------------------------------ 訂單

    @Test
    void orderIdsDoNotCollideAfterDelete() throws Exception {
        Map<String, Object> body = Map.of("farmerName", "撞號測試", "cropId", "taoyuan_veg", "area", 2.0, "plan", "early_bird");
        String a = json(call("POST", "/api/orders", adminToken, body)).get("data").get("id").asText();
        MvcResult rb = call("POST", "/api/orders", adminToken, Map.of("farmerName", "B 農場", "cropId", "mango", "area", 1.0));
        String b = json(rb).get("data").get("id").asText();
        assertNotEquals(a, b);
        assertEquals(200, call("DELETE", "/api/orders/" + a, adminToken, null).getResponse().getStatus());

        String c = json(call("POST", "/api/orders", adminToken, body)).get("data").get("id").asText();
        assertNotEquals(b, c, "刪除後新增不可與既有 ID 相撞");
        assertEquals("B 農場", orderRepository.findById(b).orElseThrow().getFarmerName(), "既有訂單不可被覆寫");
    }

    @Test
    void orderCreateIgnoresClientStatusAndAmounts() throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("id", "ORD-2026-001");
        body.put("farmerName", "竄改測試");
        body.put("cropId", "taoyuan_veg");
        body.put("area", 5.0);
        body.put("plan", "early_bird");
        body.put("status", "completed");
        body.put("depositPaid", true);
        body.put("finalAmount", 1);
        JsonNode created = json(call("POST", "/api/orders", adminToken, body)).get("data");
        assertNotEquals("ORD-2026-001", created.get("id").asText(), "不可覆寫既有訂單");
        assertEquals("pending", created.get("status").asText(), "需定金方案新訂單為 pending");
        assertFalse(created.get("depositPaid").asBoolean());
        assertEquals(31500, created.get("finalAmount").asInt(), "金額由後端計算：100h×315（早鳥不打折）");
        assertEquals(6300, created.get("depositAmount").asInt());
        assertEquals("陳建龍 (阿龍班長)", orderRepository.findById("ORD-2026-001").orElseThrow().getFarmerName());
    }

    @Test
    void orderUpdateProtectsPaymentFieldsAndRecalculates() throws Exception {
        JsonNode created = json(call("POST", "/api/orders", adminToken,
                Map.of("farmerName", "更新測試", "cropId", "taoyuan_veg", "area", 5.0, "plan", "early_bird"))).get("data");
        String id = created.get("id").asText();

        // client 嘗試直接改 depositPaid / status / finalAmount → 忽略
        MvcResult r = call("PUT", "/api/orders/" + id, adminToken,
                Map.of("depositPaid", true, "status", "completed", "finalAmount", 1, "foremanNotes", "請帶剪刀"));
        assertEquals(200, r.getResponse().getStatus());
        JsonNode u = json(r).get("data");
        assertFalse(u.get("depositPaid").asBoolean());
        assertEquals("pending", u.get("status").asText());
        assertEquals(31500, u.get("finalAmount").asInt());
        assertEquals("請帶剪刀", u.get("foremanNotes").asText());

        // 面積變更 → 重算
        JsonNode u2 = json(call("PUT", "/api/orders/" + id, adminToken, Map.of("area", 10.0))).get("data");
        assertEquals(200, u2.get("totalHours").asInt());
        assertEquals(63000, u2.get("finalAmount").asInt());

        // 方案改為免定金 → 定金 0、狀態轉 confirmed
        JsonNode u3 = json(call("PUT", "/api/orders/" + id, adminToken, Map.of("plan", "hourly_flex"))).get("data");
        assertEquals(0, u3.get("depositAmount").asInt());
        assertEquals("confirmed", u3.get("status").asText());

        // cropId 不存在 → 400，且資料不變
        assertEquals(400, call("PUT", "/api/orders/" + id, adminToken, Map.of("cropId", "no_such_crop")).getResponse().getStatus());
        assertEquals("taoyuan_veg", orderRepository.findById(id).orElseThrow().getCropId());

        // PUT 缺欄位不會把已付定金清成 false（seed ORD-2026-001 已付）
        call("PUT", "/api/orders/ORD-2026-001", adminToken, Map.of("foremanNotes", "x"));
        assertTrue(orderRepository.findById("ORD-2026-001").orElseThrow().isDepositPaid());
    }

    @Test
    void adminStatusTransitionsFollowStateMachine() throws Exception {
        String id = json(call("POST", "/api/orders", adminToken,
                Map.of("farmerName", "狀態機", "cropId", "mango", "area", 2.0, "plan", "hourly_flex"))).get("data").get("id").asText();
        assertEquals(409, call("PUT", "/api/orders/" + id + "/status", adminToken, Map.of("status", "completed")).getResponse().getStatus());
        assertEquals(200, call("PUT", "/api/orders/" + id + "/status", adminToken, Map.of("status", "in_progress")).getResponse().getStatus());
        assertEquals(200, call("PUT", "/api/orders/" + id + "/status", adminToken, Map.of("status", "completed")).getResponse().getStatus());
        assertEquals(409, call("PUT", "/api/orders/" + id + "/status", adminToken, Map.of("status", "pending")).getResponse().getStatus());
        assertEquals(400, call("PUT", "/api/orders/" + id + "/status", adminToken, Map.of("status", "weird")).getResponse().getStatus());
    }

    // ------------------------------------------------------------------ ECPay

    private MockHttpServletRequestBuilder form(String url, Map<String, String> params) {
        MockHttpServletRequestBuilder b = post(url).contentType(MediaType.APPLICATION_FORM_URLENCODED);
        params.forEach(b::param);
        return b;
    }

    @Test
    void ecpayCheckoutAndNotify() throws Exception {
        String id = json(call("POST", "/api/orders", adminToken,
                Map.of("farmerName", "綠界測試", "cropId", "taoyuan_veg", "area", 5.0, "plan", "early_bird"))).get("data").get("id").asText();

        MvcResult co = call("POST", "/api/payments/checkout", adminToken, Map.of("orderId", id, "amount", 1));
        assertEquals(200, co.getResponse().getStatus());
        JsonNode res = json(co);
        assertEquals("PENDING", res.get("status").asText());
        assertEquals(6300, res.get("amount").asInt(), "金額由後端依訂單計算，忽略 client amount");
        String txId = res.get("transactionId").asText();
        assertTrue(txId.length() <= 20);
        Map<String, String> formParams = om.convertValue(res.get("next").get("params"), Map.class);
        assertEquals("6300", formParams.get("TotalAmount"));
        assertTrue(EcpayCheckMac.verify(formParams, HASH_KEY, HASH_IV), "送往綠界的表單 CheckMacValue 正確");

        Map<String, String> notify = new LinkedHashMap<>();
        notify.put("MerchantID", "3002607");
        notify.put("MerchantTradeNo", txId);
        notify.put("RtnCode", "1");
        notify.put("RtnMsg", "交易成功");
        notify.put("TradeNo", "2309231234567890");
        notify.put("TradeAmt", "6300");
        notify.put("PaymentDate", "2026/09/23 10:00:00");
        notify.put("PaymentType", "Credit_CreditCard");
        notify.put("TradeDate", "2026/09/23 09:59:00");
        notify.put("SimulatePaid", "0");

        // CheckMacValue 錯誤 → 拒絕，訂單不可標記已付款
        Map<String, String> forged = new LinkedHashMap<>(notify);
        forged.put("CheckMacValue", "0000000000000000000000000000000000000000000000000000000000000000");
        MvcResult bad = mvc.perform(form("/api/payments/ecpay/notify", forged)).andReturn();
        assertEquals(400, bad.getResponse().getStatus());
        assertEquals("0|CheckMacValue Error", bad.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertFalse(orderRepository.findById(id).orElseThrow().isDepositPaid());

        // 以正確金鑰簽章但竄改金額 → 仍拒絕
        Map<String, String> wrongAmt = new LinkedHashMap<>(notify);
        wrongAmt.put("TradeAmt", "1");
        wrongAmt.put("CheckMacValue", EcpayCheckMac.generate(wrongAmt, HASH_KEY, HASH_IV));
        assertEquals(400, mvc.perform(form("/api/payments/ecpay/notify", wrongAmt)).andReturn().getResponse().getStatus());
        assertFalse(orderRepository.findById(id).orElseThrow().isDepositPaid());

        // 正確通知 → 1|OK，訂單 pending → confirmed
        notify.put("CheckMacValue", EcpayCheckMac.generate(notify, HASH_KEY, HASH_IV));
        MvcResult ok = mvc.perform(form("/api/payments/ecpay/notify", notify)).andReturn();
        assertEquals(200, ok.getResponse().getStatus());
        assertEquals("1|OK", ok.getResponse().getContentAsString(StandardCharsets.UTF_8));
        Order paid = orderRepository.findById(id).orElseThrow();
        assertTrue(paid.isDepositPaid());
        assertEquals("confirmed", paid.getStatus());

        // 重複通知冪等；再次結帳回傳既有交易
        assertEquals("1|OK", mvc.perform(form("/api/payments/ecpay/notify", notify)).andReturn()
                .getResponse().getContentAsString(StandardCharsets.UTF_8));
        JsonNode again = json(call("POST", "/api/payments/checkout", adminToken, Map.of("orderId", id)));
        assertTrue(again.get("alreadyPaid").asBoolean());
        assertEquals(txId, again.get("transactionId").asText());
    }

    // ------------------------------------------------------------------ LINE webhook

    private static String lineSignature(String secret, byte[] body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return Base64.getEncoder().encodeToString(mac.doFinal(body));
    }

    @Test
    void lineWebhookRequiresValidSignature() throws Exception {
        byte[] body = "{\"events\":[{\"type\":\"message\",\"source\":{\"userId\":\"U1\"},\"message\":{\"type\":\"text\",\"text\":\"查費率\"}}]}"
                .getBytes(StandardCharsets.UTF_8);
        assertEquals(401, mvc.perform(post("/api/line/webhook").contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse().getStatus(), "缺少簽章");
        assertEquals(401, mvc.perform(post("/api/line/webhook").contentType(MediaType.APPLICATION_JSON).content(body)
                .header("X-Line-Signature", "mock_signature")).andReturn().getResponse().getStatus(), "簽章錯誤");
        MvcResult ok = mvc.perform(post("/api/line/webhook").contentType(MediaType.APPLICATION_JSON).content(body)
                .header("X-Line-Signature", lineSignature("test-line-channel-secret", body))).andReturn();
        assertEquals(200, ok.getResponse().getStatus());
        assertTrue(json(ok).get("replies").size() > 0);
    }

    @Test
    void h2ConsoleNotExposed() throws Exception {
        assertEquals(404, mvc.perform(get("/h2-console/login.jsp")).andReturn().getResponse().getStatus());
    }
}
