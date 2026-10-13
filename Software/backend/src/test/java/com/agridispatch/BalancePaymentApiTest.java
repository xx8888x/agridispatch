package com.agridispatch;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 尾款收款登錄（admin）：POST /api/payments/{orderId}/balance、PUT /transactions/{id}/invoice、POST /transactions/{id}/void
 * 種子：ORD-2026-006 完工・定金 30,240 已付；ORD-2026-012 完工・定金已付；ORD-2026-004 完工・免定金；ORD-2026-001 已排定
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:agri_balance_test;DB_CLOSE_DELAY=-1",
        "agri.demo.enabled=true",
        "agri.security.token-secret=unit-test-token-secret-0123456789abcdef",
        "agri.payment.provider=mock"
})
@AutoConfigureMockMvc
class BalancePaymentApiTest extends ApiTestSupport {

    private JsonNode status(String admin, String orderId) throws Exception {
        return json(call("GET", "/api/payments/" + orderId + "/status", admin, null)).get("data");
    }

    @Test
    void partialAndFullBalanceThenSettled() throws Exception {
        String admin = login("admin_tech", "admin888");
        JsonNode s0 = status(admin, "ORD-2026-006");
        int outstanding = s0.get("finalAmount").asInt() - 30240;
        assertEquals(30240, s0.get("depositCredit").asInt());
        assertEquals(0, s0.get("balancePaid").asInt());
        assertEquals(outstanding, s0.get("balanceOutstanding").asInt());

        MvcResult r1 = call("POST", "/api/payments/ORD-2026-006/balance", admin,
                Map.of("amount", 100000, "paidDate", "2026-09-30", "paymentMethod", "bank_transfer", "note", "轉帳末五碼 12345"));
        assertEquals(200, r1.getResponse().getStatus(), r1.getResponse().getContentAsString());
        JsonNode d1 = json(r1).get("data");
        assertEquals(100000, d1.get("balancePaid").asInt());
        assertEquals(outstanding - 100000, d1.get("balanceOutstanding").asInt());
        JsonNode tx = d1.get("transaction");
        assertEquals("balance", tx.get("kind").asText());
        assertEquals("offline", tx.get("provider").asText());
        assertEquals("PAID", tx.get("status").asText());
        assertTrue(tx.get("paidAt").asText().startsWith("2026-09-30"));
        assertFalse(tx.has("balance"), "isBalance() 不輸出");

        // 超過未結清金額
        assertEquals(400, call("POST", "/api/payments/ORD-2026-006/balance", admin,
                Map.of("amount", outstanding)).getResponse().getStatus());
        // 不帶金額＝結清剩餘
        JsonNode d2 = json(call("POST", "/api/payments/ORD-2026-006/balance", admin, Map.of("paymentMethod", "cash"))).get("data");
        assertEquals(outstanding, d2.get("balancePaid").asInt());
        assertEquals(0, d2.get("balanceOutstanding").asInt());
        assertEquals(409, call("POST", "/api/payments/ORD-2026-006/balance", admin, Map.of()).getResponse().getStatus(), "已結清");

        // 定金冪等回應仍是定金交易，不會拿到尾款登錄
        JsonNode again = json(call("POST", "/api/payments/checkout", admin, Map.of("orderId", "ORD-2026-006")));
        assertTrue(again.get("alreadyPaid").asBoolean());
        assertEquals("SEED2026006", again.get("transactionId").asText());
        assertEquals(3, status(admin, "ORD-2026-006").get("transactions").size());
    }

    @Test
    void validationAndPermissions() throws Exception {
        String admin = login("admin_tech", "admin888");
        String farmer = login("farmer", "123456");
        assertEquals(409, call("POST", "/api/payments/ORD-2026-001/balance", admin, Map.of()).getResponse().getStatus(), "未完工不可登錄");
        assertEquals(400, call("POST", "/api/payments/ORD-2026-012/balance", admin,
                Map.of("paidDate", LocalDate.now().plusDays(1).toString())).getResponse().getStatus(), "收款日不可晚於今天");
        assertEquals(400, call("POST", "/api/payments/ORD-2026-012/balance", admin,
                Map.of("paymentMethod", "bitcoin")).getResponse().getStatus());
        assertEquals(400, call("POST", "/api/payments/ORD-2026-012/balance", admin,
                Map.of("invoiceNo", "12345")).getResponse().getStatus(), "發票格式");
        assertEquals(404, call("POST", "/api/payments/ORD-9999-999/balance", admin, Map.of()).getResponse().getStatus());

        assertEquals(403, call("POST", "/api/payments/ORD-2026-012/balance", farmer, Map.of()).getResponse().getStatus());
        assertEquals(401, call("POST", "/api/payments/ORD-2026-012/balance", null, Map.of()).getResponse().getStatus());
        assertEquals(403, call("PUT", "/api/payments/transactions/SEED2026012/invoice", farmer,
                Map.of("invoiceNo", "AB12345678")).getResponse().getStatus());
        assertEquals(403, call("POST", "/api/payments/transactions/SEED2026012/void", farmer,
                Map.of("reason", "x")).getResponse().getStatus());
    }

    @Test
    void invoiceAndVoid() throws Exception {
        String admin = login("admin_tech", "admin888");
        int outstanding = status(admin, "ORD-2026-012").get("balanceOutstanding").asInt();
        assertTrue(outstanding > 0);
        JsonNode tx = json(call("POST", "/api/payments/ORD-2026-012/balance", admin, Map.of())).get("data").get("transaction");
        String txId = tx.get("id").asText();
        assertTrue(tx.get("invoiceNo").isNull());

        // 補登發票：小寫、無連字號 → 正規化
        JsonNode inv = json(call("PUT", "/api/payments/transactions/" + txId + "/invoice", admin, Map.of("invoiceNo", "ab12345678")));
        assertEquals("AB-12345678", inv.get("data").get("transaction").get("invoiceNo").asText());
        assertEquals(400, call("PUT", "/api/payments/transactions/" + txId + "/invoice", admin, Map.of("invoiceNo", "")).getResponse().getStatus());

        // 作廢：需原因；作廢後尾款恢復未結清，不可再作廢、不可補發票
        assertEquals(400, call("POST", "/api/payments/transactions/" + txId + "/void", admin, Map.of("reason", " ")).getResponse().getStatus());
        JsonNode v = json(call("POST", "/api/payments/transactions/" + txId + "/void", admin, Map.of("reason", "金額登錄錯誤"))).get("data");
        assertEquals("VOID", v.get("transaction").get("status").asText());
        assertTrue(v.get("transaction").get("note").asText().contains("金額登錄錯誤"));
        assertEquals(outstanding, v.get("balanceOutstanding").asInt());
        assertEquals(409, call("POST", "/api/payments/transactions/" + txId + "/void", admin, Map.of("reason", "x")).getResponse().getStatus());
        assertEquals(409, call("PUT", "/api/payments/transactions/" + txId + "/invoice", admin, Map.of("invoiceNo", "AB12345678")).getResponse().getStatus());

        // 線上金流的定金交易不可作廢；但可補登發票號碼
        assertEquals(409, call("POST", "/api/payments/transactions/SEED2026012/void", admin, Map.of("reason", "x")).getResponse().getStatus());
        assertEquals(200, call("PUT", "/api/payments/transactions/SEED2026012/invoice", admin, Map.of("invoiceNo", "CD-87654321")).getResponse().getStatus());
    }

    @Test
    void zeroDepositOrderBalanceIsFullAmount() throws Exception {
        String admin = login("admin_tech", "admin888");
        JsonNode s = status(admin, "ORD-2026-004");
        assertEquals(0, s.get("depositCredit").asInt(), "免定金方案即使 depositPaid=true 也不抵扣");
        assertEquals(s.get("finalAmount").asInt(), s.get("balanceOutstanding").asInt());
        JsonNode d = json(call("POST", "/api/payments/ORD-2026-004/balance", admin, Map.of("invoiceNo", "EF-11112222"))).get("data");
        assertEquals(0, d.get("balanceOutstanding").asInt());
        assertEquals("EF-11112222", d.get("transaction").get("invoiceNo").asText());
    }
}
