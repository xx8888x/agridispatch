package com.agridispatch;

import com.agridispatch.repository.WorkerRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * demo 模式（路演）完成刪除帳號申請時只更新狀態，不刪共用的示範帳號（與前端 Mock 一致）
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:agri_account_deletion_demo_test;DB_CLOSE_DELAY=-1",
        "agri.demo.enabled=true",
        "agri.security.token-secret=unit-test-token-secret-0123456789abcdef"
})
@AutoConfigureMockMvc
class AccountDeletionDemoModeTest extends ApiTestSupport {

    @Autowired WorkerRepository workerRepository;

    private String demoToken(String role) throws Exception {
        return json(call("POST", "/api/auth/demo-login", null, Map.of("role", role))).get("token").asText();
    }

    @Test
    void demoCompletionKeepsTheDemoAccount() throws Exception {
        String worker = demoToken("worker_local");
        String workerId = json(call("GET", "/api/auth/me", worker, null)).get("user").get("refId").asText();
        String hashBefore = workerRepository.findById(workerId).orElseThrow().getPasswordHash();

        MvcResult r = call("POST", "/api/account/deletion-request", worker, Map.of("reason", "路演示範"));
        assertEquals(200, r.getResponse().getStatus(), r.getResponse().getContentAsString());
        String id = json(r).get("data").get("id").asText();

        JsonNode done = json(call("POST", "/api/account-deletions/" + id + "/complete", demoToken("admin_tech"), Map.of()));
        assertEquals("completed", done.get("data").get("status").asText());
        assertTrue(done.get("data").get("resultSummary").asText().contains("示範模式"));
        assertEquals(hashBefore, workerRepository.findById(workerId).orElseThrow().getPasswordHash(), "示範帳號不刪");
        assertEquals(200, call("GET", "/api/auth/me", demoToken("worker_local"), null).getResponse().getStatus());
    }
}
