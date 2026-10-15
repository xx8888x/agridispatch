package com.agridispatch;

import com.agridispatch.entity.Order;
import com.agridispatch.entity.User;
import com.agridispatch.repository.OrderRepository;
import com.agridispatch.repository.UserRepository;
import com.agridispatch.security.PasswordHasher;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 開工日不得早於今天（功能測試 B-02）：
 * - 建立訂單：開工日早於今天 → 400；今天、之後、未提供日期 → 可建立
 * - 更新訂單：開工日「有改」且早於今天 → 400；沒改開工日（只改完工日或其他欄位、原樣送回）不擋
 * - 只約束農場主（含合作社帳號）；科技後台管理員（admin_tech）可補登／更正成過去的開工日
 * 最短提前天數目前為 0（可選今天）【待確認】營運規則，對應 OrderService.MIN_LEAD_DAYS。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:agri_start_date_test;DB_CLOSE_DELAY=-1",
        "agri.demo.enabled=false",
        "agri.features.foreign-workers=false",
        "agri.security.token-secret=unit-test-token-secret-0123456789abcdef",
        "agri.security.admin-password=Admin#Pass2026"
})
@AutoConfigureMockMvc
class OrderStartDateApiTest extends ApiTestSupport {

    /** 業務時區（OrderService 以台灣時間判斷「今天」） */
    private static final ZoneId TPE = ZoneId.of("Asia/Taipei");

    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PasswordHasher passwordHasher;

    private String admin;
    private String farmer;

    @BeforeEach
    void setUp() throws Exception {
        admin = login("admin_tech", "Admin#Pass2026");
        MvcResult r = call("POST", "/api/auth/reset-password", admin, Map.of("identifier", "farmer", "newPassword", "Farmer#2026pw"));
        assertEquals(200, r.getResponse().getStatus());
        farmer = login("farmer", "Farmer#2026pw");
    }

    private static LocalDate today() {
        return LocalDate.now(TPE);
    }

    private static Map<String, Object> order(LocalDate start, LocalDate end) {
        Map<String, Object> o = new HashMap<>();
        o.put("farmerName", "陳建龍");
        o.put("phone", "0912-345-678");
        o.put("farmLocation", "屏東縣枋山鄉");
        o.put("cropId", "mango");
        o.put("area", 1.0);
        o.put("plan", "early_bird");
        o.put("dispatchCategory", "harvest_grading");
        if (start != null) o.put("startDate", start.toString());
        if (end != null) o.put("endDate", end.toString());
        return o;
    }

    private static String body(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private String create(String token, LocalDate start, LocalDate end) throws Exception {
        MvcResult r = call("POST", "/api/orders", token, order(start, end));
        assertEquals(201, r.getResponse().getStatus(), body(r));
        return json(r).get("data").get("id").asText();
    }

    @Test
    void createWithStartBeforeTodayIsRejected() throws Exception {
        MvcResult r = call("POST", "/api/orders", farmer, order(today().minusDays(1), today().plusDays(1)));
        assertEquals(400, r.getResponse().getStatus(), body(r));
        String msg = json(r).get("error").asText();
        assertTrue(msg.contains("不得早於今天"), msg);
        assertTrue(msg.contains(today().toString()), "錯誤訊息要帶最早可選日期：" + msg);

        MvcResult longAgo = call("POST", "/api/orders", farmer, order(LocalDate.of(2020, 1, 1), LocalDate.of(2020, 1, 3)));
        assertEquals(400, longAgo.getResponse().getStatus(), body(longAgo));
    }

    @Test
    void createWithStartTodayOrLaterIsAccepted() throws Exception {
        String t = create(farmer, today(), today());                          // 今天（最短提前天數 0）
        String f = create(farmer, today().plusDays(1), today().plusDays(2));  // 明天起
        String far = create(farmer, today().plusDays(200), today().plusDays(201));
        assertNotEquals(t, f);
        assertNotEquals(f, far);
    }

    @Test
    void createWithoutDatesStillWorks() throws Exception {
        // 沒有開工日不檢查（工期由合作社之後排定）；只給一個日期仍由原本的規則擋下
        create(farmer, null, null);
        MvcResult half = call("POST", "/api/orders", farmer, order(today().plusDays(1), null));
        assertEquals(400, half.getResponse().getStatus(), body(half));
    }

    @Test
    void adminMayBackdateButCoopMayNot() throws Exception {
        // 科技後台管理員代建／補登：可以填過去的開工日
        Map<String, Object> o = order(today().minusDays(3), today());
        o.put("farmerId", "F001");
        MvcResult r = call("POST", "/api/orders", admin, o);
        assertEquals(201, r.getResponse().getStatus(), body(r));
        String id = json(r).get("data").get("id").asText();
        assertEquals(today().minusDays(3).toString(), json(r).get("data").get("startDate").asText());

        assertNotNull(id);

        // 合作社帳號：改開工日比照農場主，不可改成過去
        if (!userRepository.existsById("coop_start_test")) {
            userRepository.save(new User("coop_start_test", passwordHasher.hash("Coop#2026pw"), "合作社測試員", "coop_admin", "COOP01", "合作社後台人員"));
        }
        String coop = login("coop_start_test", "Coop#2026pw");
        String future = create(farmer, today().plusDays(9), today().plusDays(10));
        MvcResult c = call("PUT", "/api/orders/" + future, coop, Map.of("startDate", today().minusDays(1).toString(), "endDate", today().plusDays(10).toString()));
        assertEquals(400, c.getResponse().getStatus(), body(c));
    }

    @Test
    void updateStartDateToThePastIsRejected() throws Exception {
        String id = create(farmer, today().plusDays(5), today().plusDays(6));

        MvcResult bad = call("PUT", "/api/orders/" + id, farmer, Map.of("startDate", today().minusDays(1).toString(), "endDate", today().plusDays(6).toString()));
        assertEquals(400, bad.getResponse().getStatus(), body(bad));
        assertTrue(json(bad).get("error").asText().contains("不得早於今天"));

        // 被擋下之後工期沒有被改
        JsonNode o = json(call("GET", "/api/orders/" + id, farmer, null)).get("data");
        assertEquals(today().plusDays(5).toString(), o.get("startDate").asText());

        // 改到今天、之後都可以
        assertEquals(200, call("PUT", "/api/orders/" + id, farmer, Map.of("startDate", today().toString(), "endDate", today().plusDays(1).toString())).getResponse().getStatus());
        assertEquals(200, call("PUT", "/api/orders/" + id, farmer, Map.of("startDate", today().plusDays(30).toString(), "endDate", today().plusDays(31).toString())).getResponse().getStatus());

        // 科技後台管理員可更正成過去的日期（補登／延誤更正）
        MvcResult fixed = call("PUT", "/api/orders/" + id, admin, Map.of("startDate", today().minusDays(2).toString(), "endDate", today().plusDays(1).toString()));
        assertEquals(200, fixed.getResponse().getStatus(), body(fixed));
    }

    @Test
    void updateThatDoesNotChangeStartIsNotBlocked() throws Exception {
        String id = create(farmer, today(), today().plusDays(1));
        // 模擬「建立時還沒過、之後日子過了」的舊訂單：直接把開工日改成過去
        Order stale = orderRepository.findById(id).orElseThrow();
        stale.setStartDate(today().minusDays(4));
        stale.setEndDate(today().minusDays(3));
        orderRepository.save(stale);

        // 改現場資訊：不送工期，不擋
        MvcResult info = call("PUT", "/api/orders/" + id, farmer, Map.of("farmLocation", "屏東縣枋山鄉新地號"));
        assertEquals(200, info.getResponse().getStatus(), body(info));

        // 原樣送回同一個（已過去的）開工日，只延長完工日：開工日沒改，不擋
        MvcResult sameStart = call("PUT", "/api/orders/" + id, farmer, Map.of("startDate", today().minusDays(4).toString(), "endDate", today().plusDays(2).toString()));
        assertEquals(200, sameStart.getResponse().getStatus(), body(sameStart));

        // 只送完工日（延長）：開工日沒改，不擋
        MvcResult endOnly = call("PUT", "/api/orders/" + id, farmer, Map.of("endDate", today().plusDays(3).toString()));
        assertEquals(200, endOnly.getResponse().getStatus(), body(endOnly));

        // 但把開工日改成「另一個過去的日子」就擋
        MvcResult other = call("PUT", "/api/orders/" + id, farmer, Map.of("startDate", today().minusDays(2).toString(), "endDate", today().plusDays(3).toString()));
        assertEquals(400, other.getResponse().getStatus(), body(other));
    }
}
