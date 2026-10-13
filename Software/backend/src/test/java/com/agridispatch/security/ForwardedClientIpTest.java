package com.agridispatch.security;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 反向代理後的來源 IP（application.yml server.forward-headers-strategy: native）：
 * 經本機代理送來的 X-Forwarded-For 視為使用者 IP，登入失敗的 IP 鎖定只鎖該使用者，不會因為大家都從 Nginx（127.0.0.1）來而一起被鎖。
 * 需要真的 Tomcat（RemoteIpValve），MockMvc 測不到，故以實際 HTTP 呼叫。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:agri_forwarded_ip_test;DB_CLOSE_DELAY=-1",
        "agri.demo.enabled=false",
        "agri.security.token-secret=unit-test-token-secret-0123456789abcdef",
        "agri.security.admin-password=Admin#Pass2026",
        "agri.security.login-ip-max-failures=3"
})
class ForwardedClientIpTest {

    @LocalServerPort
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    private int failLogin(String identifier, String forwardedFor) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/auth/login"))
                .header("Content-Type", "application/json")
                .header("X-Forwarded-For", forwardedFor)
                .POST(HttpRequest.BodyPublishers.ofString("{\"identifier\":\"" + identifier + "\",\"password\":\"wrong-password\"}"))
                .build();
        return http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    @Test
    void ipLockAppliesToForwardedClientNotToProxy() throws Exception {
        // 使用者 A 連續失敗（每次不同帳號，避免觸發帳號鎖定）→ 第 4 次起 A 的 IP 被鎖
        for (int i = 0; i < 3; i++) assertEquals(401, failLogin("ghost_a" + i, "203.0.113.10"));
        assertEquals(429, failLogin("ghost_a9", "203.0.113.10"));

        // 同樣經由本機代理的使用者 B 不受影響
        assertEquals(401, failLogin("ghost_b0", "203.0.113.20"));
        // Nginx 的 $proxy_add_x_forwarded_for 會把使用者自帶的值放在前面：取最右邊非代理的位址，偽造前段無法冒用他人
        assertEquals(429, failLogin("ghost_a10", "198.51.100.7, 203.0.113.10"));
    }
}
