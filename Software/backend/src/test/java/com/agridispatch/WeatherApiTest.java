package com.agridispatch;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.*;

/** /api/weather/towns：需登入；未設定 CWA_API_KEY 時 503（不對外連線） */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:agri_weather_test;DB_CLOSE_DELAY=-1",
        "agri.demo.enabled=true",
        "agri.security.token-secret=unit-test-token-secret-0123456789abcdef",
        "agri.weather.cwa-api-key="
})
@AutoConfigureMockMvc
class WeatherApiTest extends ApiTestSupport {

    @Test
    void requiresLogin() throws Exception {
        assertEquals(401, call("GET", "/api/weather/towns", null, null).getResponse().getStatus());
    }

    @Test
    void returns503WhenCwaKeyMissing() throws Exception {
        MvcResult r = call("GET", "/api/weather/towns", login("admin_tech", "admin888"), null);
        assertEquals(503, r.getResponse().getStatus());
        assertFalse(json(r).path("success").asBoolean(true));
        assertTrue(json(r).path("error").asText().contains("CWA_API_KEY"));
    }
}
