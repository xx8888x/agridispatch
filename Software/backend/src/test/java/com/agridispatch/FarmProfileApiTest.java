package com.agridispatch;

import com.agridispatch.repository.FarmRepository;
import com.agridispatch.repository.FarmerRepository;
import com.agridispatch.security.LoginRateLimiter;
import com.agridispatch.security.RegisterRateLimiter;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 我的農場 /api/farmers/me/farms（預約派工第一步：可登錄多個農場，預約時選一個）
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:agri_farm_profile_test;DB_CLOSE_DELAY=-1",
        "agri.demo.enabled=false",
        "agri.security.token-secret=unit-test-token-secret-0123456789abcdef",
        "agri.security.admin-password=Admin#Pass2026"
})
@AutoConfigureMockMvc
class FarmProfileApiTest extends ApiTestSupport {

    private static final String FARMS = "/api/farmers/me/farms";

    @Autowired RegisterRateLimiter registerRateLimiter;
    @Autowired LoginRateLimiter loginRateLimiter;
    @Autowired FarmerRepository farmerRepository;
    @Autowired FarmRepository farmRepository;

    @BeforeEach
    void setUp() {
        registerRateLimiter.reset();
        loginRateLimiter.reset();
    }

    /** 自助註冊一個農場主 */
    private JsonNode register(String phone) throws Exception {
        Map<String, Object> b = new HashMap<>();
        b.put("name", "王大明");
        b.put("phone", phone);
        b.put("password", "Farm#2026pw");
        b.put("address", "桃園市八德區");
        b.put("farmName", "大明有機農場");
        b.put("pdpaConsent", true);
        MvcResult r = mvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(b))).andReturn();
        assertEquals(200, r.getResponse().getStatus());
        return json(r);
    }

    private Map<String, Object> farm(String name, String phone) {
        Map<String, Object> f = new HashMap<>();
        f.put("farmName", name);
        f.put("contactName", "王小明");
        f.put("phone", phone);
        f.put("address", "桃園市八德區興豐路 100 號 1 號棚");
        f.put("farmType", "產銷班");
        return f;
    }

    private JsonNode farms(String token) throws Exception {
        MvcResult r = call("GET", FARMS, token, null);
        assertEquals(200, r.getResponse().getStatus());
        return json(r).get("data");
    }

    @Test
    void registeredFarmBecomesFirstFarm() throws Exception {
        String token = register("0911000111").get("token").asText();
        JsonNode list = farms(token);
        assertEquals(1, list.size());
        assertEquals("大明有機農場", list.get(0).get("farmName").asText());
        assertEquals("王大明", list.get(0).get("contactName").asText());
        assertEquals("0911000111", list.get(0).get("phone").asText());
        assertEquals("桃園市八德區", list.get(0).get("address").asText());
        assertEquals(1, farms(token).size());   // 再讀不會重複轉入
    }

    @Test
    void multipleFarmsCrud() throws Exception {
        JsonNode reg = register("0911000222");
        String token = reg.get("token").asText();
        farmerRepository.deleteById(reg.get("user").get("refId").asText());   // 模擬種子帳號：沒有註冊時的農場
        assertEquals(0, farms(token).size());

        MvcResult a = call("POST", FARMS, token, farm("八德一號棚", "03-366-1234"));
        assertEquals(201, a.getResponse().getStatus());
        assertEquals("033661234", json(a).get("data").get("phone").asText());   // 去掉 - 與空白
        MvcResult b = call("POST", FARMS, token, farm("大溪二場", "0922333444"));
        String idB = json(b).get("data").get("id").asText();

        JsonNode list = farms(token);
        assertEquals(2, list.size());
        assertEquals("八德一號棚", list.get(0).get("farmName").asText());
        assertEquals("大溪二場", list.get(1).get("farmName").asText());

        Map<String, Object> edit = farm("大溪二場（擴建）", "0922333444");
        assertEquals(200, call("PUT", FARMS + "/" + idB, token, edit).getResponse().getStatus());
        assertEquals("大溪二場（擴建）", farms(token).get(1).get("farmName").asText());

        assertEquals(200, call("DELETE", FARMS + "/" + idB, token, null).getResponse().getStatus());
        assertEquals(1, farms(token).size());
        assertEquals(404, call("PUT", FARMS + "/" + idB, token, edit).getResponse().getStatus());
    }

    @Test
    void cannotTouchOtherFarmersFarms() throws Exception {
        String t1 = register("0911000333").get("token").asText();
        String t2 = register("0911000444").get("token").asText();
        String id1 = farms(t1).get(0).get("id").asText();
        assertEquals(404, call("PUT", FARMS + "/" + id1, t2, farm("搶來的", "0911000444")).getResponse().getStatus());
        assertEquals(404, call("DELETE", FARMS + "/" + id1, t2, null).getResponse().getStatus());
        assertTrue(farmRepository.existsById(id1));
        assertEquals(1, farms(t2).size());   // 只看到自己的
    }

    private Map<String, Object> order(String farmId) {
        Map<String, Object> o = new HashMap<>();
        o.put("farmerName", "王小明");
        o.put("phone", "0911000777");
        o.put("farmLocation", "桃園市八德區興豐路 100 號 1 號棚");
        o.put("cropId", "mango");
        o.put("area", 3);
        o.put("plan", "early_bird");
        o.put("farmId", farmId);
        o.put("farmName", "前端亂填的名稱");   // 不採用，由後端依 farmId 帶入
        return o;
    }

    @Test
    void orderRecordsChosenFarm() throws Exception {
        String token = register("0911000777").get("token").asText();
        String other = register("0911000888").get("token").asText();
        MvcResult b = call("POST", FARMS, token, farm("大溪二場", "0922333444"));
        String farmId = json(b).get("data").get("id").asText();

        MvcResult created = call("POST", "/api/orders", token, order(farmId));
        assertEquals(201, created.getResponse().getStatus());
        JsonNode o = json(created).get("data");
        assertEquals(farmId, o.get("farmId").asText());
        assertEquals("大溪二場", o.get("farmName").asText());
        String orderId = o.get("id").asText();

        // 農場改名後，訂單保留下單當時的名稱
        call("PUT", FARMS + "/" + farmId, token, farm("大溪三場", "0922333444"));
        JsonNode again = json(call("GET", "/api/orders/" + orderId, token, null)).get("data");
        assertEquals(farmId, again.get("farmId").asText());
        assertEquals("大溪二場", again.get("farmName").asText());

        // 修改訂單不能換成別的農場
        Map<String, Object> patch = new HashMap<>();
        patch.put("farmId", "FM9999");
        patch.put("farmName", "改掉");
        call("PUT", "/api/orders/" + orderId, token, patch);
        assertEquals("大溪二場", json(call("GET", "/api/orders/" + orderId, token, null)).get("data").get("farmName").asText());

        // 用別人的農場或不存在的農場下單 → 400
        assertEquals(400, call("POST", "/api/orders", other, order(farmId)).getResponse().getStatus());
        assertEquals(400, call("POST", "/api/orders", token, order("FM9999")).getResponse().getStatus());

        // 不帶農場仍可下單（舊版前端、LINE 等）
        Map<String, Object> noFarm = order(null);
        noFarm.remove("farmId");
        MvcResult legacy = call("POST", "/api/orders", token, noFarm);
        assertEquals(201, legacy.getResponse().getStatus());
        assertTrue(json(legacy).get("data").get("farmName").isNull());
    }

    @Test
    void validation() throws Exception {
        String token = register("0911000555").get("token").asText();
        assertEquals(400, call("POST", FARMS, token, farm("A", "12345")).getResponse().getStatus());
        assertEquals(400, call("POST", FARMS, token, farm(" ", "0911000555")).getResponse().getStatus());
        Map<String, Object> f = farm("B", "0911000555");
        f.remove("address");
        assertEquals(400, call("POST", FARMS, token, f).getResponse().getStatus());
        f = farm("C", "0911000555");
        f.remove("contactName");
        assertEquals(400, call("POST", FARMS, token, f).getResponse().getStatus());
    }

    @Test
    void onlyFarmers() throws Exception {
        assertEquals(401, call("GET", FARMS, null, null).getResponse().getStatus());
        String admin = login("admin_tech", "Admin#Pass2026");
        assertEquals(403, call("GET", FARMS, admin, null).getResponse().getStatus());
        assertEquals(403, call("POST", FARMS, admin, farm("X", "0911000666")).getResponse().getStatus());
    }
}
