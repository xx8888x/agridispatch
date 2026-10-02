package com.agridispatch;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * LINE 雙帳號 P2：內部端點（X-Internal-Key）、LIFF 綁定與解除、身分查詢、農場主訂單查詢
 * 規格：07_系統分析及文件/05_LINE官方帳號拆分規格.md 第伍、陸章、拾參章
 * demo 模式且未設定 LINE_LOGIN_CHANNEL_ID → 綁定接受 mock:<userId> 假 token；LINE Bot 位址指向不存在的埠（通知失敗不影響結果）
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:agri_line_binding_test;DB_CLOSE_DELAY=-1",
        "agri.demo.enabled=true",
        "agri.security.token-secret=unit-test-token-secret-0123456789abcdef",
        "agri.payment.provider=mock",
        "agri.linebot.internal-key=test-internal-key-0123456789",
        "agri.linebot.base-url=http://127.0.0.1:1"
})
@AutoConfigureMockMvc
class LineBindingApiTest extends ApiTestSupport {

    private static final String KEY = "test-internal-key-0123456789";
    private static int seq = 0;

    /** 每個測試用不同的 LINE userId，避免互相影響 */
    private static String newLineUserId() {
        return String.format("U%032x", 0xabc000 + (++seq));
    }

    private MvcResult internal(String path, String key, Map<String, Object> body) throws Exception {
        var b = post("/api/line/internal/" + path).contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(body));
        if (key != null) b = b.header("X-Internal-Key", key);
        return mvc.perform(b).andReturn();
    }

    private JsonNode identity(String channel, String lineUserId) throws Exception {
        MvcResult r = internal("identity", KEY, Map.of("channel", channel, "lineUserId", lineUserId));
        assertEquals(200, r.getResponse().getStatus(), r.getResponse().getContentAsString());
        return json(r);
    }

    private MvcResult bind(String token, String channel, String lineUserId) throws Exception {
        return call("POST", "/api/line/bind", token, Map.of("channel", channel, "idToken", "mock:" + lineUserId));
    }

    /** 先解除此人在該帳號既有的綁定（測試共用同一個 Spring context，其他測試可能已綁過）再綁定 */
    private MvcResult rebind(String token, String channel, String lineUserId) throws Exception {
        call("DELETE", "/api/line/bind/" + channel, token, null);
        return bind(token, channel, lineUserId);
    }

    // ------------------------------------------------------------------ 內部端點金鑰

    @Test
    void internalEndpointsRequireKey() throws Exception {
        Map<String, Object> body = Map.of("channel", "farmer", "lineUserId", newLineUserId());
        assertEquals(403, internal("identity", null, body).getResponse().getStatus());
        assertEquals(403, internal("identity", "wrong-key", body).getResponse().getStatus());
        assertEquals(403, internal("follow-state", "wrong-key", body).getResponse().getStatus());
        assertEquals(403, internal("query", null, Map.of("channel", "farmer", "action", "rates")).getResponse().getStatus());
        assertEquals(200, internal("identity", KEY, body).getResponse().getStatus());
        assertEquals(400, internal("identity", KEY, Map.of("channel", "boss", "lineUserId", "U1")).getResponse().getStatus());
    }

    // ------------------------------------------------------------------ 追蹤與語言

    @Test
    void followStateRecordsLanguageAndChosenLanguageIsLocked() throws Exception {
        String uid = newLineUserId();
        JsonNode id = identity("worker", uid);
        assertFalse(id.get("bound").asBoolean());
        assertEquals("zh", id.get("lang").asText());
        assertEquals("worker_guest", id.get("menuKey").asText());

        // 加好友：LINE 介面語言 id（未鎖定）
        internal("follow-state", KEY, Map.of("channel", "worker", "lineUserId", uid, "followed", true, "lang", "id"));
        assertEquals("id", identity("worker", uid).get("lang").asText());

        // 主動選越語（鎖定），之後的預設值不覆蓋
        internal("follow-state", KEY, Map.of("channel", "worker", "lineUserId", uid, "lang", "vi", "langLocked", true));
        internal("follow-state", KEY, Map.of("channel", "worker", "lineUserId", uid, "lang", "id"));
        JsonNode after = identity("worker", uid);
        assertEquals("vi", after.get("lang").asText());
        assertTrue(after.get("langLocked").asBoolean());

        // 封鎖
        internal("follow-state", KEY, Map.of("channel", "worker", "lineUserId", uid, "followed", false));
        assertFalse(identity("worker", uid).get("followed").asBoolean());

        assertEquals(400, internal("follow-state", KEY, Map.of("channel", "worker", "lineUserId", uid, "lang", "fr"))
                .getResponse().getStatus());
    }

    // ------------------------------------------------------------------ 綁定

    @Test
    void farmerBindsFarmerChannelAndIdentityPersists() throws Exception {
        String uid = newLineUserId();
        MvcResult r = rebind(login("farmer", "123456"), "farmer", uid);
        assertEquals(200, r.getResponse().getStatus(), r.getResponse().getContentAsString());
        assertEquals("farmer_main", json(r).get("menuKey").asText());

        JsonNode id = identity("farmer", uid);
        assertTrue(id.get("bound").asBoolean());
        assertEquals("farmer", id.get("role").asText());
        assertEquals("farmer_main", id.get("menuKey").asText());
        // 同一個 LINE userId 在工作者帳號是另一筆，仍未綁定
        assertFalse(identity("worker", uid).get("bound").asBoolean());

        // 重複綁定同一組：冪等
        assertEquals(200, bind(login("farmer", "123456"), "farmer", uid).getResponse().getStatus());
    }

    @Test
    void roleMustMatchChannel() throws Exception {
        // 驗收 #9：農場主身分在工作者帳號綁定 → 409
        MvcResult r = bind(login("farmer", "123456"), "worker", newLineUserId());
        assertEquals(409, r.getResponse().getStatus());
        assertTrue(json(r).get("error").asText().contains("智農派工（農場主）"));
        // 工人在農場主帳號綁定 → 409
        assertEquals(409, bind(login("W001", "123456"), "farmer", newLineUserId()).getResponse().getStatus());
        // admin 不使用 LINE
        assertEquals(409, bind(login("admin_tech", "admin888"), "farmer", newLineUserId()).getResponse().getStatus());
        // 未登入 → 401
        assertEquals(401, bind(null, "farmer", newLineUserId()).getResponse().getStatus());
    }

    @Test
    void workerIdentityCarriesNationalitySubRoleAndMenu() throws Exception {
        String vn = newLineUserId();
        assertEquals(200, rebind(login("W001", "123456"), "worker", vn).getResponse().getStatus());
        JsonNode v = identity("worker", vn);
        assertEquals("worker_foreign", v.get("role").asText());
        assertEquals("VN", v.get("nationality").asText());
        assertEquals("vi", v.get("lang").asText());
        assertEquals("worker_main_vi", v.get("menuKey").asText());
        assertTrue(v.get("isLeader").asBoolean());

        String idn = newLineUserId();
        rebind(login("W006", "123456"), "worker", idn);
        assertEquals("worker_main_id", identity("worker", idn).get("menuKey").asText());

        String th = newLineUserId();
        rebind(login("W020", "123456"), "worker", th);
        JsonNode t = identity("worker", th);
        assertEquals("TH", t.get("nationality").asText());
        assertEquals("worker_main_zh", t.get("menuKey").asText()); // 未開放國別暫用中文

        String mach = newLineUserId();
        rebind(login("W012", "123456"), "worker", mach);
        JsonNode m = identity("worker", mach);
        assertEquals("worker_local", m.get("role").asText());
        assertEquals("MACHINERY", m.get("subRole").asText());
        assertEquals("zh", m.get("lang").asText());

        String fore = newLineUserId();
        rebind(login("W026", "123456"), "worker", fore);
        assertEquals("FOREMAN", identity("worker", fore).get("subRole").asText());
    }

    @Test
    void lockedLanguageSurvivesBinding() throws Exception {
        String uid = newLineUserId();
        internal("follow-state", KEY, Map.of("channel", "worker", "lineUserId", uid, "lang", "zh", "langLocked", true));
        bind(login("W002", "123456"), "worker", uid); // 越南籍
        JsonNode id = identity("worker", uid);
        assertEquals("zh", id.get("lang").asText());
        assertEquals("worker_main_zh", id.get("menuKey").asText());
    }

    @Test
    void oneLineAccountPerPersonPerChannel() throws Exception {
        String uid = newLineUserId();
        assertEquals(200, bind(login("W003", "123456"), "worker", uid).getResponse().getStatus());
        // 別人想綁同一個 LINE 帳號
        assertEquals(409, bind(login("W004", "123456"), "worker", uid).getResponse().getStatus());
        // 同一個人想再綁另一個 LINE 帳號
        assertEquals(409, bind(login("W003", "123456"), "worker", newLineUserId()).getResponse().getStatus());
        // 同一位工人用 users 帳號登入（worker_foreign → W001）與工號登入（W001）視為同一人
        String w1 = newLineUserId();
        assertEquals(200, rebind(login("W001", "123456"), "worker", w1).getResponse().getStatus());
        assertEquals(409, bind(login("worker_foreign", "123456"), "worker", newLineUserId()).getResponse().getStatus());
    }

    @Test
    void rejectsMalformedMockToken() throws Exception {
        MvcResult r = call("POST", "/api/line/bind", login("farmer", "123456"), Map.of("channel", "farmer", "idToken", "mock:U_FAKE"));
        assertEquals(400, r.getResponse().getStatus());
        r = call("POST", "/api/line/bind", login("farmer", "123456"), Map.of("channel", "farmer", "idToken", "eyJhbGciOi.real.token"));
        assertEquals(503, r.getResponse().getStatus()); // 未設定 LINE Login channel，真 token 無法驗證
    }

    // ------------------------------------------------------------------ 解除綁定

    @Test
    void unbindByUserAndAdmin() throws Exception {
        String uid = newLineUserId();
        String w5 = login("W005", "123456");
        bind(w5, "worker", uid);
        assertEquals(200, call("DELETE", "/api/line/bind/worker", w5, null).getResponse().getStatus());
        JsonNode id = identity("worker", uid);
        assertFalse(id.get("bound").asBoolean());
        assertEquals("worker_guest", id.get("menuKey").asText());
        assertEquals(404, call("DELETE", "/api/line/bind/worker", w5, null).getResponse().getStatus());

        // 解除後可改綁另一個 LINE 帳號
        String uid2 = newLineUserId();
        assertEquals(200, bind(w5, "worker", uid2).getResponse().getStatus());

        // admin 清單（LINE userId 遮罩）與強制解除
        String admin = login("admin_tech", "admin888");
        MvcResult list = call("GET", "/api/line/bindings", admin, null);
        assertEquals(200, list.getResponse().getStatus());
        assertFalse(list.getResponse().getContentAsString().contains(uid2));
        String rowId = null;
        for (JsonNode b : json(list).get("bindings")) {
            if (b.get("bound").asBoolean() && "W005".equals(b.get("refId").asText())) rowId = b.get("id").asText();
        }
        assertNotNull(rowId);
        assertEquals(403, call("GET", "/api/line/bindings", w5, null).getResponse().getStatus());
        assertEquals(403, call("DELETE", "/api/line/bindings/" + rowId, w5, null).getResponse().getStatus());
        assertEquals(200, call("DELETE", "/api/line/bindings/" + rowId, admin, null).getResponse().getStatus());
        assertFalse(identity("worker", uid2).get("bound").asBoolean());
        assertEquals(409, call("DELETE", "/api/line/bindings/" + rowId, admin, null).getResponse().getStatus());
    }

    // ------------------------------------------------------------------ 查詢

    @Test
    void farmerOrderQueryReturnsOnlyOwnOrders() throws Exception {
        String uid = newLineUserId();
        Map<String, Object> q = new LinkedHashMap<>(Map.of("channel", "farmer", "lineUserId", uid, "action", "orders", "text", "查訂單"));
        // 未綁定 → 空清單（LINE Bot 會改回綁定卡）
        assertEquals(0, json(internal("query", KEY, q)).get("messages").size());

        rebind(login("farmer", "123456"), "farmer", uid); // F001
        JsonNode msgs = json(internal("query", KEY, q)).get("messages");
        assertEquals(1, msgs.size());
        String alt = msgs.get(0).get("altText").asText();
        assertTrue(alt.contains("ORD-2026-001") || alt.contains("ORD-2026-013") || alt.contains("ORD-2026-014"), alt);

        // 指定別人的單號也只會回本人的訂單
        q.put("text", "ord-2026-002");
        String alt2 = json(internal("query", KEY, q)).get("messages").get(0).get("altText").asText();
        assertFalse(alt2.contains("ORD-2026-002"), alt2);

        // 沒有訂單的農場主 → 文字提示
        String uid2 = newLineUserId();
        rebind(login("farmer_yilan", "123456"), "farmer", uid2);
        JsonNode none = json(internal("query", KEY, Map.of("channel", "farmer", "lineUserId", uid2, "action", "orders")))
                .get("messages");
        assertEquals("text", none.get(0).get("type").asText());

        // 費率、產曆（公開）
        assertEquals(1, json(internal("query", KEY, Map.of("channel", "farmer", "lineUserId", newLineUserId(), "action", "rates")))
                .get("messages").size());
        assertEquals(1, json(internal("query", KEY, Map.of("channel", "farmer", "lineUserId", newLineUserId(), "action", "calendar")))
                .get("messages").size());
        // 工作者帳號不回農場主資料
        assertEquals(0, json(internal("query", KEY, Map.of("channel", "worker", "lineUserId", uid, "action", "rates")))
                .get("messages").size());
    }
}
