package com.agridispatch;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 合法用工證明（production 模式）
 * 種子：ORD-2026-013（F001、in_progress、team_b）有 6 位工人 9/25 起 4 天、每日 7 小時的出勤；
 *       W026 另有 2 筆枋山出勤也連到 013；工人自 2026-03-01 起依身分加保
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:agri_certificate_test;DB_CLOSE_DELAY=-1",
        "agri.demo.enabled=false",
        "agri.security.token-secret=unit-test-token-secret-0123456789abcdef",
        "agri.security.admin-password=Admin#Pass2026"
})
@AutoConfigureMockMvc
class CertificateApiTest extends ApiTestSupport {

    private static final String CERT_URL = "/api/certificates/orders/ORD-2026-013";

    private String admin;

    @BeforeEach
    void setUp() throws Exception {
        admin = login("admin_tech", "Admin#Pass2026");
    }

    private String farmerToken() throws Exception {
        MvcResult r = call("POST", "/api/auth/reset-password", admin,
                Map.of("identifier", "farmer", "newPassword", "Farmer#2026pw"));
        assertEquals(200, r.getResponse().getStatus());
        return login("farmer", "Farmer#2026pw");
    }

    private JsonNode verify(String no, String code) throws Exception {
        MvcResult r = call("GET", "/api/public/certificates/" + no + (code == null ? "" : "?code=" + code), null, null);
        assertEquals(200, r.getResponse().getStatus());
        return json(r).get("data");
    }

    @Test
    void certificateLifecycle() throws Exception {
        String farmer = farmerToken();

        // 1. 種子資料：可開立，6 人，至少 168 小時
        JsonNode cert = json(call("GET", CERT_URL, farmer, null)).get("data");
        assertTrue(cert.get("issuable").asBoolean(), cert.get("problems").toString());
        assertEquals("LEC-ORD-2026-013", cert.get("certificateNo").asText());
        assertEquals(6, cert.get("workerCount").asInt());
        assertTrue(cert.get("totalHours").asDouble() >= 168);
        assertEquals("2026-09-25", cert.get("periodStart").asText());
        String code = cert.get("verifyCode").asText();
        assertEquals(12, code.length());

        // 2. 公開查驗：正確碼有效且不含工人姓名；錯碼、錯編號無效
        JsonNode ok = verify("LEC-ORD-2026-013", code);
        assertTrue(ok.get("valid").asBoolean());
        assertEquals(6, ok.get("workerCount").asInt());
        assertFalse(ok.has("workers"));
        assertFalse(ok.toString().contains("布迪"), "公開查驗不回傳工人姓名");
        assertFalse(verify("LEC-ORD-2026-013", "000000000000").get("valid").asBoolean());
        assertFalse(verify("LEC-ORD-2026-013", null).get("valid").asBoolean());
        assertFalse(verify("LEC-ORD-9999-999", code).get("valid").asBoolean());
        assertFalse(verify("XYZ", code).get("valid").asBoolean());

        // 3. 打卡連結訂單：指定 013 的 team_b 工人會被納入；非該工班拒絕
        MvcResult in = call("POST", "/api/workers/W009/clock-in", admin, Map.of("location", "枋山", "orderId", "ORD-2026-013"));
        assertEquals(200, in.getResponse().getStatus(), in.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertEquals("ORD-2026-013", json(in).get("data").get("orderId").asText());
        assertEquals(200, call("POST", "/api/workers/W009/clock-out", admin, Map.of()).getResponse().getStatus());
        assertEquals(400, call("POST", "/api/workers/W003/clock-in", admin, Map.of("orderId", "ORD-2026-013")).getResponse().getStatus(),
                "W003 屬於 team_a");
        assertEquals(409, call("POST", "/api/workers/W002/clock-in", admin, Map.of("orderId", "ORD-2026-014")).getResponse().getStatus(),
                "014 尚未施工");
        JsonNode after = json(call("GET", CERT_URL, farmer, null)).get("data");
        assertEquals(7, after.get("workerCount").asInt());
        assertTrue(after.get("issuable").asBoolean());
        assertFalse(verify("LEC-ORD-2026-013", code).get("valid").asBoolean(), "內容更新後舊查驗碼失效");

        // 4. 出勤期間有人退保 → 不可開立並指出日期
        assertEquals(200, call("POST", "/api/insurance/records/INS-W007-health-20260301/withdraw", admin,
                Map.of("withdrawDate", "2026-09-26")).getResponse().getStatus());
        JsonNode uninsured = json(call("GET", CERT_URL, farmer, null)).get("data");
        assertFalse(uninsured.get("issuable").asBoolean());
        assertTrue(uninsured.get("verifyCode").isNull());
        assertTrue(uninsured.get("problems").toString().contains("布迪 於 2026-09-26 未完整加保"), uninsured.get("problems").toString());

        // 5. 工作許可效期早於出勤日 → 不可開立
        assertEquals(200, call("PUT", "/api/workers/W008", admin, Map.of("permitExpiry", "2026-09-26")).getResponse().getStatus());
        JsonNode noPermit = json(call("GET", CERT_URL, farmer, null)).get("data");
        assertTrue(noPermit.get("problems").toString().contains("蒂薇 於 2026-09-27 無有效工作許可"), noPermit.get("problems").toString());
    }

    @Test
    void permissionsAndNotStartedOrder() throws Exception {
        String farmer = farmerToken();
        JsonNode pending = json(call("GET", "/api/certificates/orders/ORD-2026-014", farmer, null)).get("data");
        assertFalse(pending.get("issuable").asBoolean());
        assertTrue(pending.get("problems").toString().contains("尚未出工"));
        assertEquals(403, call("GET", "/api/certificates/orders/ORD-2026-002", farmer, null).getResponse().getStatus(), "他人訂單");
        assertEquals(401, call("GET", CERT_URL, null, null).getResponse().getStatus());
        assertEquals(404, call("GET", "/api/certificates/orders/ORD-9999-999", admin, null).getResponse().getStatus());
    }
}
