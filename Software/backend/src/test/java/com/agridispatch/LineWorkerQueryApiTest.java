package com.agridispatch;

import com.agridispatch.entity.DispatchTeam;
import com.agridispatch.entity.Order;
import com.agridispatch.repository.DispatchTeamRepository;
import com.agridispatch.repository.OrderRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * LINE 工作者帳號本人查詢：POST /api/line/internal/query（channel=worker，action=task／weather／salary）回 data
 * 規格：07_系統分析及文件/05_LINE官方帳號拆分規格.md 第 4.2 節 W-01、W-03、W-04、第拾章第 3 點
 * 種子：W026（林小祥）為工班 B 本國籍領班；測試把 ORD-2026-005 改成今天、出工名單只有 W026
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:agri_line_worker_query_test;DB_CLOSE_DELAY=-1",
        "agri.demo.enabled=true",
        "agri.features.foreign-workers=false",
        "agri.security.token-secret=unit-test-token-secret-0123456789abcdef",
        "agri.payment.provider=mock",
        "agri.linebot.internal-key=test-internal-key-0123456789",
        "agri.linebot.base-url=http://127.0.0.1:1"
})
@AutoConfigureMockMvc
class LineWorkerQueryApiTest extends ApiTestSupport {

    private static final String KEY = "test-internal-key-0123456789";
    private static final String W026_LINE = String.format("U%032x", 0x5026);

    @Autowired OrderRepository orderRepository;
    @Autowired DispatchTeamRepository teamRepository;

    @BeforeEach
    void setUp() throws Exception {
        String token = login("W026", "123456");
        call("DELETE", "/api/line/bind/worker", token, null);
        assertEquals(200, call("POST", "/api/line/bind", token, Map.of("channel", "worker", "idToken", "mock:" + W026_LINE))
                .getResponse().getStatus());

        Order o = orderRepository.findById("ORD-2026-005").orElseThrow();
        o.setStartDate(LocalDate.now());
        o.setEndDate(LocalDate.now());
        o.setStatus("confirmed");
        o.setAssignedTeamId("team_b");
        o.setCrewWorkerIds("W026");
        orderRepository.save(o);
    }

    private JsonNode query(String channel, String lineUserId, String action) throws Exception {
        MvcResult r = mvc.perform(post("/api/line/internal/query").contentType(MediaType.APPLICATION_JSON)
                .header("X-Internal-Key", KEY)
                .content(om.writeValueAsString(Map.of("channel", channel, "lineUserId", lineUserId, "action", action)))).andReturn();
        assertEquals(200, r.getResponse().getStatus(), r.getResponse().getContentAsString());
        return json(r);
    }

    private static JsonNode item(JsonNode data, String orderId) {
        for (JsonNode i : data.get("items")) if (orderId.equals(i.get("orderId").asText())) return i;
        return fail("找不到 " + orderId + "：" + data);
    }

    @Test
    void taskReturnsOwnTodayScheduleWithoutPricingAndCrewSummaryForLeader() throws Exception {
        JsonNode res = query("worker", W026_LINE, "task");
        assertEquals(0, res.get("messages").size());
        JsonNode d = res.get("data");
        assertEquals("task", d.get("kind").asText());
        assertEquals("W026", d.get("workerId").asText());
        assertEquals(LocalDate.now().toString(), d.get("date").asText());
        assertFalse(d.get("machinery").asBoolean());

        JsonNode i = item(d, "ORD-2026-005");
        assertEquals(orderRepository.findById("ORD-2026-005").orElseThrow().getFarmLocation(), i.get("place").asText());
        for (String hidden : List.of("finalAmount", "depositAmount", "unitRate", "phone", "farmerId", "farmerName", "plan"))
            assertFalse(i.has(hidden), "不應回傳 " + hidden);

        JsonNode crew = d.get("crew");
        assertTrue(crew.isObject(), "領班應附工班摘要");
        assertTrue(crew.get("total").asInt() >= 1);
        assertEquals(crew.get("total").asInt() - crew.get("present").asInt(), crew.get("absentNames").size());
    }

    @Test
    void weatherShowsFarmPlaceAndHidesAdminMessage() throws Exception {
        {
            JsonNode d = query("worker", W026_LINE, "weather").get("data");
            assertEquals("weather", d.get("kind").asText());
            assertTrue(d.hasNonNull("weather"));
            assertFalse(d.has("weatherMessage"), "後台天候訊息含營收數字，不應給工人");
            assertFalse(d.has("securedRevenue"));
            JsonNode i = item(d, "ORD-2026-005");
            assertFalse(i.has("indoor"), "暴雨避險（室內）已移除");
            assertTrue(i.hasNonNull("place"));
        }
    }

    @Test
    void salaryIsOwnSimulatedPayroll() throws Exception {
        JsonNode d = query("worker", W026_LINE, "salary").get("data");
        assertEquals("salary", d.get("kind").asText());
        assertTrue(d.get("simulated").asBoolean());
        assertEquals(d.get("grossPay").asInt() - d.get("totalDeductions").asInt(), d.get("netPay").asInt());
        assertFalse(d.has("bankAccount"));
        assertTrue(d.get("payDate").asText().matches("\\d{4}-\\d{2}-\\d{2}"), d.toString());
        assertTrue(d.get("month").asText().matches("\\d{4}-\\d{2}"), d.toString());
    }

    @Test
    void noDataForUnboundUnknownActionOrFarmerChannel() throws Exception {
        assertFalse(query("worker", String.format("U%032x", 0x5999), "task").has("data"), "未綁定");
        assertFalse(query("worker", W026_LINE, "orders").has("data"), "不支援的動作");
        assertFalse(query("farmer", W026_LINE, "task").has("data"), "農場主帳號");
    }
}
