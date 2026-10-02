package com.agridispatch;

import com.agridispatch.entity.Order;
import com.agridispatch.repository.OrderRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 工作者帳號推播時段（規格第柒章第 4 點【待確認】）：時段外不推、只回報略過人數；農場主帳號不受限
 * worker-hours 設為 00:00-00:00（空時段），任何時間都在時段外
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:agri_line_quiet_test;DB_CLOSE_DELAY=-1",
        "agri.demo.enabled=true",
        "agri.security.token-secret=unit-test-token-secret-0123456789abcdef",
        "agri.payment.provider=mock",
        "agri.line.notify.async=false",
        "agri.line.notify.worker-hours=00:00-00:00"
})
@AutoConfigureMockMvc
@Import(RecordingLineBotClient.Config.class)
class LineNotifyQuietHoursTest extends ApiTestSupport {

    @Autowired RecordingLineBotClient bot;
    @Autowired OrderRepository orderRepository;

    @Test
    void workerPushSkippedOutsideHoursFarmerStillNotified() throws Exception {
        assertEquals(200, call("POST", "/api/line/bind", login("W001", "123456"),
                Map.of("channel", "worker", "idToken", "mock:U" + "c".repeat(31) + "1")).getResponse().getStatus());
        assertEquals(200, call("POST", "/api/line/bind", login("farmer", "123456"),
                Map.of("channel", "farmer", "idToken", "mock:U" + "d".repeat(31) + "1")).getResponse().getStatus());
        Order o = orderRepository.findById("ORD-2026-001").orElseThrow();
        o.setFarmerId("F001");
        o.setStatus("in_progress");
        o.setStartDate(LocalDate.now().plusDays(1));
        o.setEndDate(LocalDate.now().plusDays(1));
        o.setCrewWorkerIds("W001");
        orderRepository.save(o);
        bot.clear();

        String admin = login("admin_tech", "admin888");
        MvcResult r = call("POST", "/api/line/notify/tomorrow", admin, Map.of());
        JsonNode s = json(r);
        assertEquals(0, s.get("workerRecipients").asInt());
        assertTrue(s.get("skippedQuietHours").asInt() >= 1);
        assertTrue(bot.of("worker", "tomorrow_task").isEmpty());

        // 農場主帳號不受工作者推播時段限制
        call("PUT", "/api/orders/ORD-2026-001/status", admin, Map.of("status", "completed"));
        assertEquals(1, bot.of("farmer", "order_status").size());
        assertFalse(json(call("GET", "/api/line/notify/status", admin, null)).get("inWorkerHours").asBoolean());
    }
}
