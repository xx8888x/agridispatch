package com.agridispatch;

import com.agridispatch.security.RegisterRateLimiter;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MvcResult;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 推薦裂變（production 模式）：推薦碼、邀請連結開啟計數、農場主經邀請註冊、工人意願登記、後台跟進與權限
 * 種子：farmer（F001，農場主）、W001（外籍工人）、admin_tech
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:agri_referral_test;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",   // 記憶體庫跨測試方法共用：每個方法重建，推薦碼與統計才不會互相影響
        "agri.demo.enabled=false",
        "agri.security.token-secret=unit-test-token-secret-0123456789abcdef",
        "agri.security.admin-password=Admin#Pass2026"
})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class ReferralApiTest extends ApiTestSupport {

    @Autowired RegisterRateLimiter registerRateLimiter;

    private String admin;

    @BeforeEach
    void setUp() throws Exception {
        registerRateLimiter.reset();
        admin = login("admin_tech", "Admin#Pass2026");
    }

    private String loginAs(String identifier) throws Exception {
        assertEquals(200, call("POST", "/api/auth/reset-password", admin,
                Map.of("identifier", identifier, "newPassword", "Refer#2026pw")).getResponse().getStatus());
        return login(identifier, "Refer#2026pw");
    }

    private JsonNode mine(String token) throws Exception {
        MvcResult r = call("GET", "/api/referrals/me", token, null);
        assertEquals(200, r.getResponse().getStatus(), r.getResponse().getContentAsString());
        return json(r).get("data");
    }

    private MvcResult postFrom(String ip, String url, Map<String, Object> body) throws Exception {
        return mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(body)).with(r -> { r.setRemoteAddr(ip); return r; })).andReturn();
    }

    private Map<String, Object> registerBody(String phone, String refCode) {
        Map<String, Object> b = new HashMap<>();
        b.put("name", "王大明");
        b.put("phone", phone);
        b.put("password", "Farm#2026pw");
        b.put("address", "桃園市八德區");
        b.put("pdpaConsent", true);
        if (refCode != null) { b.put("refCode", refCode); b.put("refChannel", "line"); }
        return b;
    }

    private Map<String, Object> leadBody(String code, String phone) {
        Map<String, Object> b = new HashMap<>();
        b.put("code", code);
        b.put("name", "陳小華");
        b.put("phone", phone);
        b.put("pdpaConsent", true);
        b.put("channel", "fb");
        return b;
    }

    @Test
    void codeIsStablePerUserAndRoleRestricted() throws Exception {
        String farmer = loginAs("farmer");
        String code = mine(farmer).get("code").asText();
        assertTrue(code.matches("[A-Z2-9]{8}"));
        assertEquals(code, mine(farmer).get("code").asText(), "同一人每次拿到同一組推薦碼");
        assertNotEquals(code, mine(loginAs("W001")).get("code").asText());

        assertEquals(401, call("GET", "/api/referrals/me", null, null).getResponse().getStatus());
        assertEquals(403, call("GET", "/api/referrals/me", admin, null).getResponse().getStatus(), "後台帳號不是推薦人");
    }

    @Test
    void openingTheLinkCountsAndHidesInviterName() throws Exception {
        String farmer = loginAs("farmer");
        String code = mine(farmer).get("code").asText();

        JsonNode open = json(call("GET", "/api/public/referral/" + code.toLowerCase(), null, null));
        assertTrue(open.get("valid").asBoolean());
        assertEquals("farmer", open.get("inviterRole").asText());
        assertTrue(open.get("inviterName").asText().contains("○"), "姓名要遮罩：" + open.get("inviterName"));
        call("GET", "/api/public/referral/" + code, null, null);
        assertEquals(2, mine(farmer).get("stats").get("opens").asInt());

        JsonNode bad = json(call("GET", "/api/public/referral/ZZZZZZZZ", null, null));
        assertFalse(bad.get("valid").asBoolean());
    }

    @Test
    void farmerRegisteringViaLinkIsRecordedForReferrer() throws Exception {
        String inviter = loginAs("W001");
        String code = mine(inviter).get("code").asText();

        MvcResult r = postFrom("10.2.0.1", "/api/auth/register", registerBody("0911000111", code));
        assertEquals(200, r.getResponse().getStatus(), r.getResponse().getContentAsString());

        JsonNode me = mine(inviter);
        assertEquals(1, me.get("stats").get("farmersRegistered").asInt());
        JsonNode inv = me.get("invitees").get(0);
        assertEquals("farmer", inv.get("role").asText());
        assertEquals("registered", inv.get("status").asText());
        assertEquals("line", inv.get("channel").asText());
        assertEquals("王○明", inv.get("name").asText());
        assertNull(inv.get("phone"), "推薦人看不到被邀請者電話");

        // 推薦碼無效：註冊照常成功、不記推薦
        assertEquals(200, postFrom("10.2.0.2", "/api/auth/register", registerBody("0911000222", "NOSUCHCD")).getResponse().getStatus());
        assertEquals(1, mine(inviter).get("invitees").size());
    }

    @Test
    void workerLeadFlowFromSubmissionToStaffFollowUp() throws Exception {
        String farmer = loginAs("farmer");
        String code = mine(farmer).get("code").asText();

        MvcResult ok = postFrom("10.2.1.1", "/api/public/referral/lead", leadBody(code, "0922-111-222"));
        assertEquals(200, ok.getResponse().getStatus(), ok.getResponse().getContentAsString());
        // 同一人重複送出不會多一筆，也不報錯
        assertEquals(200, postFrom("10.2.1.1", "/api/public/referral/lead", leadBody(code, "0922111222")).getResponse().getStatus());
        assertEquals(1, mine(farmer).get("stats").get("workerLeads").asInt());

        // 驗證
        Map<String, Object> noConsent = leadBody(code, "0933111222");
        noConsent.put("pdpaConsent", false);
        assertEquals(400, postFrom("10.2.1.2", "/api/public/referral/lead", noConsent).getResponse().getStatus());
        assertEquals(400, postFrom("10.2.1.2", "/api/public/referral/lead", leadBody("BADCODE1", "0933111222")).getResponse().getStatus());
        assertEquals(400, postFrom("10.2.1.2", "/api/public/referral/lead", leadBody(code, "12")).getResponse().getStatus());
        assertEquals(400, postFrom("10.2.1.2", "/api/public/referral/lead", leadBody(code, "farmer")).getResponse().getStatus());

        // 後台看得到電話與推薦人，推薦人看不到
        JsonNode rows = json(call("GET", "/api/referrals", admin, null)).get("data");
        assertEquals(1, rows.size());
        JsonNode row = rows.get(0);
        assertEquals("0922111222", row.get("inviteePhone").asText());
        assertEquals("farmer", row.get("referrerUsername").asText());
        assertEquals("pending", row.get("status").asText());
        assertEquals(403, call("GET", "/api/referrals", farmer, null).getResponse().getStatus());

        String id = row.get("id").asText();
        assertEquals(403, call("POST", "/api/referrals/" + id + "/status", farmer, Map.of("status", "onboarded")).getResponse().getStatus());
        assertEquals(400, call("POST", "/api/referrals/" + id + "/status", admin, Map.of("status", "bogus")).getResponse().getStatus());
        assertEquals(200, call("POST", "/api/referrals/" + id + "/status", admin, Map.of("status", "onboarded", "note", "已查驗證件建檔")).getResponse().getStatus());
        JsonNode me = mine(farmer);
        assertEquals(1, me.get("stats").get("workersOnboarded").asInt());
        assertEquals("onboarded", me.get("invitees").get(0).get("status").asText());
        assertNull(me.get("invitees").get(0).get("note"), "合作社備註不外洩給推薦人");
    }

    @Test
    void cannotRegisterLeadWithOwnPhone() throws Exception {
        // 自助註冊的農場主以手機號碼為帳號，拿自己的邀請碼登記自己的電話要被擋
        String self = json(postFrom("10.2.2.1", "/api/auth/register", registerBody("0944000111", null))).get("token").asText();
        String selfCode = mine(self).get("code").asText();
        assertEquals(400, postFrom("10.2.2.2", "/api/public/referral/lead", leadBody(selfCode, "0944-000-111")).getResponse().getStatus());
    }

    @Test
    void leadSubmissionIsRateLimitedPerIp() throws Exception {
        String code = mine(loginAs("farmer")).get("code").asText();
        for (int i = 0; i < 10; i++) {
            assertEquals(200, postFrom("10.2.3.1", "/api/public/referral/lead", leadBody(code, "09550000" + (10 + i))).getResponse().getStatus());
        }
        assertEquals(429, postFrom("10.2.3.1", "/api/public/referral/lead", leadBody(code, "0955000099")).getResponse().getStatus());
    }
}
