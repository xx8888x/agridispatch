package com.agridispatch;

import com.agridispatch.repository.FarmerRepository;
import com.agridispatch.repository.UserRepository;
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
 * 我的農場 GET/PUT /api/farmers/me（預約派工第一步：登錄農場，登錄過就直接派工）
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:agri_farm_profile_test;DB_CLOSE_DELAY=-1",
        "agri.demo.enabled=false",
        "agri.security.token-secret=unit-test-token-secret-0123456789abcdef",
        "agri.security.admin-password=Admin#Pass2026"
})
@AutoConfigureMockMvc
class FarmProfileApiTest extends ApiTestSupport {

    @Autowired RegisterRateLimiter registerRateLimiter;
    @Autowired LoginRateLimiter loginRateLimiter;
    @Autowired FarmerRepository farmerRepository;
    @Autowired UserRepository userRepository;

    @BeforeEach
    void setUp() {
        registerRateLimiter.reset();
        loginRateLimiter.reset();
    }

    /** 自助註冊一個農場主，回 token */
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

    private Map<String, Object> farm(String phone) {
        Map<String, Object> f = new HashMap<>();
        f.put("farmName", "大明溫室二場");
        f.put("contactName", "王小明");
        f.put("phone", phone);
        f.put("address", "桃園市八德區興豐路 100 號 1 號棚");
        f.put("farmType", "產銷班");
        return f;
    }

    @Test
    void registeredFarmerAlreadyHasFarm() throws Exception {
        String token = register("0911000111").get("token").asText();
        MvcResult r = call("GET", "/api/farmers/me", token, null);
        assertEquals(200, r.getResponse().getStatus());
        JsonNode d = json(r).get("data");
        assertEquals("大明有機農場", d.get("farmName").asText());
        assertEquals("王大明", d.get("contactName").asText());
        assertEquals("0911000111", d.get("phone").asText());
        assertEquals("桃園市八德區", d.get("address").asText());
    }

    @Test
    void farmerWithoutFarmRegistersOnceThenReads() throws Exception {
        JsonNode reg = register("0911000222");
        String token = reg.get("token").asText();
        String refId = reg.get("user").get("refId").asText();
        farmerRepository.deleteById(refId);   // 模擬種子帳號：只有 users、沒有 farmers

        MvcResult empty = call("GET", "/api/farmers/me", token, null);
        assertEquals(200, empty.getResponse().getStatus());
        assertTrue(json(empty).get("data").isNull());

        MvcResult saved = call("PUT", "/api/farmers/me", token, farm("03-366-1234"));
        assertEquals(200, saved.getResponse().getStatus());
        assertEquals("033661234", json(saved).get("data").get("phone").asText());   // 去掉 - 與空白
        assertEquals("033661234", farmerRepository.findById(refId).orElseThrow().getPhone());
        assertEquals("產銷班", farmerRepository.findById(refId).orElseThrow().getFarmType());
        assertEquals("王小明", userRepository.findById("0911000222").orElseThrow().getName());   // 聯絡人姓名同步帳號姓名

        JsonNode again = json(call("GET", "/api/farmers/me", token, null)).get("data");
        assertEquals("大明溫室二場", again.get("farmName").asText());
        assertEquals("王小明", again.get("contactName").asText());
    }

    @Test
    void validationAndPhoneConflict() throws Exception {
        register("0911000333");
        String token = register("0911000444").get("token").asText();

        Map<String, Object> f = farm("12345");
        assertEquals(400, call("PUT", "/api/farmers/me", token, f).getResponse().getStatus());
        f = farm("0911000444");
        f.put("farmName", " ");
        assertEquals(400, call("PUT", "/api/farmers/me", token, f).getResponse().getStatus());
        f = farm("0911000444");
        f.remove("address");
        assertEquals(400, call("PUT", "/api/farmers/me", token, f).getResponse().getStatus());

        // 不可用其他農場主的電話（自助註冊以 farmers.phone 判斷是否已註冊）
        assertEquals(409, call("PUT", "/api/farmers/me", token, farm("0911-000-333")).getResponse().getStatus());
        // 自己原本的電話可以
        assertEquals(200, call("PUT", "/api/farmers/me", token, farm("0911000444")).getResponse().getStatus());
    }

    @Test
    void onlyFarmers() throws Exception {
        assertEquals(401, call("GET", "/api/farmers/me", null, null).getResponse().getStatus());
        String admin = login("admin_tech", "Admin#Pass2026");
        assertEquals(403, call("GET", "/api/farmers/me", admin, null).getResponse().getStatus());
        assertEquals(403, call("PUT", "/api/farmers/me", admin, farm("0911000555")).getResponse().getStatus());
    }
}
