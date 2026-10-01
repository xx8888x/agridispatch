package com.agridispatch;

import com.agridispatch.entity.Attendance;
import com.agridispatch.entity.TeamRating;
import com.agridispatch.repository.AttendanceRepository;
import com.agridispatch.repository.TeamRatingRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 熟手標籤與領班逐人評核（production 模式）
 * 種子：W011、W026 於 ORD-2026-002＋013 累積採收 > 80 小時 → 採收熟手；
 *       W023 同樣達標但 013 最後兩天領班評「需加強」→ 暫停待複核；W009／W010／W024 只有 56 小時
 * 帳號：worker_local = W026（工班 B 領班）、worker_foreign = W001（工班 A 組長）
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:agri_skill_test;DB_CLOSE_DELAY=-1",
        "agri.demo.enabled=false",
        "agri.security.token-secret=unit-test-token-secret-0123456789abcdef",
        "agri.security.admin-password=Admin#Pass2026"
})
@AutoConfigureMockMvc
class SkillApiTest extends ApiTestSupport {

    @Autowired AttendanceRepository attendanceRepository;
    @Autowired TeamRatingRepository ratingRepository;

    private String admin;

    @BeforeEach
    void setUp() throws Exception {
        admin = login("admin_tech", "Admin#Pass2026");
    }

    private String tokenFor(String username) throws Exception {
        MvcResult r = call("POST", "/api/auth/reset-password", admin, Map.of("identifier", username, "newPassword", "Test#2026pw"));
        assertEquals(200, r.getResponse().getStatus());
        return login(username, "Test#2026pw");
    }

    private JsonNode harvest(String workerId) throws Exception {
        JsonNode p = json(call("GET", "/api/skills/workers/" + workerId, admin, null)).get("data");
        for (JsonNode s : p.get("skills")) if ("harvest".equals(s.get("key").asText())) return s;
        throw new AssertionError("no harvest skill");
    }

    private int status(String method, String url, String token, Object body) throws Exception {
        return call(method, url, token, body).getResponse().getStatus();
    }

    @Test
    void seededSkillsSuspensionAndRestore() throws Exception {
        JsonNode w011 = harvest("W011");
        assertEquals("skilled", w011.get("level").asText());
        assertTrue(w011.get("active").asBoolean());
        assertEquals(84.0, w011.get("hours").asDouble(), 0.01);
        assertEquals(2, w011.get("orders").asInt());

        JsonNode w009 = harvest("W009");
        assertEquals("none", w009.get("level").asText());
        assertTrue(w009.get("next").toString().contains("56.0／80"), w009.get("next").toString());

        JsonNode w023 = harvest("W023");
        assertEquals("skilled", w023.get("level").asText());
        assertTrue(w023.get("suspended").asBoolean());
        assertFalse(w023.get("active").asBoolean());
        assertTrue(w023.get("suspendReasons").toString().contains("需加強"));

        JsonNode team = json(call("GET", "/api/skills/teams/team_b", admin, null)).get("data");
        assertEquals(2, team.get("skills").get("harvest").get("skilled").asInt(), "W011、W026（W023 暫停不計）");
        int verifiedBefore = team.get("verified").asInt();

        // 管理員複核恢復 → W023 重新計入，但近 6 個月曾暫停，不給履歷認證
        assertEquals(200, status("POST", "/api/skills/workers/W023/harvest/restore", admin, Map.of("note", "已面談並補訓")));
        assertTrue(harvest("W023").get("active").asBoolean());
        JsonNode p023 = json(call("GET", "/api/skills/workers/W023", admin, null)).get("data");
        assertFalse(p023.get("verified").asBoolean());
        assertTrue(p023.get("unverifiedReasons").toString().contains("近 6 個月"));
        JsonNode teamAfter = json(call("GET", "/api/skills/teams/team_b", admin, null)).get("data");
        assertEquals(3, teamAfter.get("skills").get("harvest").get("skilled").asInt());
        assertEquals(verifiedBefore, teamAfter.get("verified").asInt());
        assertEquals(400, status("POST", "/api/skills/workers/W023/drone/restore", admin, Map.of()));
    }

    @Test
    void seniorLevelFromSyntheticHistory() throws Exception {
        // W012：4 張採收類訂單、每張 8 班 × 8 小時 = 256 小時，全部「優」，2 張有評價且皆 5 星
        List<Attendance> list = new ArrayList<>();
        LocalDate start = LocalDate.of(2026, 8, 1);
        String[] orders = {"ORD-2026-001", "ORD-2026-002", "ORD-2026-005", "ORD-2026-008"};
        for (int o = 0; o < orders.length; o++) {
            for (int d = 0; d < 8; d++) {
                LocalDate day = start.plusDays(o * 8 + d);
                Attendance a = new Attendance("W012", day.atTime(7, 0), "測試", 1.0);
                a.completeShift(day.atTime(15, 0), "測試領班");
                a.setOrderId(orders[o]);
                a.setForemanRating("excellent");
                a.setForemanRatedBy("W006");
                a.setForemanRatedAt(day.atTime(16, 0));
                list.add(a);
            }
        }
        attendanceRepository.saveAll(list);
        TeamRating r = new TeamRating();
        r.setId("RAT-ORD-2026-001");
        r.setOrderId("ORD-2026-001");
        r.setTeamId("team_a");
        r.setScore(5);
        r.setCreatedAt(LocalDateTime.now());
        r.setUpdatedAt(LocalDateTime.now());
        ratingRepository.save(r);

        JsonNode s = harvest("W012");
        assertEquals("senior", s.get("level").asText(), s.toString());
        assertEquals(256.0, s.get("hours").asDouble(), 0.01);
    }

    @Test
    void foremanEvaluationPermissions() throws Exception {
        String foreman = tokenFor("worker_local");   // W026，工班 B 領班
        String otherLeader = tokenFor("worker_foreign"); // W001，工班 A 組長
        String farmer = tokenFor("farmer");

        JsonNode day = json(call("GET", "/api/evaluations/teams/team_b?date=2026-09-27", foreman, null)).get("data");
        assertTrue(day.get("shifts").size() >= 6);
        Long ownShift = null, w023Shift = null;
        for (JsonNode sh : day.get("shifts")) {
            if (sh.get("self").asBoolean()) ownShift = sh.get("attendanceId").asLong();
            if ("W023".equals(sh.get("workerId").asText())) {
                w023Shift = sh.get("attendanceId").asLong();
                assertEquals("needs_improvement", sh.get("rating").asText());
            }
        }
        assertNotNull(ownShift);
        assertEquals(403, status("GET", "/api/evaluations/teams/team_a", foreman, null), "非本工班領班");
        assertEquals(403, status("GET", "/api/evaluations/teams/team_b", otherLeader, null));
        assertEquals(403, status("GET", "/api/evaluations/teams/team_b", farmer, null));
        assertEquals(403, status("PUT", "/api/evaluations/attendance/" + ownShift, foreman, Map.of("rating", "excellent")), "不可評自己");
        assertEquals(403, status("PUT", "/api/evaluations/attendance/" + w023Shift, otherLeader, Map.of("rating", "excellent")));
        assertEquals(400, status("PUT", "/api/evaluations/attendance/" + w023Shift, foreman, Map.of("rating", "great")));

        // 超過 7 天的班次領班不可改，admin 可
        Long oldShift = null;
        for (JsonNode sh : json(call("GET", "/api/evaluations/teams/team_b?date=2026-09-19", foreman, null)).get("data").get("shifts")) {
            if ("W009".equals(sh.get("workerId").asText())) oldShift = sh.get("attendanceId").asLong();
        }
        assertNotNull(oldShift);
        if (LocalDate.of(2026, 9, 19).isBefore(LocalDate.now().minusDays(7))) {
            assertEquals(409, status("PUT", "/api/evaluations/attendance/" + oldShift, foreman, Map.of("rating", "excellent")));
        }
        assertEquals(200, status("PUT", "/api/evaluations/attendance/" + oldShift, admin, Map.of("rating", "excellent")));

        // 今日新班次：未下班不可評，下班後領班可評
        MvcResult in = call("POST", "/api/workers/W010/clock-in", admin, Map.of("orderId", "ORD-2026-013"));
        assertEquals(200, in.getResponse().getStatus(), in.getResponse().getContentAsString(StandardCharsets.UTF_8));
        long todayShift = json(in).get("data").get("recordId").asLong();
        assertEquals(409, status("PUT", "/api/evaluations/attendance/" + todayShift, foreman, Map.of("rating", "ok")));
        assertEquals(200, status("POST", "/api/workers/W010/clock-out", admin, Map.of()));
        MvcResult rated = call("PUT", "/api/evaluations/attendance/" + todayShift, foreman, Map.of("rating", "excellent"));
        assertEquals(200, rated.getResponse().getStatus());
        assertEquals("優", json(rated).get("data").get("ratingLabel").asText());
    }

    @Test
    void skillVisibility() throws Exception {
        String farmer = tokenFor("farmer");
        assertEquals(403, status("GET", "/api/skills/workers/W011", farmer, null));
        assertEquals(403, status("GET", "/api/skills/workers", farmer, null));
        assertEquals(403, status("POST", "/api/skills/workers/W023/harvest/restore", farmer, Map.of()));
        JsonNode team = json(call("GET", "/api/skills/teams/team_b", farmer, null)).get("data");
        assertFalse(team.toString().contains("W011"), "農場主只看到人數");
        String worker = tokenFor("worker_local");
        assertEquals(200, status("GET", "/api/skills/workers/W026", worker, null), "本人可查");
        assertEquals(403, status("GET", "/api/skills/workers/W011", worker, null), "不可查他人");
        assertEquals(401, status("GET", "/api/skills/teams/team_b", null, null));
    }
}
