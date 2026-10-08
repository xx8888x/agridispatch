package com.agridispatch;

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
 * 農場主自助註冊 POST /api/auth/register（規格 03 第 4、5 節）
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:agri_register_test;DB_CLOSE_DELAY=-1",
        "agri.demo.enabled=false",
        "agri.security.token-secret=unit-test-token-secret-0123456789abcdef",
        "agri.security.admin-password=Admin#Pass2026",
        "agri.security.register-ip-max=3"
})
@AutoConfigureMockMvc
class FarmerRegisterApiTest extends ApiTestSupport {

    @Autowired RegisterRateLimiter registerRateLimiter;
    @Autowired LoginRateLimiter loginRateLimiter;
    @Autowired FarmerRepository farmerRepository;

    @BeforeEach
    void setUp() {
        registerRateLimiter.reset();
        loginRateLimiter.reset();
    }

    private Map<String, Object> body(String phone) {
        Map<String, Object> b = new HashMap<>();
        b.put("name", "王大明");
        b.put("phone", phone);
        b.put("password", "Farm#2026pw");
        b.put("address", "桃園市八德區");
        b.put("farmName", "大明有機農場");
        b.put("pdpaConsent", true);
        return b;
    }

    private MvcResult register(String ip, Map<String, Object> b) throws Exception {
        return mvc.perform(post("/api/auth/register")
                .with(req -> { req.setRemoteAddr(ip); return req; })
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(b))).andReturn();
    }

    @Test
    void registerCreatesFarmerAndLogsIn() throws Exception {
        MvcResult r = register("10.1.0.1", body("0911-222-333"));
        assertEquals(200, r.getResponse().getStatus());
        JsonNode res = json(r);
        assertTrue(res.get("success").asBoolean());
        JsonNode user = res.get("user");
        assertEquals("0911222333", user.get("username").asText());
        assertEquals("farmer", user.get("role").asText());
        String refId = user.get("refId").asText();
        assertTrue(refId.matches("F\\d{3,}"));
        assertNotEquals("F001", refId);   // 不可與種子帳號 F001、F002 撞號
        assertNotEquals("F002", refId);
        assertTrue(farmerRepository.findById(refId).isPresent());
        assertEquals("個人農戶", farmerRepository.findById(refId).get().getFarmType());   // 未填農場類型時的預設，與註冊頁一致
        assertFalse(res.toString().contains("Farm#2026pw"));

        // 回傳的 token 可用
        String token = res.get("token").asText();
        assertEquals(200, call("GET", "/api/auth/me", token, null).getResponse().getStatus());
        // 之後可用手機號碼登入（含不含 - 都可以）
        login("0911222333", "Farm#2026pw");
        login("0911-222-333", "Farm#2026pw");
    }

    @Test
    void duplicatePhoneRejected() throws Exception {
        assertEquals(200, register("10.1.0.2", body("0922333444")).getResponse().getStatus());
        MvcResult dup = register("10.1.0.2", body("0922-333-444"));
        assertEquals(409, dup.getResponse().getStatus());
        assertEquals("此手機號碼已註冊，請直接登入", json(dup).get("error").asText());
    }

    @Test
    void validationErrors() throws Exception {
        Map<String, Object> b = body("12345");
        assertEquals(400, register("10.1.0.3", b).getResponse().getStatus());

        b = body("0933444555");
        b.put("password", "short");
        assertEquals(400, register("10.1.0.4", b).getResponse().getStatus());

        b = body("0933444555");
        b.remove("pdpaConsent");
        assertEquals(400, register("10.1.0.5", b).getResponse().getStatus());

        b = body("0933444555");
        b.put("address", " ");
        assertEquals(400, register("10.1.0.6", b).getResponse().getStatus());
    }

    @Test
    void registerIsRateLimitedPerIp() throws Exception {
        for (int i = 0; i < 3; i++) {
            Map<String, Object> b = body("09550000" + (10 + i));
            assertEquals(200, register("10.1.0.9", b).getResponse().getStatus());
        }
        assertEquals(429, register("10.1.0.9", body("0955000099")).getResponse().getStatus());
        assertEquals(200, register("10.1.0.10", body("0955000099")).getResponse().getStatus());
    }
}
