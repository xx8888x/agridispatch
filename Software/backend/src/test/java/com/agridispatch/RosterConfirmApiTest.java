package com.agridispatch;

import com.agridispatch.entity.Order;
import com.agridispatch.entity.SharedRoster;
import com.agridispatch.repository.OrderRepository;
import com.agridispatch.repository.SharedRosterRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 社區共享班表：確認開班流程（POST /api/rosters/{id}/confirm）與截止日前後的認領規則。
 * RosterApiTest 已涵蓋開班、認領、自動判斷主流程；這裡補：手動確認的權限與狀態轉換、
 * 開班後仍可認領（即使已過截止日）、截止日過後未開班不可認領、取消的連帶效果與限制。
 * 種子：工班 B 的本國工人 W026（領班）、W033、W034 完整加保；F001＝farmer。每個測試用不同期間避免名單撞期。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:agri_roster_confirm_test;DB_CLOSE_DELAY=-1",
        "agri.demo.enabled=false",
        "agri.features.foreign-workers=false",
        "agri.security.token-secret=unit-test-token-secret-0123456789abcdef",
        "agri.security.admin-password=Admin#Pass2026",
        "agri.roster.evaluate-cron=-"
})
@AutoConfigureMockMvc
class RosterConfirmApiTest extends ApiTestSupport {

    private static final List<String> CREW = List.of("W026", "W033", "W034");

    @Autowired SharedRosterRepository rosterRepository;
    @Autowired OrderRepository orderRepository;

    private String admin;
    private String farmer;

    @BeforeEach
    void setUp() throws Exception {
        admin = login("admin_tech", "Admin#Pass2026");
        farmer = resetAndLogin("farmer", "Farmer#2026pw");
    }

    private String resetAndLogin(String username, String password) throws Exception {
        MvcResult r = call("POST", "/api/auth/reset-password", admin, Map.of("identifier", username, "newPassword", password));
        assertEquals(200, r.getResponse().getStatus());
        return login(username, password);
    }

    private static String body(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private JsonNode createRoster(LocalDate start, int days, int minPercent, LocalDate deadline) throws Exception {
        Map<String, Object> req = new HashMap<>();
        req.put("title", "枋山芒果產銷班共享班表");
        req.put("region", "屏東縣枋山鄉");
        req.put("teamId", "team_b");
        req.put("crew", CREW);
        req.put("startDate", start.toString());
        req.put("endDate", start.plusDays(days - 1).toString());
        req.put("minClaimPercent", minPercent);
        if (deadline != null) req.put("claimDeadline", deadline.toString());
        MvcResult r = call("POST", "/api/rosters", admin, req);
        assertEquals(201, r.getResponse().getStatus(), body(r));
        return json(r).get("data");
    }

    private MvcResult claim(String rosterId, LocalDate day, String slot) throws Exception {
        Map<String, Object> c = new HashMap<>();
        c.put("date", day.toString());
        c.put("timeSlot", slot);
        c.put("farmerName", "陳建龍");
        c.put("phone", "0912-345-678");
        c.put("farmLocation", "屏東縣枋山鄉善餘村 12 號");
        c.put("cropId", "mango");
        c.put("area", 1.5);
        c.put("plan", "early_bird");
        return call("POST", "/api/rosters/" + rosterId + "/claims", farmer, c);
    }

    private String claimOk(String rosterId, LocalDate day, String slot) throws Exception {
        MvcResult r = claim(rosterId, day, slot);
        assertEquals(201, r.getResponse().getStatus(), body(r));
        return json(r).get("data").get("id").asText();
    }

    private JsonNode view(String rosterId, String token) throws Exception {
        MvcResult r = call("GET", "/api/rosters/" + rosterId, token, null);
        assertEquals(200, r.getResponse().getStatus(), body(r));
        return json(r).get("data");
    }

    private void moveDeadline(String rosterId, LocalDate deadline) {
        SharedRoster r = rosterRepository.findById(rosterId).orElseThrow();
        r.setClaimDeadline(deadline);
        rosterRepository.save(r);
    }

    @Test
    void manualConfirmRequiresAdminAndThreshold() throws Exception {
        LocalDate start = LocalDate.now().plusDays(30);
        String id = createRoster(start, 3, 50, null).get("id").asText();   // 6 格，門檻 50% = 3 格
        String url = "/api/rosters/" + id + "/confirm";

        // 權限：未登入 401、農戶 403、工人 403；不存在的班表 404
        assertEquals(401, call("POST", url, null, null).getResponse().getStatus());
        assertEquals(403, call("POST", url, farmer, null).getResponse().getStatus());
        assertEquals(403, call("POST", url, resetAndLogin("W026", "Worker#2026pw"), null).getResponse().getStatus());
        assertEquals(404, call("POST", "/api/rosters/SR-1999-999/confirm", admin, null).getResponse().getStatus());

        // 1/6 = 16%：未達門檻 409，狀態仍為開放
        claimOk(id, start, "morning");
        MvcResult low = call("POST", url, admin, null);
        assertEquals(409, low.getResponse().getStatus());
        assertTrue(body(low).contains("未達門檻"), body(low));
        assertEquals("open", view(id, admin).get("status").asText());

        // 再認領一個整天：3/6 = 50% 達標 → 確認開班
        claimOk(id, start.plusDays(1), "full_day");
        JsonNode before = view(id, farmer);
        assertEquals(3, before.get("claimedSlots").asInt());
        assertEquals(50, before.get("claimPercent").asInt());
        assertTrue(before.get("thresholdMet").asBoolean());
        MvcResult ok = call("POST", url, admin, null);
        assertEquals(200, ok.getResponse().getStatus(), body(ok));
        JsonNode confirmed = json(ok).get("data");
        assertEquals("confirmed", confirmed.get("status").asText());
        assertEquals("已開班", confirmed.get("statusLabel").asText());
        assertTrue(confirmed.get("claimable").asBoolean());
        assertEquals("confirmed", rosterRepository.findById(id).orElseThrow().getStatus());

        // 已開班不可再確認
        assertEquals(409, call("POST", url, admin, null).getResponse().getStatus());

        // 農戶清單看得到已開班的班表
        boolean listed = false;
        for (JsonNode x : json(call("GET", "/api/rosters", farmer, null)).get("data")) {
            if (id.equals(x.get("id").asText())) listed = "confirmed".equals(x.get("status").asText());
        }
        assertTrue(listed);

        // 開班後即使過了截止日，剩下的空檔仍可認領；同一格不可重複
        moveDeadline(id, LocalDate.now().minusDays(1));
        assertTrue(view(id, farmer).get("claimable").asBoolean());
        assertEquals("free", view(id, farmer).get("days").get(0).get("afternoon").get("state").asText());
        claimOk(id, start, "afternoon");
        assertEquals(409, claim(id, start, "afternoon").getResponse().getStatus());
        assertEquals(409, claim(id, start.plusDays(1), "morning").getResponse().getStatus(), "整天認領佔上午與下午");
        JsonNode after = view(id, farmer);
        assertEquals(4, after.get("claimedSlots").asInt());
        assertEquals(66, after.get("claimPercent").asInt());
    }

    @Test
    void openRosterPastDeadlineStopsClaimsUntilDecided() throws Exception {
        LocalDate start = LocalDate.now().plusDays(50);
        String id = createRoster(start, 2, 50, null).get("id").asText();   // 4 格
        claimOk(id, start, "full_day");                                    // 2/4 = 50%
        moveDeadline(id, LocalDate.now().minusDays(1));

        // 截止日過後、合作社尚未判斷：不可再認領，時段格全部顯示 closed（已認領的除外）
        MvcResult late = claim(id, start.plusDays(1), "morning");
        assertEquals(409, late.getResponse().getStatus());
        assertTrue(body(late).contains("截止"), body(late));
        JsonNode v = view(id, farmer);
        assertEquals("open", v.get("status").asText());
        assertFalse(v.get("claimable").asBoolean());
        assertEquals("closed", v.get("days").get(1).get("morning").get("state").asText());
        assertEquals("mine", v.get("days").get(0).get("morning").get("state").asText());

        // 合作社仍可手動確認（已達門檻）→ 開班後恢復可認領
        assertEquals(200, call("POST", "/api/rosters/" + id + "/confirm", admin, null).getResponse().getStatus());
        assertEquals("free", view(id, farmer).get("days").get(1).get("morning").get("state").asText());
        claimOk(id, start.plusDays(1), "morning");
    }

    @Test
    void deadlineDefaultsAndValidation() throws Exception {
        LocalDate start = LocalDate.now().plusDays(70);
        // 未給截止日：開工前 3 天
        JsonNode r1 = createRoster(start, 1, 60, null);
        assertEquals(start.minusDays(3).toString(), r1.get("claimDeadline").asText());
        assertEquals(60, r1.get("minClaimPercent").asInt());
        // 截止日早於今天：改為今天（今天仍可認領）
        LocalDate start2 = LocalDate.now().plusDays(2);
        JsonNode r2 = createRoster(start2, 1, 60, LocalDate.now().minusDays(5));
        assertEquals(LocalDate.now().toString(), r2.get("claimDeadline").asText());
        assertTrue(r2.get("claimable").asBoolean());
        claimOk(r2.get("id").asText(), start2, "morning");

        Map<String, Object> bad = new HashMap<>(Map.of("title", "x", "region", "y", "teamId", "team_b", "crew", CREW,
                "startDate", start.plusDays(5).toString(), "endDate", start.plusDays(5).toString()));
        bad.put("claimDeadline", start.plusDays(5).toString());
        assertEquals(400, call("POST", "/api/rosters", admin, bad).getResponse().getStatus(), "截止日須早於開工日");
        bad.put("claimDeadline", start.plusDays(1).toString());
        bad.put("minClaimPercent", 0);
        assertEquals(400, call("POST", "/api/rosters", admin, bad).getResponse().getStatus(), "門檻須介於 1～100");
        bad.put("minClaimPercent", 101);
        assertEquals(400, call("POST", "/api/rosters", admin, bad).getResponse().getStatus());
    }

    @Test
    void cancelBlockedByInProgressClaimAndCountsRefunds() throws Exception {
        LocalDate start = LocalDate.now().plusDays(90);
        String id = createRoster(start, 2, 50, null).get("id").asText();
        String o1 = claimOk(id, start, "full_day");
        String o2 = claimOk(id, start.plusDays(1), "morning");
        assertEquals(200, call("POST", "/api/rosters/" + id + "/confirm", admin, null).getResponse().getStatus());

        // 有施工中的認領訂單：不可取消（409），班表與訂單都不變
        Order first = orderRepository.findById(o1).orElseThrow();
        first.setStatus("in_progress");
        orderRepository.save(first);
        assertEquals(403, call("POST", "/api/rosters/" + id + "/cancel", farmer, Map.of("reason", "x")).getResponse().getStatus());
        MvcResult blocked = call("POST", "/api/rosters/" + id + "/cancel", admin, Map.of("reason", "測試"));
        assertEquals(409, blocked.getResponse().getStatus());
        assertTrue(body(blocked).contains("施工中"), body(blocked));
        assertEquals("confirmed", rosterRepository.findById(id).orElseThrow().getStatus());
        assertEquals("pending", orderRepository.findById(o2).orElseThrow().getStatus());

        // 改回已排定、其中一張已付定金 → 取消成功、兩張訂單取消、待退定金 1 筆；未給原因用預設
        first.setStatus("confirmed");
        first.setDepositPaid(true);
        orderRepository.save(first);
        MvcResult c = call("POST", "/api/rosters/" + id + "/cancel", admin, null);
        assertEquals(200, c.getResponse().getStatus(), body(c));
        JsonNode d = json(c).get("data");
        assertEquals(2, d.get("cancelledOrders").size());
        assertEquals(1, d.get("refundsNeeded").asInt());
        assertEquals("合作社取消", d.get("roster").get("cancelReason").asText());
        assertEquals("cancelled", orderRepository.findById(o1).orElseThrow().getStatus());
        assertEquals("cancelled", orderRepository.findById(o2).orElseThrow().getStatus());

        // 已取消：不可確認、不可再取消；農戶看不到（404），admin 看得到
        assertEquals(409, call("POST", "/api/rosters/" + id + "/confirm", admin, null).getResponse().getStatus());
        assertEquals(409, call("POST", "/api/rosters/" + id + "/cancel", admin, null).getResponse().getStatus());
        assertEquals(404, call("GET", "/api/rosters/" + id, farmer, null).getResponse().getStatus());
        assertEquals("cancelled", view(id, admin).get("status").asText());
    }
}
