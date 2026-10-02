package com.agridispatch;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 工班合法化（黑轉白）API（production 模式，驗證擁有者權限）
 * 種子：F001 七筆（LGW-000001～007：2 本國、1 配偶、1 僑生、1 借用、2 失聯）、F002 兩筆（LGW-000008～009）
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:agri_legalize_test;DB_CLOSE_DELAY=-1",
        "agri.demo.enabled=false",
        "agri.security.token-secret=unit-test-token-secret-0123456789abcdef",
        "agri.security.admin-password=Admin#Pass2026"
})
@AutoConfigureMockMvc
class LegalizeApiTest extends ApiTestSupport {

    private String adminToken;

    @BeforeEach
    void setUp() throws Exception {
        adminToken = login("admin_tech", "Admin#Pass2026");
    }

    private String userToken(String username, String pw) throws Exception {
        MvcResult r = call("POST", "/api/auth/reset-password", adminToken, Map.of("identifier", username, "newPassword", pw));
        assertEquals(200, r.getResponse().getStatus());
        return login(username, pw);
    }

    private int status(String method, String url, String token, Object body) throws Exception {
        return call(method, url, token, body).getResponse().getStatus();
    }

    @Test
    void typesArePublicAndCarryLegalText() throws Exception {
        MvcResult r = call("GET", "/api/legalize/types", null, null);
        assertEquals(200, r.getResponse().getStatus());
        JsonNode d = json(r).get("data");
        assertEquals(5, d.size());
        assertEquals("local", d.get(0).get("key").asText());
        assertEquals("replace", d.get(4).get("path").asText());
        assertEquals(3, d.get(4).get("steps").size());
        assertTrue(d.get(4).get("note").asText().contains("【待確認】"));
    }

    @Test
    void farmerSeesOnlyOwnRoster() throws Exception {
        String f1 = userToken("farmer", "Farmer#2026pw");
        MvcResult r = call("GET", "/api/legalize/roster", f1, null);
        assertEquals(200, r.getResponse().getStatus());
        JsonNode d = json(r).get("data");
        assertEquals(7, d.size());
        for (JsonNode w : d) assertEquals("F001", w.get("farmerId").asText());
        // 失聯移工不帶姓名；路徑與狀態由後端算
        JsonNode runaway = null;
        for (JsonNode w : d) if ("runaway".equals(w.get("type").asText())) runaway = w;
        assertNotNull(runaway);
        assertTrue(runaway.get("name").isNull());
        assertEquals("replace", runaway.get("path").asText());
        assertEquals("in_use", runaway.get("status").asText());

        // ?farmerId= 對農場主無效（仍只看自己）
        JsonNode other = json(call("GET", "/api/legalize/roster?farmerId=F002", f1, null)).get("data");
        assertEquals(7, other.size());

        // admin 看全部、也可篩選
        assertEquals(9, json(call("GET", "/api/legalize/roster", adminToken, null)).get("data").size());
        assertEquals(2, json(call("GET", "/api/legalize/roster?farmerId=F002", adminToken, null)).get("data").size());
    }

    @Test
    void workerCannotUseLegalize() throws Exception {
        String w = userToken("worker_foreign", "Worker#2026pw");
        assertEquals(403, status("GET", "/api/legalize/roster", w, null));
        assertEquals(403, status("GET", "/api/legalize/summary", w, null));
        assertEquals(401, status("GET", "/api/legalize/roster", null, null));
    }

    @Test
    void addToggleAndRemoveOwnWorker() throws Exception {
        String f1 = userToken("farmer", "Farmer#2026pw");
        MvcResult r = call("POST", "/api/legalize/roster", f1, Map.of("type", "spouse", "name", "測試配偶", "job", "包裝", "daysPerWeek", 3));
        assertEquals(201, r.getResponse().getStatus());
        JsonNode d = json(r).get("data");
        String id = d.get("id").asText();
        assertTrue(id.startsWith("LGW-"));
        assertEquals("F001", d.get("farmerId").asText());
        assertEquals(5, d.get("done").size());
        assertEquals("in_progress", d.get("status").asText());

        // 勾一步
        d = json(call("PUT", "/api/legalize/roster/" + id, f1, Map.of("stepIndex", 0, "checked", true))).get("data");
        assertTrue(d.get("done").get(0).asBoolean());
        assertEquals(1, d.get("doneCount").asInt());

        // 全部完成 → legalized
        d = json(call("PUT", "/api/legalize/roster/" + id, f1, Map.of("done", List.of(true, true, true, true, true)))).get("data");
        assertEquals("legalized", d.get("status").asText());
        assertTrue(d.get("isDone").asBoolean());

        // 範圍檢查
        assertEquals(400, status("PUT", "/api/legalize/roster/" + id, f1, Map.of("stepIndex", 9, "checked", true)));
        assertEquals(400, status("PUT", "/api/legalize/roster/" + id, f1, Map.of("done", List.of(true))));
        assertEquals(400, status("POST", "/api/legalize/roster", f1, Map.of("type", "spouse", "name", "", "daysPerWeek", 3)));
        assertEquals(400, status("POST", "/api/legalize/roster", f1, Map.of("type", "nope", "name", "x")));
        assertEquals(400, status("POST", "/api/legalize/roster", f1, Map.of("type", "local", "name", "x", "daysPerWeek", 9)));

        assertEquals(200, status("DELETE", "/api/legalize/roster/" + id, f1, null));
        assertEquals(404, status("DELETE", "/api/legalize/roster/" + id, f1, null));
    }

    @Test
    void replacePathNeverStoresName() throws Exception {
        String f1 = userToken("farmer", "Farmer#2026pw");
        JsonNode d = json(call("POST", "/api/legalize/roster", f1, Map.of("type", "runaway", "name", "不該被存的名字", "job", "採收", "daysPerWeek", 6))).get("data");
        assertTrue(d.get("name").isNull());
        assertEquals("in_use", d.get("status").asText());
        String id = d.get("id").asText();
        // 第 1 步「停止使用」→ stopped；全部完成 → replaced
        d = json(call("PUT", "/api/legalize/roster/" + id, f1, Map.of("stepIndex", 0, "checked", true))).get("data");
        assertEquals("stopped", d.get("status").asText());
        assertTrue(d.get("isStopped").asBoolean());
        d = json(call("PUT", "/api/legalize/roster/" + id, f1, Map.of("done", List.of(true, true, true)))).get("data");
        assertEquals("replaced", d.get("status").asText());
        call("DELETE", "/api/legalize/roster/" + id, f1, null);
    }

    @Test
    void otherFarmerCannotTouchMyRoster() throws Exception {
        String f2 = userToken("farmer_yilan", "Farmer2#2026pw");
        assertEquals(403, status("PUT", "/api/legalize/roster/LGW-000001", f2, Map.of("stepIndex", 0, "checked", true)));
        assertEquals(403, status("DELETE", "/api/legalize/roster/LGW-000001", f2, null));
        // 自己的可以
        assertEquals(200, status("PUT", "/api/legalize/roster/LGW-000008", f2, Map.of("stepIndex", 2, "checked", true)));
        call("PUT", "/api/legalize/roster/LGW-000008", f2, Map.of("stepIndex", 2, "checked", false));
    }

    @Test
    void adminSummaryAggregatesExposureAndGaps() throws Exception {
        String f1 = userToken("farmer", "Farmer#2026pw");
        assertEquals(403, status("GET", "/api/legalize/summary", f1, null));

        JsonNode d = json(call("GET", "/api/legalize/summary", adminToken, null)).get("data");
        JsonNode totals = d.get("totals");
        assertEquals(9, totals.get("total").asInt());
        // F001：借用 1 + 失聯 2，F002：借用 1 → 4 個不可轉白，全未停止使用
        assertEquals(4, totals.get("replace").asInt());
        assertEquals(4, totals.get("exposed").asInt());
        assertEquals(4 * 15, totals.get("exposureMinWan").asInt());
        assertEquals(4 * 75, totals.get("exposureMaxWan").asInt());
        assertEquals(4, totals.get("gapOpen").asInt());
        assertEquals(1, totals.get("legalized").asInt());   // 阮氏蘭已完成
        assertEquals(2, d.get("farms").size());
        assertEquals("F001", d.get("farms").get(0).get("farmerId").asText(), "暴露最高的農場排前面");
        assertEquals("陳建龍 (阿龍班長)", d.get("farms").get(0).get("farmerName").asText(), "名稱取自登入帳號");
        assertTrue(d.get("note").asText().contains("【待確認】"));
    }

    @Test
    void demoResetIsHiddenInProduction() throws Exception {
        String f1 = userToken("farmer", "Farmer#2026pw");
        assertEquals(404, status("POST", "/api/legalize/roster/reset", f1, Map.of()));
    }
}
