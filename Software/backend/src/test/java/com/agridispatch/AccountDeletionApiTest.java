package com.agridispatch;

import com.agridispatch.entity.LineUser;
import com.agridispatch.entity.Order;
import com.agridispatch.entity.PushToken;
import com.agridispatch.repository.*;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 刪除帳號申請（production 模式）：App 內申請／撤回、免登入網頁申請、後台完成與不受理、刪除後無法登入與資料保留
 * 種子：farmer（F001，ORD-2026-013 施工中、ORD-2026-014 待確認）、W001（工人）
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:agri_account_deletion_test;DB_CLOSE_DELAY=-1",
        "agri.demo.enabled=false",
        "agri.security.token-secret=unit-test-token-secret-0123456789abcdef",
        "agri.security.admin-password=Admin#Pass2026"
})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class AccountDeletionApiTest extends ApiTestSupport {

    @Autowired OrderRepository orderRepository;
    @Autowired UserRepository userRepository;
    @Autowired WorkerRepository workerRepository;
    @Autowired FarmRepository farmRepository;
    @Autowired LineUserRepository lineUserRepository;
    @Autowired PushTokenRepository pushTokenRepository;

    private String admin;

    @BeforeEach
    void setUp() throws Exception {
        admin = login("admin_tech", "Admin#Pass2026");
    }

    private String loginAs(String identifier) throws Exception {
        assertEquals(200, call("POST", "/api/auth/reset-password", admin,
                Map.of("identifier", identifier, "newPassword", "Delete#2026pw")).getResponse().getStatus());
        return login(identifier, "Delete#2026pw");
    }

    private int status(String method, String url, String token, Object body) throws Exception {
        return call(method, url, token, body).getResponse().getStatus();
    }

    private JsonNode adminList() throws Exception {
        return json(call("GET", "/api/account-deletions", admin, null)).get("data");
    }

    private MvcResult webRequest(String ip, Map<String, Object> body) throws Exception {
        return mvc.perform(post("/api/public/account-deletion").contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(body)).with(r -> { r.setRemoteAddr(ip); return r; })).andReturn();
    }

    @Test
    void farmerRequestsWithdrawsAndRequestsAgain() throws Exception {
        String farmer = loginAs("farmer");
        MvcResult r = call("POST", "/api/account/deletion-request", farmer, Map.of("reason", "不再使用"));
        assertEquals(200, r.getResponse().getStatus(), r.getResponse().getContentAsString());
        assertEquals("pending", json(r).get("data").get("status").asText());
        assertEquals(409, status("POST", "/api/account/deletion-request", farmer, Map.of()), "同時只能有一筆待處理");
        assertEquals("pending", json(call("GET", "/api/account/deletion-request", farmer, null)).get("data").get("status").asText());

        assertEquals("withdrawn", json(call("DELETE", "/api/account/deletion-request", farmer, null)).get("data").get("status").asText());
        assertEquals(404, status("DELETE", "/api/account/deletion-request", farmer, null));
        assertEquals(200, status("POST", "/api/account/deletion-request", farmer, Map.of()));
        // 本人看不到後台欄位
        assertNull(json(call("GET", "/api/account/deletion-request", farmer, null)).get("data").get("username"));
    }

    @Test
    void farmerWithActiveOrdersIsBlockedThenDeletedKeepingOrders() throws Exception {
        String farmer = loginAs("farmer");
        String id = json(call("POST", "/api/account/deletion-request", farmer, Map.of())).get("data").get("id").asText();
        JsonNode row = adminList().get(0);
        assertEquals(id, row.get("id").asText());
        assertTrue(row.get("warnings").toString().contains("未完成的訂單"), row.toString());
        assertEquals(409, status("POST", "/api/account-deletions/" + id + "/complete", admin, Map.of()));

        // 綁 LINE、登記推播裝置，確認刪除時一起清掉
        LineUser lu = new LineUser("LU-T1", "farmer", "U" + "d".repeat(32));
        lu.setUsername("farmer"); lu.setRefId("F001"); lu.setRole("farmer"); lu.setIsBound("Y");
        lineUserRepository.save(lu);
        PushToken pt = new PushToken("e".repeat(64), "farmer-device");
        pt.setUsername("farmer"); pt.setRefId("F001"); pt.setRole("farmer");
        pushTokenRepository.save(pt);

        List<Order> mine = orderRepository.findAll().stream().filter(o -> "F001".equalsIgnoreCase(o.getFarmerId())).toList();
        mine.forEach(o -> { if (!"cancelled".equals(o.getStatus())) o.setStatus("completed"); });
        orderRepository.saveAll(mine);

        MvcResult done = call("POST", "/api/account-deletions/" + id + "/complete", admin, Map.of("note", "電話核對本人"));
        assertEquals(200, done.getResponse().getStatus(), done.getResponse().getContentAsString());
        String summary = json(done).get("data").get("resultSummary").asText();
        assertTrue(summary.contains("刪除登入帳號") && summary.contains("LINE") && summary.contains("推播"), summary);

        assertFalse(userRepository.existsById("farmer"));
        assertEquals(401, status("GET", "/api/auth/me", farmer, null), "已登入的 token 立即失效");
        assertEquals(401, call("POST", "/api/auth/login", null, Map.of("identifier", "farmer", "password", "Delete#2026pw")).getResponse().getStatus());
        assertEquals("N", lineUserRepository.findById("LU-T1").orElseThrow().getIsBound());
        assertTrue(pushTokenRepository.findByUsername("farmer").isEmpty());
        farmRepository.findByFarmerIdOrderByCreatedAtAscIdAsc("F001").forEach(f -> {
            assertEquals("（已刪除）", f.getPhone());
            assertEquals("（已刪除）", f.getAddress());
        });
        assertEquals(mine.size(), orderRepository.findAll().stream().filter(o -> "F001".equalsIgnoreCase(o.getFarmerId())).count(), "訂單依法保留");
        assertEquals(409, status("POST", "/api/account-deletions/" + id + "/complete", admin, Map.of()), "已處理不能重複");
    }

    @Test
    void workerDeletionRemovesLoginButKeepsEmploymentRecord() throws Exception {
        String worker = loginAs("W001");
        String id = json(call("POST", "/api/account/deletion-request", worker, Map.of())).get("data").get("id").asText();
        assertEquals(200, status("POST", "/api/account-deletions/" + id + "/complete", admin, Map.of()));
        assertEquals(401, status("GET", "/api/auth/me", worker, null));
        assertEquals(401, call("POST", "/api/auth/login", null, Map.of("identifier", "W001", "password", "Delete#2026pw")).getResponse().getStatus());
        var w = workerRepository.findById("W001").orElseThrow();
        assertNull(w.getPasswordHash());
        assertNotNull(w.getName(), "聘僱紀錄由合作社保存");
    }

    @Test
    void webRequestNeedsIdentifierAndIsThrottled() throws Exception {
        MvcResult r = webRequest("10.0.0.1", Map.of("name", "陳建龍", "phone", "0912-345-678", "account", "farmer", "reason", "換手機了"));
        assertEquals(200, r.getResponse().getStatus(), r.getResponse().getContentAsString());
        // 帳號不存在也回一樣的訊息
        String msg = json(r).get("message").asText();
        assertEquals(msg, json(webRequest("10.0.0.1", Map.of("name", "某人", "phone", "0900000000", "account", "nobody"))).get("message").asText());
        assertEquals(400, webRequest("10.0.0.1", Map.of("name", "某人", "phone", "abc")).getResponse().getStatus());

        JsonNode row = null;
        for (JsonNode n : adminList()) if ("farmer".equals(n.path("accountHint").asText())) row = n;
        assertNotNull(row);
        assertEquals("web", row.get("source").asText());
        assertEquals("farmer", row.get("suggestedAccount").asText());
        String id = row.get("id").asText();
        assertEquals(400, status("POST", "/api/account-deletions/" + id + "/complete", admin, Map.of()), "網頁申請要指定帳號");
        assertEquals(404, status("POST", "/api/account-deletions/" + id + "/complete", admin, Map.of("identifier", "nobody")));

        // 同一 IP 每小時 5 次（前面已用 3 次）
        webRequest("10.0.0.1", Map.of("name", "某人", "phone", "0900000001"));
        webRequest("10.0.0.1", Map.of("name", "某人", "phone", "0900000002"));
        assertEquals(429, webRequest("10.0.0.1", Map.of("name", "某人", "phone", "0900000003")).getResponse().getStatus());
        assertEquals(200, webRequest("10.0.0.2", Map.of("name", "某人", "phone", "0900000003")).getResponse().getStatus());
    }

    @Test
    void permissionsAndReject() throws Exception {
        assertEquals(400, status("POST", "/api/account/deletion-request", admin, Map.of()), "後台帳號不在此申請");
        String worker = loginAs("W001");
        assertEquals(403, status("GET", "/api/account-deletions", worker, null));
        assertEquals(401, status("POST", "/api/account/deletion-request", null, Map.of()));
        String id = json(call("POST", "/api/account/deletion-request", worker, Map.of())).get("data").get("id").asText();
        assertEquals(403, status("POST", "/api/account-deletions/" + id + "/complete", worker, Map.of()));
        assertEquals(400, status("POST", "/api/account-deletions/" + id + "/reject", admin, Map.of()), "不受理要填原因");
        MvcResult r = call("POST", "/api/account-deletions/" + id + "/reject", admin, Map.of("note", "無法核對身分"));
        assertEquals("rejected", json(r).get("data").get("status").asText());
        assertEquals("rejected", json(call("GET", "/api/account/deletion-request", worker, null)).get("data").get("status").asText());
        assertEquals(200, status("GET", "/api/auth/me", worker, null), "不受理不影響帳號");
    }
}
