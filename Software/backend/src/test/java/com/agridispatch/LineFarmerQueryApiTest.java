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
 * LINE 農場主帳號本人查詢：POST /api/line/internal/query（channel=farmer，action=orders／weather）回 data
 * 規格：07_系統分析及文件/05_LINE官方帳號拆分規格.md 第 4.1 節 F-01、F-04、第拾章第 3 點
 * 種子：F001（帳號 farmer）有 ORD-2026-001、ORD-2026-013；測試把 001 改成今天起施工、地點屏東縣枋山鄉、工班 B
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:agri_line_farmer_query_test;DB_CLOSE_DELAY=-1",
        "agri.demo.enabled=true",
        "agri.security.token-secret=unit-test-token-secret-0123456789abcdef",
        "agri.payment.provider=mock",
        "agri.linebot.internal-key=test-internal-key-0123456789",
        "agri.linebot.base-url=http://127.0.0.1:1"
})
@AutoConfigureMockMvc
class LineFarmerQueryApiTest extends ApiTestSupport {

    private static final String KEY = "test-internal-key-0123456789";
    private static final String F001_LINE = String.format("U%032x", 0xf001);

    @Autowired OrderRepository orderRepository;
    @Autowired DispatchTeamRepository teamRepository;

    @BeforeEach
    void setUp() throws Exception {
        String token = login("farmer", "123456");
        call("DELETE", "/api/line/bind/farmer", token, null);
        assertEquals(200, call("POST", "/api/line/bind", token, Map.of("channel", "farmer", "idToken", "mock:" + F001_LINE))
                .getResponse().getStatus());

        Order o = orderRepository.findById("ORD-2026-001").orElseThrow();
        o.setStatus("in_progress");
        o.setStartDate(LocalDate.now());
        o.setEndDate(LocalDate.now().plusDays(1));
        o.setFarmLocation("屏東縣枋山鄉");
        o.setFarmName(null);
        o.setAssignedTeamId("team_b");
        orderRepository.save(o);

        // 10 天後才開工：不在天候避險範圍
        Order later = orderRepository.findById("ORD-2026-013").orElseThrow();
        assertEquals("F001", later.getFarmerId());
        later.setStatus("confirmed");
        later.setStartDate(LocalDate.now().plusDays(10));
        later.setEndDate(LocalDate.now().plusDays(11));
        orderRepository.save(later);
    }

    private JsonNode query(String channel, String lineUserId, String action, String text) throws Exception {
        MvcResult r = mvc.perform(post("/api/line/internal/query").contentType(MediaType.APPLICATION_JSON)
                .header("X-Internal-Key", KEY)
                .content(om.writeValueAsString(Map.of("channel", channel, "lineUserId", lineUserId, "action", action, "text", text))))
                .andReturn();
        assertEquals(200, r.getResponse().getStatus(), r.getResponse().getContentAsString());
        return json(r);
    }

    @Test
    void weatherListsOwnActiveOrdersWithRegion() throws Exception {
        {
            JsonNode d = query("farmer", F001_LINE, "weather", "").get("data");
            assertEquals("weather", d.get("kind").asText());
            assertTrue(d.hasNonNull("weather"));
            assertFalse(d.has("weatherMessage"), "後台天候訊息含營收數字，不應給農場主");

            JsonNode mine = null;
            for (JsonNode o : d.get("orders")) {
                assertNotEquals("ORD-2026-013", o.get("id").asText(), "10 天後開工不列入");
                if ("ORD-2026-001".equals(o.get("id").asText())) mine = o;
            }
            assertNotNull(mine, d.toString());
            assertTrue(mine.get("workingToday").asBoolean());
            assertFalse(mine.has("teamIndoor"), "暴雨避險（室內）已移除");
            assertEquals("屏東縣枋山鄉", mine.get("place").asText());
            assertTrue(mine.get("region").isObject(), "屏東縣枋山鄉應對到「屏東枋山」產區：" + mine);
            assertEquals("屏東枋山", mine.get("region").get("region").asText());
        }
    }

    @Test
    void orderCardPicksInProgressAndHasNoWorkerPersonalData() throws Exception {
        JsonNode d = query("farmer", F001_LINE, "orders", "查訂單").get("data");
        JsonNode o = d.get("order");
        assertEquals("ORD-2026-001", o.get("id").asText(), "預設挑施工中的訂單");
        assertEquals(teamRepository.findById("team_b").orElseThrow().getName(), o.get("teamName").asText());
        for (String hidden : List.of("crewWorkerIds", "crew", "driver", "vehicle", "phone", "farmerId"))
            assertFalse(o.has(hidden), "不應回傳 " + hidden);
        assertTrue(d.get("others").size() <= 3);

        // 指定作物或單號
        assertEquals("ORD-2026-013", query("farmer", F001_LINE, "orders", "ORD-2026-013").get("data").get("order").get("id").asText());
    }

    @Test
    void noDataForWorkerChannelOrUnboundFarmer() throws Exception {
        assertFalse(query("worker", F001_LINE, "orders", "").has("data"));
        assertFalse(query("farmer", String.format("U%032x", 0xf999), "weather", "").has("data"));
    }
}
