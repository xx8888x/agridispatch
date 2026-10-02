package com.agridispatch;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * LINE 雙帳號 P2（production 模式、未設定內部金鑰與 LINE Login）：
 * 內部端點整組停用、mock 假 token 一律不接受（規格 05 第 5.3 節第 1 點、第拾章第 6 點）
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:agri_line_binding_prod_test;DB_CLOSE_DELAY=-1",
        "agri.demo.enabled=false",
        "agri.security.token-secret=unit-test-token-secret-0123456789abcdef",
        "agri.security.admin-password=Admin#Pass2026",
        "agri.linebot.base-url=http://127.0.0.1:1"
})
@AutoConfigureMockMvc
class LineBindingProductionTest extends ApiTestSupport {

    @Test
    void internalEndpointsDisabledWithoutKey() throws Exception {
        for (String path : new String[]{"identity", "follow-state", "query"}) {
            MvcResult r = mvc.perform(post("/api/line/internal/" + path)
                    .header("X-Internal-Key", "anything")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"channel\":\"farmer\",\"lineUserId\":\"U1\"}")).andReturn();
            assertEquals(403, r.getResponse().getStatus(), path);
            assertTrue(json(r).get("error").asText().contains("AGRI_LINEBOT_INTERNAL_KEY"), path);
        }
    }

    @Test
    void mockIdTokenRejectedInProduction() throws Exception {
        String admin = login("admin_tech", "Admin#Pass2026");
        assertEquals(200, call("POST", "/api/auth/reset-password", admin,
                Map.of("identifier", "farmer", "newPassword", "Farmer#2026pw")).getResponse().getStatus());
        String farmer = login("farmer", "Farmer#2026pw");
        MvcResult r = call("POST", "/api/line/bind", farmer,
                Map.of("channel", "farmer", "idToken", "mock:U0123456789abcdef0123456789abcdef"));
        assertEquals(503, r.getResponse().getStatus());
        // 綁定清單仍只限 admin
        assertEquals(403, call("GET", "/api/line/bindings", farmer, null).getResponse().getStatus());
        assertEquals(200, call("GET", "/api/line/bindings", admin, null).getResponse().getStatus());
    }
}
