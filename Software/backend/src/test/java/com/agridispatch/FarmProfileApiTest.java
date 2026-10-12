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
