package com.agridispatch;

import com.agridispatch.repository.AttendanceRepository;
import com.agridispatch.repository.OrderRepository;
import com.agridispatch.service.SeedService;
import com.agridispatch.service.payment.EcpayCheckMac;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Oracle 實機整合測試：以 oracle profile（application-oracle.yml）連到已用
 * Software/database/01_agridispatch_oracle_schema.sql 建好的 schema，跑一輪主要寫入流程。
 * 只在設定環境變數 AGRI_DB_URL（與 AGRI_DB_USERNAME、AGRI_DB_PASSWORD）時執行，平常的 mvn test 會略過。
 * 會寫入並重灌資料，請只對臨時 schema 執行（做法見 Software/database/README_ORACLE_SETUP.md「後端實測」）。
 */
@EnabledIfEnvironmentVariable(named = "AGRI_DB_URL", matches = ".+")
@ActiveProfiles("oracle")
@SpringBootTest(properties = {
        "agri.demo.enabled=false",
        "agri.security.token-secret=oracle-it-token-secret-0123456789abcdef",
        "agri.security.admin-password=Admin#Pass2026",
        "agri.payment.provider=ecpay",
        "agri.payment.ecpay.merchant-id=3002607",
        "agri.payment.ecpay.hash-key=pwFHCqoQZGmho4w6",
        "agri.payment.ecpay.hash-iv=EkRm7iFT261dpevs",
        "agri.payment.ecpay.return-url=https://example.com/api/payments/ecpay/notify"
})
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OracleIntegrationTest extends ApiTestSupport {

    private static final String HASH_KEY = "pwFHCqoQZGmho4w6";
    private static final String HASH_IV = "EkRm7iFT261dpevs";

    @Autowired JdbcTemplate jdbc;
    @Autowired OrderRepository orderRepository;
    @Autowired AttendanceRepository attendanceRepository;
    @Autowired SeedService seedService;

    private String admin;

    @BeforeEach
    void setUp() throws Exception {
        admin = login("admin_tech", "Admin#Pass2026");
    }

    private int status(MvcResult r) { return r.getResponse().getStatus(); }

    private String body(MvcResult r) throws Exception { return r.getResponse().getContentAsString(StandardCharsets.UTF_8); }

    @Test
    @Order(1)
    void startupSeedingWritesYesNoFlagsAndTimestamps() {
        // 啟動時 DataInitializer 依 ID 補齊示範資料：旗標欄位只能是 Y／N（CHECK 約束），時間欄位是 TIMESTAMP
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM workers WHERE is_leader NOT IN ('Y','N')", Integer.class));
        assertTrue(jdbc.queryForObject("SELECT COUNT(*) FROM workers WHERE is_leader = 'Y'", Integer.class) > 0);
        assertTrue(jdbc.queryForObject("SELECT COUNT(*) FROM workers", Integer.class) > 20, "Java 種子補上 W021 之後的工人");
        assertTrue(jdbc.queryForObject("SELECT COUNT(*) FROM clock_ins", Integer.class) > 0, "打卡種子以 IDENTITY 取號");
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE deposit_paid NOT IN ('Y','N')", Integer.class));
    }

    @Test
    @Order(2)
    void ordersRoundTripWithChineseTextAndTimestamps() throws Exception {
        // 工時包 DM 標題換算後 plan_name 超過 100 bytes（舊 schema 為 BYTE 語意會 ORA-12899）
        MvcResult pkg = call("POST", "/api/orders", admin, Map.of("farmerName", "冷鏈測試農場", "farmerId", "F999",
                "phone", "0912000333", "farmLocation", "屏東縣高樹鄉測試段 1 號", "packageId", "dm-coldchain-2026"));
        assertEquals(201, status(pkg), body(pkg));
        JsonNode data = json(pkg).get("data");
        assertTrue(data.get("planName").asText().length() > 34);
        assertTrue(data.get("createdAt").asText().matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}"), data.get("createdAt").asText());

        // 讀回：時間格式不變（不多 ".0"）、farmer_id 不必存在於 farmers 表
        String id = data.get("id").asText();
        JsonNode back = json(call("GET", "/api/orders/" + id, admin, null)).get("data");
        assertEquals(data.get("createdAt").asText(), back.get("createdAt").asText());
        assertEquals("F999", back.get("farmerId").asText());
        assertFalse(back.get("depositPaid").asBoolean());

        // 管理員建單不帶電話與地點也要成功（Oracle 的 NOT NULL 不可讓 API 回 500）
        MvcResult minimal = call("POST", "/api/orders", admin, Map.of("farmerName", "最少欄位", "cropId", "mango", "area", 2.0, "plan", "hourly_flex"));
        assertEquals(201, status(minimal), body(minimal));
    }

    private MockHttpServletRequestBuilder form(Map<String, String> params) {
        MockHttpServletRequestBuilder b = post("/api/payments/ecpay/notify").contentType(MediaType.APPLICATION_FORM_URLENCODED);
        params.forEach(b::param);
        return b;
    }

    @Test
    @Order(3)
    void paymentMarksDepositPaidAsY() throws Exception {
        String id = json(call("POST", "/api/orders", admin,
                Map.of("farmerName", "付款測試", "cropId", "taoyuan_veg", "area", 5.0, "plan", "early_bird"))).get("data").get("id").asText();
        JsonNode co = json(call("POST", "/api/payments/checkout", admin, Map.of("orderId", id)));
        Map<String, String> n = new LinkedHashMap<>();
        n.put("MerchantID", "3002607");
        n.put("MerchantTradeNo", co.get("transactionId").asText());
        n.put("RtnCode", "1");
        n.put("RtnMsg", "交易成功");
        n.put("TradeNo", "2310089999999999");
        n.put("TradeAmt", co.get("amount").asText());
        n.put("PaymentDate", "2026/10/08 10:00:00");
        n.put("PaymentType", "Credit_CreditCard");
        n.put("TradeDate", "2026/10/08 09:59:00");
        n.put("SimulatePaid", "0");
        n.put("CheckMacValue", EcpayCheckMac.generate(n, HASH_KEY, HASH_IV));
        assertEquals("1|OK", mvc.perform(form(n)).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertEquals("Y", jdbc.queryForObject("SELECT deposit_paid FROM orders WHERE id = ?", String.class, id));
        assertTrue(orderRepository.findById(id).orElseThrow().isDepositPaid());
    }

    @Test
    @Order(4)
    void clockInUsesIdentityAndOtherWritesSucceed() throws Exception {
        long before = attendanceRepository.count();
        MvcResult in = call("POST", "/api/attendance/clock-in", admin, Map.of("workerId", "W002", "location", "八德測試"));
        assertTrue(status(in) == 200 || status(in) == 409, body(in));   // 409＝今天已有打卡（重跑測試時）
        if (status(in) == 200) {
            assertEquals(before + 1, attendanceRepository.count());
            assertEquals(200, status(call("POST", "/api/attendance/clock-out", admin, Map.of("workerId", "W002"))));
        }

        // 公開人才報名：pdpa_consent 寫入 'Y'、registered_at 為 TIMESTAMP
        MvcResult talent = call("POST", "/api/talents/register", null, Map.of("name", "王大明", "phone", "0912000444",
                "category", "harvest", "categoryName", "採收", "pdpaConsent", true, "experience", "十年芒果採收與分級經驗"));
        assertEquals(201, status(talent), body(talent));
        String tid = json(talent).get("data").get("id").asText();
        assertEquals("Y", jdbc.queryForObject("SELECT pdpa_consent FROM talents WHERE id = ?", String.class, tid));
        assertTrue(json(talent).get("data").get("registeredAt").asText().matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}"));

        // 天候情境切換（weather_states 的 regional_json 為 CLOB）
        assertEquals(200, status(call("POST", "/api/dispatch/weather-scenario", admin, Map.of("scenarioId", "heavy_rain"))));
        assertEquals(200, status(call("POST", "/api/dispatch/weather-scenario", admin, Map.of("scenarioId", "sunny"))));

        // 文宣 DM：長文字欄位為 CLOB，封面圖可能是大張 base64
        JsonNode dm = json(call("GET", "/api/brochures/dm-mango-2026", null, null)).get("data");
        ((com.fasterxml.jackson.databind.node.ObjectNode) dm).put("coverImage", "data:image/png;base64," + "A".repeat(60_000));
        MvcResult put = call("PUT", "/api/brochures/dm-mango-2026", admin, dm.toString());
        assertEquals(200, status(put), body(put));
        assertEquals(60_000 + 22, json(call("GET", "/api/brochures/dm-mango-2026", null, null)).get("data").get("coverImage").asText().length());

        // 工人名冊讀回 isLeader（'Y'／'N' → boolean）
        boolean leaderFound = false;
        for (JsonNode w : json(call("GET", "/api/workers", admin, null)).get("data")) {
            if ("W001".equals(w.get("id").asText())) leaderFound = w.get("leader").asBoolean(w.path("isLeader").asBoolean());
        }
        assertTrue(leaderFound, "W001 為領班");
    }

    @Test
    @Order(5)
    void ratingsInsuranceSkillsMatchingAndLegalizeWrite() throws Exception {
        call("POST", "/api/auth/reset-password", admin, Map.of("identifier", "farmer", "newPassword", "Farmer#2026pw"));
        String farmer = login("farmer", "Farmer#2026pw");

        MvcResult rating = call("PUT", "/api/ratings/orders/ORD-2026-013", farmer,
                Map.of("score", 5, "tags", List.of("準時到場", "做工細心"), "comment", "芒果採收很仔細"));
        assertEquals(200, status(rating), body(rating));

        MvcResult enroll = call("POST", "/api/insurance/workers/W016/enroll", admin, Map.of("type", "health",
                "insuredSalary", 27000, "enrollDate", java.time.LocalDate.now().toString(), "filingRef", "NHI-ORA-001"));
        assertTrue(status(enroll) == 200 || status(enroll) == 409, body(enroll));

        MvcResult restore = call("POST", "/api/skills/workers/W023/harvest/restore", admin, Map.of("note", "Oracle 實測"));
        assertNotEquals(500, status(restore), body(restore));

        JsonNode crew = json(call("GET", "/api/matching/orders/ORD-2026-005/crew?teamId=team_b", admin, null)).get("data");
        List<String> picks = new ArrayList<>();
        for (JsonNode c : crew.get("candidates")) if (c.get("recommended").asBoolean()) picks.add(c.get("workerId").asText());
        MvcResult match = call("PUT", "/api/matching/orders/ORD-2026-005", admin, Map.of("teamId", "team_b", "crew", picks));
        assertEquals(200, status(match), body(match));

        MvcResult roster = call("POST", "/api/legalize/roster", farmer, Map.of("type", "local", "name", "阿財", "daysPerWeek", 2));
        assertTrue(status(roster) == 200 || status(roster) == 201, body(roster));

        // 打卡連結訂單（clock_ins.order_id → orders FK）
        MvcResult in = call("POST", "/api/workers/W009/clock-in", admin, Map.of("location", "枋山", "orderId", "ORD-2026-013"));
        assertNotEquals(500, status(in), body(in));
    }

    @Test
    @Order(6)
    void reseedRespectsForeignKeys() {
        // /api/dev/seed（demo 模式）的清空重灌：刪除順序須符合 FK（clock_ins、financial_ledger 先刪）
        assertDoesNotThrow(() -> seedService.seedAll(true));
        assertTrue(jdbc.queryForObject("SELECT COUNT(*) FROM orders", Integer.class) > 0);
        assertTrue(jdbc.queryForObject("SELECT COUNT(*) FROM clock_ins", Integer.class) > 0);
    }
}
