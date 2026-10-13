package com.agridispatch;

import com.agridispatch.config.AgriSettings;
import com.agridispatch.entity.PushToken;
import com.agridispatch.repository.PushTokenRepository;
import com.agridispatch.service.FcmPushService;
import com.agridispatch.service.FcmPushServiceAccess;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * App 推播權杖（/api/notifications/fcm/*）與 FcmPushService（production 模式、未設定 FCM 服務帳戶）
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:agri_push_test;DB_CLOSE_DELAY=-1",
        "agri.demo.enabled=false",
        "agri.security.token-secret=unit-test-token-secret-0123456789abcdef",
        "agri.security.admin-password=Admin#Pass2026",
        "agri.features.foreign-workers=false"
})
@AutoConfigureMockMvc
class PushTokenApiTest extends ApiTestSupport {

    @Autowired PushTokenRepository repository;
    @Autowired AgriSettings settings;

    private String adminToken;
    private String farmerToken;

    @BeforeEach
    void setUp() throws Exception {
        repository.deleteAll();
        adminToken = login("admin_tech", "Admin#Pass2026");
        assertEquals(200, call("POST", "/api/auth/reset-password", adminToken,
                Map.of("identifier", "farmer", "newPassword", "Farmer#2026pw")).getResponse().getStatus());
        farmerToken = login("farmer", "Farmer#2026pw");
    }

    private PushToken only() {
        List<PushToken> all = repository.findAll();
        assertEquals(1, all.size());
        return all.get(0);
    }

    @Test
    void registerBindsTokenToCurrentUserAndRebindsOnRelogin() throws Exception {
        MvcResult r = call("POST", "/api/notifications/fcm/register", farmerToken, Map.of("token", "device-token-1", "platform", "android"));
        assertEquals(200, r.getResponse().getStatus());
        assertFalse(json(r).get("pushConfigured").asBoolean());
        PushToken t = only();
        assertEquals("farmer", t.getUsername());
        assertEquals("farmer", t.getRole());
        assertNotNull(t.getRefId());

        // 同一台裝置改由 admin 登入：改綁，不會多一筆
        call("POST", "/api/notifications/fcm/register", adminToken, Map.of("token", "device-token-1"));
        assertEquals("admin_tech", only().getRole());
    }

    @Test
    void mockOrEmptyTokenIsIgnored() throws Exception {
        assertEquals(200, call("POST", "/api/notifications/fcm/register", farmerToken, Map.of("token", "MOCK_FCM_TOKEN_ABC")).getResponse().getStatus());
        assertEquals(200, call("POST", "/api/notifications/fcm/register", farmerToken, Map.of("token", "  ")).getResponse().getStatus());
        assertEquals(0, repository.count());
        assertEquals(400, call("POST", "/api/notifications/fcm/register", farmerToken, Map.of("token", "x".repeat(1025))).getResponse().getStatus());
        assertEquals(401, call("POST", "/api/notifications/fcm/register", null, Map.of("token", "device-token-1")).getResponse().getStatus());
    }

    @Test
    void unregisterOnlyRemovesOwnToken() throws Exception {
        call("POST", "/api/notifications/fcm/register", farmerToken, Map.of("token", "device-token-1"));
        assertFalse(json(call("POST", "/api/notifications/fcm/unregister", adminToken, Map.of("token", "device-token-1"))).get("removed").asBoolean());
        assertEquals(1, repository.count());
        assertTrue(json(call("POST", "/api/notifications/fcm/unregister", farmerToken, Map.of("token", "device-token-1"))).get("removed").asBoolean());
        assertEquals(0, repository.count());
    }

    @Test
    void sendReportsRecipientsAndSkipsForeignWorkersWhenDisabled() throws Exception {
        call("POST", "/api/notifications/fcm/register", farmerToken, Map.of("token", "farmer-device"));
        String farmerRef = only().getRefId();
        PushToken foreign = new PushToken("f".repeat(64), "foreign-device");
        foreign.setUsername("W001");
        foreign.setRefId("W001");
        foreign.setRole("worker_foreign");
        repository.save(foreign);

        JsonNode toFarmer = json(call("POST", "/api/notifications/send", adminToken,
                Map.of("title", "派工", "message", "明天 6:30 集合", "target", farmerRef)));
        assertTrue(toFarmer.get("success").asBoolean());
        assertEquals(1, toFarmer.get("push").get("recipients").asInt());
        assertFalse(toFarmer.get("push").get("configured").asBoolean());

        assertFalse(settings.isForeignWorkersEnabled());
        JsonNode toAll = json(call("POST", "/api/notifications/send", adminToken, Map.of("message", "全體公告", "target", "all")));
        assertEquals(1, toAll.get("push").get("recipients").asInt(), "外籍移工功能關閉時不推給 worker_foreign");
        assertEquals(0, json(call("POST", "/api/notifications/send", adminToken, Map.of("message", "hi", "target", "W001"))).get("push").get("recipients").asInt());
    }

    @Test
    void statusIsAdminOnly() throws Exception {
        assertEquals(403, call("GET", "/api/notifications/fcm/status", farmerToken, null).getResponse().getStatus());
        MvcResult r = call("GET", "/api/notifications/fcm/status", adminToken, null);
        assertEquals(200, r.getResponse().getStatus());
        assertFalse(json(r).get("configured").asBoolean());
    }

    /** 服務帳戶 JSON 讀得到即視為已設定；JWT 以該私鑰簽 RS256、對應公鑰可驗 */
    @Test
    void serviceAccountIsLoadedAndJwtIsVerifiable(@TempDir Path dir) throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        KeyPair kp = gen.generateKeyPair();
        String pem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(kp.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n";
        Path file = dir.resolve("sa.json");
        Files.writeString(file, om.writeValueAsString(Map.of(
                "type", "service_account", "project_id", "agri-test",
                "client_email", "fcm@agri-test.iam.gserviceaccount.com", "private_key", pem,
                "token_uri", "https://oauth2.googleapis.com/token")), StandardCharsets.UTF_8);

        FcmPushService svc = new FcmPushService(repository, settings, om, file.toString());
        assertTrue(svc.isConfigured());
        assertFalse(new FcmPushService(repository, settings, om, dir.resolve("missing.json").toString()).isConfigured());

        String jwt = FcmPushServiceAccess.jwt(om, Files.readString(file));
        String[] parts = jwt.split("\\.");
        assertEquals(3, parts.length);
        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initVerify(kp.getPublic());
        sig.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        assertTrue(sig.verify(Base64.getUrlDecoder().decode(parts[2])));
        JsonNode claims = om.readTree(Base64.getUrlDecoder().decode(parts[1]));
        assertEquals("fcm@agri-test.iam.gserviceaccount.com", claims.get("iss").asText());
        assertEquals("https://www.googleapis.com/auth/firebase.messaging", claims.get("scope").asText());
    }

    @Test
    void messagePayloadMapsChannelToAndroidChannel() {
        Map<String, Object> m = FcmPushServiceAccess.message("tok", "標題", "內容", "weather");
        JsonNode n = om.valueToTree(m).get("message");
        assertEquals("tok", n.get("token").asText());
        assertEquals("channel_weather", n.get("android").get("notification").get("channel_id").asText());
        assertEquals("HIGH", n.get("android").get("priority").asText());
        assertEquals("weather", n.get("data").get("channel").asText());
        assertEquals("channel_dispatch", om.valueToTree(FcmPushServiceAccess.message("tok", "t", "b", null))
                .get("message").get("android").get("notification").get("channel_id").asText());
    }
}
