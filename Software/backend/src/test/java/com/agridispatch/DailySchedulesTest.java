package com.agridispatch;

import com.agridispatch.entity.Order;
import com.agridispatch.entity.SharedRoster;
import com.agridispatch.repository.LineUserRepository;
import com.agridispatch.repository.OrderRepository;
import com.agridispatch.repository.SharedRosterRepository;
import com.agridispatch.service.LineNotifyService;
import com.agridispatch.service.RosterService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MvcResult;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 每天定時執行的兩個排程：直接呼叫排程方法驗證效果，不等真的時間。
 * - 00:05 RosterService.evaluateDeadlinesDaily：過截止日的開放班表達標開班、未達標取消（連帶取消認領訂單）；
 *   已開班且期間已過改為已結束。
 * - 18:00 LineNotifyService.scheduledTomorrow：明日出工通知推給出工名單（工作者帳號），同一天只自動發一次。
 * 兩個 cron 在測試中以 "-" 停用，避免測試期間剛好跑到真的排程。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:agri_daily_schedules_test;DB_CLOSE_DELAY=-1",
        "agri.demo.enabled=true",
        "agri.security.token-secret=unit-test-token-secret-0123456789abcdef",
        "agri.line.notify.async=false",
        "agri.line.notify.worker-hours=00:00-24:00",
        "agri.line.notify.tomorrow-cron=-",
        "agri.roster.evaluate-cron=-"
})
@AutoConfigureMockMvc
@Import(RecordingLineBotClient.Config.class)
class DailySchedulesTest extends ApiTestSupport {

    private static final List<String> CREW = List.of("W026", "W033", "W034");
    private static final String VN_UID = "U" + "7".repeat(31) + "1";   // W001（越南）
    private static final String ID_UID = "U" + "8".repeat(31) + "1";   // W006（印尼）
    private static final String FARMER_UID = "U" + "6".repeat(31) + "1";

    @Autowired RosterService rosterService;
    @Autowired LineNotifyService lineNotifyService;
    @Autowired SharedRosterRepository rosterRepository;
    @Autowired OrderRepository orderRepository;
    @Autowired LineUserRepository lineUserRepository;
    @Autowired RecordingLineBotClient bot;

    private String admin;
    private String farmer;

    @BeforeEach
    void setUp() throws Exception {
        admin = login("admin_tech", "admin888");
        farmer = login("farmer", "123456");
    }

    private static String body(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    // =========================================================================
    // 排程設定
    // =========================================================================

    @Test
    void cronExpressionsAreDailyAt0005And1800() throws Exception {
        Method roster = RosterService.class.getMethod("evaluateDeadlinesDaily");
        assertEquals("${agri.roster.evaluate-cron:0 5 0 * * *}", roster.getAnnotation(Scheduled.class).cron());
        Method tomorrow = LineNotifyService.class.getMethod("scheduledTomorrow");
        assertEquals("${agri.line.notify.tomorrow-cron:0 0 18 * * *}", tomorrow.getAnnotation(Scheduled.class).cron());
    }

    // =========================================================================
    // 00:05 共享班表截止日判斷
    // =========================================================================

    private String createRoster(LocalDate start, int days, int minPercent) throws Exception {
        Map<String, Object> req = new HashMap<>();
        req.put("title", "排程測試班表");
        req.put("region", "屏東縣枋山鄉");
        req.put("teamId", "team_b");
        req.put("crew", CREW);
        req.put("startDate", start.toString());
        req.put("endDate", start.plusDays(days - 1).toString());
        req.put("minClaimPercent", minPercent);
        MvcResult r = call("POST", "/api/rosters", admin, req);
        assertEquals(201, r.getResponse().getStatus(), body(r));
        return json(r).get("data").get("id").asText();
    }

    private String claim(String rosterId, LocalDate day, String slot) throws Exception {
        Map<String, Object> c = new HashMap<>();
        c.put("date", day.toString());
        c.put("timeSlot", slot);
        c.put("farmerName", "陳建龍");
        c.put("phone", "0912-345-678");
        c.put("farmLocation", "屏東縣枋山鄉善餘村 12 號");
        c.put("cropId", "mango");
        c.put("area", 1.5);
        c.put("plan", "early_bird");
        MvcResult r = call("POST", "/api/rosters/" + rosterId + "/claims", farmer, c);
        assertEquals(201, r.getResponse().getStatus(), body(r));
        return json(r).get("data").get("id").asText();
    }

    /** 直接改資料庫的日期，模擬「時間已經過去」 */
    private void setDates(String rosterId, LocalDate start, LocalDate end, LocalDate deadline) {
        SharedRoster r = rosterRepository.findById(rosterId).orElseThrow();
        if (start != null) r.setStartDate(start);
        if (end != null) r.setEndDate(end);
        r.setClaimDeadline(deadline);
        rosterRepository.save(r);
    }

    private String rosterStatus(String id) {
        return rosterRepository.findById(id).orElseThrow().getStatus();
    }

    private String orderStatus(String id) {
        return orderRepository.findById(id).orElseThrow().getStatus();
    }

    @Test
    void rosterDeadlineJobAt0005() throws Exception {
        LocalDate today = LocalDate.now();
        LocalDate base = today.plusDays(150);

        // A：過截止日、1/4 = 25% 未達 60% → 取消，認領訂單一併取消
        String a = createRoster(base, 2, 60);
        String aOrder = claim(a, base, "morning");
        setDates(a, null, null, today.minusDays(1));

        // B：過截止日、2/4 = 50% 達 50% → 開班，認領訂單維持
        String b = createRoster(base.plusDays(10), 2, 50);
        String bOrder = claim(b, base.plusDays(10), "full_day");
        setDates(b, null, null, today.minusDays(1));

        // C：截止日就是今天 → 還沒過，維持開放（即使未達門檻）
        String c = createRoster(base.plusDays(20), 2, 60);
        setDates(c, null, null, today);

        // D：已開班、期間已過 → 已結束
        String d = createRoster(base.plusDays(30), 1, 50);
        claim(d, base.plusDays(30), "full_day");
        assertEquals(200, call("POST", "/api/rosters/" + d + "/confirm", admin, null).getResponse().getStatus());
        setDates(d, today.minusDays(3), today.minusDays(1), today.minusDays(6));

        // E：已開班、今天仍在期間內 → 維持已開班
        String e = createRoster(base.plusDays(40), 1, 50);
        claim(e, base.plusDays(40), "full_day");
        assertEquals(200, call("POST", "/api/rosters/" + e + "/confirm", admin, null).getResponse().getStatus());
        setDates(e, today.minusDays(1), today, today.minusDays(4));

        // F：過截止日、未達門檻，但認領訂單已施工中 → 無法自動取消，維持開放等合作社處理
        String f = createRoster(base.plusDays(50), 2, 60);
        String fOrder = claim(f, base.plusDays(50), "morning");
        Order fo = orderRepository.findById(fOrder).orElseThrow();
        fo.setStatus("in_progress");
        orderRepository.save(fo);
        setDates(f, null, null, today.minusDays(1));

        rosterService.evaluateDeadlinesDaily();

        assertEquals("cancelled", rosterStatus(a));
        String reason = rosterRepository.findById(a).orElseThrow().getCancelReason();
        assertTrue(reason.contains("未達門檻") && reason.contains("25%") && reason.contains("60%"), reason);
        assertEquals("cancelled", orderStatus(aOrder));
        assertEquals("confirmed", rosterStatus(b));
        assertEquals("pending", orderStatus(bOrder));
        assertEquals("open", rosterStatus(c));
        assertEquals("completed", rosterStatus(d));
        assertEquals("confirmed", rosterStatus(e));
        assertEquals("open", rosterStatus(f));
        assertEquals("in_progress", orderStatus(fOrder));

        // 農戶視角：取消的看不到；開班的仍可認領剩下的時段
        assertEquals(404, call("GET", "/api/rosters/" + a, farmer, null).getResponse().getStatus());
        assertTrue(json(call("GET", "/api/rosters/" + b, farmer, null)).get("data").get("claimable").asBoolean());

        // 再跑一次（例如重啟後補跑）：狀態不再變動
        rosterService.evaluateDeadlinesDaily();
        assertEquals(List.of("cancelled", "confirmed", "open", "completed", "confirmed", "open"),
                List.of(rosterStatus(a), rosterStatus(b), rosterStatus(c), rosterStatus(d), rosterStatus(e), rosterStatus(f)));
        assertEquals("pending", orderStatus(bOrder));

        // 隔天（C 的截止日已過、仍未達門檻）→ 取消；E 期間已過 → 已結束
        Map<String, Object> next = rosterService.evaluateDeadlines(today.plusDays(1));
        assertTrue(((List<?>) next.get("cancelled")).contains(c), next.toString());
        assertTrue(((List<?>) next.get("completed")).contains(e), next.toString());
        assertEquals("cancelled", rosterStatus(c));
        assertEquals("completed", rosterStatus(e));
    }

    // =========================================================================
    // 18:00 明日出工通知
    // =========================================================================

    private void bind(String login, String channel, String uid) throws Exception {
        MvcResult r = call("POST", "/api/line/bind", login(login, "123456"), Map.of("channel", channel, "idToken", "mock:" + uid));
        assertEquals(200, r.getResponse().getStatus(), body(r));
    }

    private void prepare(String orderId, String status, LocalDate start, LocalDate end, String crew) {
        Order o = orderRepository.findById(orderId).orElseThrow();
        o.setFarmerId("F001");
        o.setStatus(status);
        o.setStartDate(start);
        o.setEndDate(end);
        o.setCrewWorkerIds(crew);
        orderRepository.save(o);
    }

    private List<RecordingLineBotClient.Call> tomorrowCallsFor(String orderId) {
        return bot.of("worker", "tomorrow_task").stream().filter(c -> orderId.equals(c.params().get("orderId"))).toList();
    }

    @Test
    void tomorrowNoticeJobAt1800() throws Exception {
        lineUserRepository.deleteAll();
        bind("farmer", "farmer", FARMER_UID);
        bind("W001", "worker", VN_UID);
        bind("W006", "worker", ID_UID);
        LocalDate tomorrow = LocalDate.now().plusDays(1);

        // 明天在工期內：已排定（只做明天）、施工中（今天到後天）→ 通知；待確認、已取消、工期在後天 → 不通知
        prepare("ORD-2026-001", "confirmed", tomorrow, tomorrow, "W001");
        prepare("ORD-2026-014", "in_progress", LocalDate.now(), tomorrow.plusDays(1), "W006");
        String pendingId = null, cancelledId = null, laterId = null;
        for (Order o : orderRepository.findAll()) {
            if (List.of("ORD-2026-001", "ORD-2026-014").contains(o.getId())) continue;
            if (pendingId == null) { pendingId = o.getId(); prepare(pendingId, "pending", tomorrow, tomorrow, "W001,W006"); }
            else if (cancelledId == null) { cancelledId = o.getId(); prepare(cancelledId, "cancelled", tomorrow, tomorrow, "W001,W006"); }
            else if (laterId == null) { laterId = o.getId(); prepare(laterId, "confirmed", tomorrow.plusDays(1), tomorrow.plusDays(2), "W001,W006"); }
        }
        assertNotNull(laterId);

        ReflectionTestUtils.setField(lineNotifyService, "tomorrowSentFor", null);
        bot.clear();
        lineNotifyService.scheduledTomorrow();

        List<RecordingLineBotClient.Call> c1 = tomorrowCallsFor("ORD-2026-001");
        assertEquals(1, c1.size());
        assertEquals(tomorrow.toString(), c1.get(0).params().get("date"));
        assertEquals(List.of(Map.of("lineUserId", VN_UID, "lang", "vi")), c1.get(0).recipients());
        List<RecordingLineBotClient.Call> c2 = tomorrowCallsFor("ORD-2026-014");
        assertEquals(1, c2.size());
        assertEquals(List.of(Map.of("lineUserId", ID_UID, "lang", "id")), c2.get(0).recipients());
        assertTrue(tomorrowCallsFor(pendingId).isEmpty(), "待確認訂單不通知");
        assertTrue(tomorrowCallsFor(cancelledId).isEmpty(), "已取消訂單不通知");
        assertTrue(tomorrowCallsFor(laterId).isEmpty(), "工期不含明天不通知");
        // 只走工作者帳號，不推給農場主帳號
        assertTrue(bot.calls.stream().noneMatch(c -> "farmer".equals(c.channel())), bot.calls.toString());
        assertEquals(tomorrow, ReflectionTestUtils.getField(lineNotifyService, "tomorrowSentFor"));

        // 同一天再觸發（例如排程重複執行）：不重發
        int before = bot.calls.size();
        lineNotifyService.scheduledTomorrow();
        assertEquals(before, bot.calls.size());

        // 後台手動補發不受「一天一次」限制
        MvcResult manual = call("POST", "/api/line/notify/tomorrow", admin, Map.of());
        assertEquals(200, manual.getResponse().getStatus(), body(manual));
        assertEquals(2, tomorrowCallsFor("ORD-2026-001").size());

        // 換一天（記錄為前一天）才會再自動發
        ReflectionTestUtils.setField(lineNotifyService, "tomorrowSentFor", LocalDate.now());
        bot.clear();
        lineNotifyService.scheduledTomorrow();
        assertEquals(1, tomorrowCallsFor("ORD-2026-001").size());
    }

    @Test
    void tomorrowNoticeSkipsUnboundAndBlockedCrew() throws Exception {
        lineUserRepository.deleteAll();
        bind("W001", "worker", VN_UID);
        bind("W006", "worker", ID_UID);
        var blocked = lineUserRepository.findByChannelAndLineUserId("worker", ID_UID).orElseThrow();
        blocked.setFollowed("N");
        lineUserRepository.save(blocked);
        LocalDate tomorrow = LocalDate.now().plusDays(1);
        prepare("ORD-2026-001", "confirmed", tomorrow, tomorrow, "W001,W006,W002");

        ReflectionTestUtils.setField(lineNotifyService, "tomorrowSentFor", null);
        bot.clear();
        lineNotifyService.scheduledTomorrow();
        List<RecordingLineBotClient.Call> calls = tomorrowCallsFor("ORD-2026-001");
        assertEquals(1, calls.size());
        // W006 封鎖、W002 未綁定 → 只推 W001
        assertEquals(List.of(VN_UID), calls.get(0).recipients().stream().map(m -> m.get("lineUserId")).toList());
    }
}
