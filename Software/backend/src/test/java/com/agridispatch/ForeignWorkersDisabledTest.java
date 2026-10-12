package com.agridispatch;

import com.agridispatch.entity.Order;
import com.agridispatch.repository.LineUserRepository;
import com.agridispatch.repository.OrderRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 外籍移工功能下架（agri.features.foreign-workers=false）：
 * 派工媒合不列入外籍工人、不可把外籍工人排進出工名單；LINE 主動推播不推給外籍工人。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:agri_foreign_disabled_test;DB_CLOSE_DELAY=-1",
        "agri.demo.enabled=true",
        "agri.features.foreign-workers=false",
        "agri.security.token-secret=unit-test-token-secret-0123456789abcdef",
        "agri.payment.provider=mock",
        "agri.line.notify.async=false",
        "agri.line.notify.worker-hours=00:00-24:00"
})
@AutoConfigureMockMvc
@Import(RecordingLineBotClient.Config.class)
class ForeignWorkersDisabledTest extends ApiTestSupport {

    /** 種子中的外籍工人（tier1_foreign） */
    static final Set<String> FOREIGN = Set.of("W001", "W002", "W003", "W004", "W005", "W006", "W007", "W008", "W009", "W010",
            "W011", "W014", "W015", "W016", "W017", "W018", "W019", "W020", "W023", "W024");

    static final String VN_UID = "U" + "a".repeat(31) + "2";
    static final String LOCAL_UID = "U" + "c".repeat(31) + "2";

    @Autowired RecordingLineBotClient bot;
    @Autowired LineUserRepository lineUserRepository;
    @Autowired OrderRepository orderRepository;

    private String admin;

    @BeforeEach
    void setUp() throws Exception {
        admin = login("admin_tech", "admin888");
    }

    private static void assertNoForeign(JsonNode list, String what) {
        for (JsonNode c : list) assertFalse(FOREIGN.contains(c.get("workerId").asText()), what + " 不應含外籍工人：" + c.get("workerId").asText());
    }

    @Test
    void matchingExcludesForeignWorkers() throws Exception {
        JsonNode rec = json(call("GET", "/api/matching/orders/ORD-2026-005", admin, null)).get("data");
        for (JsonNode t : rec.get("teams")) {
            if ("team_b".equals(t.get("teamId").asText())) assertEquals(7, t.get("members").asInt(), "工班 B：林小祥＋本國 W033～W038");
        }
        JsonNode crew = json(call("GET", "/api/matching/orders/ORD-2026-005/crew?teamId=team_b", admin, null)).get("data");
        assertNoForeign(crew.get("candidates"), "建議名單");
        assertNoForeign(crew.get("excluded"), "排除名單");
        assertTrue(crew.get("candidates").size() > 0);

        MvcResult r = call("PUT", "/api/matching/orders/ORD-2026-005", admin, Map.of("teamId", "team_b", "crew", List.of("W026", "W006")));
        assertEquals(409, r.getResponse().getStatus());
        assertTrue(r.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8).contains("外籍移工服務暫停中"));
        assertEquals(200, call("PUT", "/api/matching/orders/ORD-2026-005", admin,
                Map.of("teamId", "team_b", "crew", List.of("W026", "W033", "W034"))).getResponse().getStatus());
    }

    @Test
    void lineNotifySkipsForeignWorkers() throws Exception {
        lineUserRepository.deleteAll();
        for (String[] b : new String[][] {{"W001", VN_UID}, {"W026", LOCAL_UID}}) {
            MvcResult r = call("POST", "/api/line/bind", login(b[0], "123456"), Map.of("channel", "worker", "idToken", "mock:" + b[1]));
            assertEquals(200, r.getResponse().getStatus(), r.getResponse().getContentAsString());
        }
        Order o = orderRepository.findById("ORD-2026-001").orElseThrow();
        LocalDate day = LocalDate.now().plusDays(1);
        o.setStatus("confirmed");
        o.setStartDate(day);
        o.setEndDate(day);
        o.setCrewWorkerIds("W001,W026");
        orderRepository.save(o);
        bot.clear();

        assertEquals(200, call("POST", "/api/line/notify/tomorrow", admin, Map.of()).getResponse().getStatus());
        Map<String, String> to = bot.recipients("worker", "tomorrow_task");
        assertTrue(to.containsKey(LOCAL_UID), to.toString());
        assertFalse(to.containsKey(VN_UID), "外籍工人不推播：" + to);
    }
}
