package com.agridispatch.service;

import com.agridispatch.config.AgriSettings;
import com.agridispatch.entity.LineUser;
import com.agridispatch.entity.Order;
import com.agridispatch.repository.DispatchTeamRepository;
import com.agridispatch.repository.LineUserRepository;
import com.agridispatch.repository.OrderRepository;
import com.agridispatch.repository.WorkerRepository;
import com.agridispatch.security.AuthContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * LINE 主動推播（規格 文件/07_系統分析及文件/05_LINE官方帳號拆分規格.md 第柒章）
 *
 * 後端決定「推給誰」：只推給已綁定且未封鎖的使用者（第 2 點），工作者帳號只推給出工名單（第 1 點，禁止 broadcast）；
 * LINE Bot 決定「怎麼說」：依收件人語言分組產生訊息、multicast（第 3 點），失敗重試 1 次（第 5 點）。
 *
 * 工作者帳號只在 agri.line.notify.worker-hours（預設 07:00-20:00）內推播（第 4 點【待確認：可推播時段與工時認定】），
 * 時段外的通知不補發，只回報略過人數。
 *
 * 推播一律在背景執行，不拖慢或影響訂單、付款、天候等原本的操作（測試以 agri.line.notify.async=false 同步執行）。
 */
@Service
public class LineNotifyService {

    private static final Logger log = LoggerFactory.getLogger(LineNotifyService.class);

    private final LineUserRepository lineUserRepository;
    private final OrderRepository orderRepository;
    private final WorkerRepository workerRepository;
    private final DispatchTeamRepository teamRepository;
    private final LineBotClient lineBotClient;
    private final AgriSettings settings;
    private final boolean async;
    private final LocalTime workerFrom;
    private final LocalTime workerTo;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "line-notify");
        t.setDaemon(true);
        return t;
    });

    /** 明日出工通知：同一天只自動發一次（記憶體紀錄；手動補發不受限） */
    private volatile LocalDate tomorrowSentFor = null;

    /** 測試與後台顯示用：可替換「現在時間」 */
    Supplier<LocalTime> clock = LocalTime::now;

    public LineNotifyService(LineUserRepository lineUserRepository, OrderRepository orderRepository,
                             WorkerRepository workerRepository, DispatchTeamRepository teamRepository,
                             LineBotClient lineBotClient, AgriSettings settings,
                             @Value("${agri.line.notify.async:true}") boolean async,
                             @Value("${agri.line.notify.worker-hours:07:00-20:00}") String workerHours) {
        this.lineUserRepository = lineUserRepository;
        this.orderRepository = orderRepository;
        this.workerRepository = workerRepository;
        this.teamRepository = teamRepository;
        this.lineBotClient = lineBotClient;
        this.settings = settings;
        this.async = async;
        String[] h = (workerHours == null || !workerHours.contains("-") ? "07:00-20:00" : workerHours).split("-");
        this.workerFrom = parseTime(h[0], LocalTime.of(7, 0));
        this.workerTo = parseTime(h[1], LocalTime.of(20, 0));
    }

    private static LocalTime parseTime(String s, LocalTime def) {
        try {
            String t = s.trim();
            return "24:00".equals(t) ? LocalTime.MAX : LocalTime.parse(t.length() == 4 ? "0" + t : t);
        } catch (Exception e) {
            return def;
        }
    }

    /** 現在是否在工作者帳號可推播時段 */
    public boolean workerHoursNow() {
        LocalTime now = clock.get();
        return !now.isBefore(workerFrom) && now.isBefore(workerTo);
    }

    public String workerHoursLabel() {
        return workerFrom + "-" + (workerTo.equals(LocalTime.MAX) ? "24:00" : workerTo.toString());
    }

    // =========================================================================
    // 事件
    // =========================================================================

    /** 訂單狀態變更（admin PUT /api/orders/{id}/status）→ 農場主帳號，推給訂單的農場主 */
    public void orderStatusChanged(Order order) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("orderId", order.getId());
        params.put("status", order.getStatus());
        params.put("statusLabel", OrderService.statusLabel(order.getStatus()));
        params.put("crop", nz(order.getCropName()));
        params.put("period", period(order));
        submit(() -> send(LineUser.CHANNEL_FARMER, "order_status", params, farmerRecipients(Set.of(nz(order.getFarmerId())))));
    }

    /** 定金付款成功 → 農場主帳號，推給訂單的農場主 */
    public void depositPaid(Order order) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("orderId", order.getId());
        params.put("amount", order.getDepositAmount());
        params.put("crop", nz(order.getCropName()));
        params.put("statusLabel", OrderService.statusLabel(order.getStatus()));
        submit(() -> send(LineUser.CHANNEL_FARMER, "deposit_paid", params, farmerRecipients(Set.of(nz(order.getFarmerId())))));
    }

    /**
     * 天候發布（科技後台切換天候情境）：只在切到警戒（warning／danger）時通知今天施工中的訂單：
     * 農場主收「訂單受影響」，出工名單收「集合／室內作業」
     */
    public void weatherChanged(String previousScenario, Map<String, Object> state) {
        String scenario = String.valueOf(state.get("scenarioId"));
        String level = String.valueOf(state.get("alertLevel"));
        if (scenario.equals(previousScenario) || !(level.equals("warning") || level.equals("danger"))) return;
        String weather = String.valueOf(state.getOrDefault("currentWeather", ""));
        submit(() -> {
            List<Order> today = activeOrdersOn(LocalDate.now(), true);
            if (today.isEmpty()) return summary("weather", 0, 0, 0, 0);
            Set<String> farmers = new LinkedHashSet<>();
            today.forEach(o -> farmers.add(nz(o.getFarmerId())));
            Map<String, Object> fp = new LinkedHashMap<>();
            fp.put("weather", weather);
            fp.put("orders", today.stream().map(Order::getId).toList());
            Map<String, Object> farmerResult = send(LineUser.CHANNEL_FARMER, "weather_farmer", fp, farmerRecipients(farmers));

            // 工人：依所屬工班是否改到室內，分兩批
            Map<Boolean, Set<String>> byIndoor = new LinkedHashMap<>();
            for (Order o : today) {
                boolean indoor = teamRepository.findById(nz(o.getAssignedTeamId()))
                        .map(t -> "hedged_indoor".equals(t.getStatus())).orElse(false);
                byIndoor.computeIfAbsent(indoor, k -> new LinkedHashSet<>()).addAll(crewOf(o));
            }
            int sent = 0, skipped = 0;
            for (Map.Entry<Boolean, Set<String>> e : byIndoor.entrySet()) {
                Map<String, Object> wp = new LinkedHashMap<>();
                wp.put("weather", weather);
                wp.put("indoor", e.getKey());
                Map<String, Object> r = sendWorker("weather_worker", wp, workerRecipients(e.getValue()));
                sent += num(r.get("recipients"));
                skipped += num(r.get("skippedQuietHours"));
            }
            return summary("weather", today.size(), num(farmerResult.get("recipients")), sent, skipped);
        });
    }

    /** 每日排程：明日出工通知（預設 18:00；時間【待確認】） */
    @Scheduled(cron = "${agri.line.notify.tomorrow-cron:0 0 18 * * *}")
    public void scheduledTomorrow() {
        LocalDate target = LocalDate.now().plusDays(1);
        if (target.equals(tomorrowSentFor)) return;
        tomorrowSentFor = target;
        submit(() -> notifyTomorrow(target));
    }

    /** 明日出工通知（排程或後台手動）：隔天在工期內、已排定或施工中的訂單，推給出工名單 */
    public Map<String, Object> notifyTomorrow(LocalDate date) {
        List<Order> orders = activeOrdersOn(date, false);
        int recipients = 0, skipped = 0;
        for (Order o : orders) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("orderId", o.getId());
            p.put("date", date.toString());
            p.put("crop", nz(o.getCropName()));
            p.put("place", nz(o.getFarmLocation()));
            p.put("leader", nz(o.getForemanName()));
            Map<String, Object> r = sendWorker("tomorrow_task", p, workerRecipients(crewOf(o)));
            recipients += num(r.get("recipients"));
            skipped += num(r.get("skippedQuietHours"));
        }
        Map<String, Object> s = summary("tomorrow_task", orders.size(), 0, recipients, skipped);
        s.put("date", date.toString());
        return s;
    }

    /** 一次薪資入帳通知的上限人數 */
    static final int SALARY_MAX_ITEMS = 500;

    /**
     * 薪資入帳通知（科技後台手動，規格第柒章）：合作社實際撥款後，後台帶「實發金額」逐人推給工人本人。
     * 系統沒有發薪作業（薪資只有試算），所以金額一律由呼叫端提供，不用試算值，避免通知與實際入帳不符。
     * items：[{workerId, netPay}]；month：yyyy-MM；payDate：可省略。時段外與外籍功能關閉的規則同其他工作者通知。
     */
    public Map<String, Object> salaryPaid(String month, String payDate, List<?> items) {
        if (month == null || !month.matches("\\d{4}-(0[1-9]|1[0-2])")) {
            throw com.agridispatch.common.ApiException.badRequest("month 格式須為 yyyy-MM");
        }
        if (payDate != null && !payDate.isBlank()) {
            try {
                LocalDate.parse(payDate.trim());
            } catch (Exception e) {
                throw com.agridispatch.common.ApiException.badRequest("payDate 格式須為 yyyy-MM-dd");
            }
        }
        if (items == null || items.isEmpty() || items.size() > SALARY_MAX_ITEMS) {
            throw com.agridispatch.common.ApiException.badRequest("items 須為 1～" + SALARY_MAX_ITEMS + " 筆");
        }
        Map<String, Double> pay = new LinkedHashMap<>();
        for (Object o : items) {
            Map<?, ?> it = o instanceof Map<?, ?> m ? m : Map.of();
            String id = it.get("workerId") == null ? "" : String.valueOf(it.get("workerId")).trim().toUpperCase();
            Object net = it.get("netPay");
            double v;
            try {
                v = Double.parseDouble(String.valueOf(net));
            } catch (Exception e) {
                v = -1;
            }
            if (id.isEmpty() || v < 0 || v >= 1_000_000) {
                throw com.agridispatch.common.ApiException.badRequest("每筆須有 workerId 與 0～999,999 的 netPay");
            }
            if (pay.put(id, v) != null) throw com.agridispatch.common.ApiException.badRequest("workerId 重複：" + id);
        }
        int recipients = 0, skipped = 0;
        List<String> unknown = new ArrayList<>(), notReached = new ArrayList<>();
        for (Map.Entry<String, Double> e : pay.entrySet()) {
            if (workerRepository.findById(e.getKey()).isEmpty()) { unknown.add(e.getKey()); continue; }
            List<Map<String, String>> to = workerRecipients(Set.of(e.getKey()));
            if (to.isEmpty()) { notReached.add(e.getKey()); continue; }
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("month", month);
            p.put("netPay", Math.round(e.getValue()));
            if (payDate != null && !payDate.isBlank()) p.put("payDate", payDate.trim());
            Map<String, Object> r = sendWorker("salary_paid", p, to);
            recipients += num(r.get("recipients"));
            skipped += num(r.get("skippedQuietHours"));
        }
        Map<String, Object> s = summary("salary_paid", 0, 0, recipients, skipped);
        s.remove("orders");
        s.remove("farmerRecipients");
        s.put("month", month);
        s.put("workers", pay.size());
        s.put("unknownWorkers", unknown);     // 查無此工號
        s.put("notReached", notReached);      // 未綁定 LINE、已封鎖，或外籍功能關閉
        return s;
    }

    /**
     * 請假審核結果（合作社核准／駁回，或取消已核准的假）→ 工作者帳號，推給請假的工人本人。
     * params：leaveId、status（approved／rejected／cancelled）、leaveType（中文假別）、leaveTypeCode（假別代碼）、period、note（可為空字串）
     */
    public void leaveDecided(String workerId, Map<String, Object> params) {
        submit(() -> sendWorker("leave_decided", params, workerRecipients(Set.of(nz(workerId)))));
    }

    /**
     * 請假對農場主的影響（核准、或取消已核准的假，且工人已排入的訂單與請假重疊）→ 農場主帳號，推給該筆訂單的農場主。
     * params：leaveId、status（approved／cancelled）、workerName、leaveType、period、orders（該農場主受影響的訂單編號）
     */
    public void leaveAffectsFarmer(String farmerId, Map<String, Object> params) {
        submit(() -> send(LineUser.CHANNEL_FARMER, "leave_farmer", params, farmerRecipients(Set.of(nz(farmerId)))));
    }

    /**
     * 請假通知工班領班（申請、核准、駁回、取消）→ 工作者帳號，推給請假工人所屬工班的領班。
     * params：leaveId、status（pending／approved／rejected／cancelled）、workerName、leaveType、period、note
     */
    public void leaveTeamNotice(Collection<String> foremanIds, Map<String, Object> params) {
        submit(() -> sendWorker("leave_team", params, workerRecipients(foremanIds)));
    }

    /** 方案行銷（科技後台手動）→ 只限農場主帳號 broadcast（規格第柒章；工作者帳號禁止 broadcast） */
    public Map<String, Object> farmerBroadcast(String text) {
        if (text == null || text.isBlank() || text.length() > 1000) {
            throw com.agridispatch.common.ApiException.badRequest("訊息內容須為 1～1000 字");
        }
        Map<String, Object> r = lineBotClient.broadcastFarmer(text.trim());
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("success", r != null && Boolean.TRUE.equals(r.get("success")));
        res.put("linebot", r == null ? Map.of("reachable", false) : r);
        return res;
    }

    // =========================================================================
    // 收件人
    // =========================================================================

    /** 已綁定、未封鎖、在該帳號的使用者，依 refId 篩選 */
    List<Map<String, String>> recipients(String channel, Collection<String> refIds) {
        List<Map<String, String>> out = new ArrayList<>();
        Set<String> want = new LinkedHashSet<>();
        refIds.forEach(r -> { if (r != null && !r.isBlank()) want.add(r.toUpperCase()); });
        if (want.isEmpty()) return out;
        for (LineUser u : lineUserRepository.findAllByChannelAndIsBound(channel, "Y")) {
            if (u.isFollowed() && u.getRefId() != null && want.contains(u.getRefId().toUpperCase())) {
                out.add(Map.of("lineUserId", u.getLineUserId(), "lang", u.getLang() == null ? "zh" : u.getLang()));
            }
        }
        return out;
    }

    List<Map<String, String>> farmerRecipients(Collection<String> farmerIds) {
        return recipients(LineUser.CHANNEL_FARMER, farmerIds);
    }

    /** 外籍移工功能關閉時，不推播給外籍工人（依工人 tier 判斷，也排除綁定身分為 worker_foreign 的 LINE 帳號） */
    List<Map<String, String>> workerRecipients(Collection<String> workerIds) {
        if (settings.isForeignWorkersEnabled()) return recipients(LineUser.CHANNEL_WORKER, workerIds);
        Set<String> local = new LinkedHashSet<>();
        for (String id : workerIds) {
            if (id != null && workerRepository.findById(id.trim()).map(settings::isWorkerAvailable).orElse(false)) local.add(id);
        }
        Set<String> foreignBound = new HashSet<>();
        for (LineUser u : lineUserRepository.findAllByChannelAndIsBound(LineUser.CHANNEL_WORKER, "Y")) {
            if (AuthContext.ROLE_WORKER_FOREIGN.equals(u.getRole())) foreignBound.add(u.getLineUserId());
        }
        List<Map<String, String>> out = new ArrayList<>(recipients(LineUser.CHANNEL_WORKER, local));
        out.removeIf(r -> foreignBound.contains(r.get("lineUserId")));
        return out;
    }

    /** 出工名單；沒有名單時以已分派工班的成員代替（與派工前加保檢查相同規則） */
    Set<String> crewOf(Order o) {
        Set<String> ids = new LinkedHashSet<>(o.crewList());
        if (ids.isEmpty() && o.getAssignedTeamId() != null) {
            workerRepository.findByTeamId(o.getAssignedTeamId()).forEach(w -> ids.add(w.getId()));
        }
        return ids;
    }

    /** 指定日期在工期內的已排定／施工中訂單；includeUndatedInProgress：沒填工期但施工中的也算（天候用） */
    List<Order> activeOrdersOn(LocalDate date, boolean includeUndatedInProgress) {
        List<Order> out = new ArrayList<>();
        for (Order o : orderRepository.findAll()) {
            String st = o.getStatus();
            if (!OrderService.CONFIRMED.equals(st) && !OrderService.IN_PROGRESS.equals(st)) continue;
            if (o.getStartDate() != null && o.getEndDate() != null) {
                if (!date.isBefore(o.getStartDate()) && !date.isAfter(o.getEndDate())) out.add(o);
            } else if (includeUndatedInProgress && OrderService.IN_PROGRESS.equals(st)) {
                out.add(o);
            }
        }
        return out;
    }

    // =========================================================================
    // 送出
    // =========================================================================

    private Map<String, Object> sendWorker(String type, Map<String, Object> params, List<Map<String, String>> to) {
        if (!to.isEmpty() && !workerHoursNow()) {
            log.info("[LINE] 工作者帳號推播時段（{}）外，略過 {} 通知 {} 人", workerHoursLabel(), type, to.size());
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("recipients", 0);
            r.put("skippedQuietHours", to.size());
            return r;
        }
        return send(LineUser.CHANNEL_WORKER, type, params, to);
    }

    private Map<String, Object> send(String channel, String type, Map<String, Object> params, List<Map<String, String>> to) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("recipients", to.size());
        if (to.isEmpty()) return r;
        Map<String, Object> res = lineBotClient.notify(channel, type, params, to);
        r.put("linebot", res == null ? Map.of("reachable", false) : res);
        log.info("[LINE] 推播 {}／{} 給 {} 人：{}", channel, type, to.size(), res == null ? "LINE Bot 無回應" : res.get("result"));
        return r;
    }

    private void submit(Supplier<Map<String, Object>> job) {
        Runnable run = () -> {
            try {
                job.get();
            } catch (Exception e) {
                log.warn("[LINE] 推播失敗：{}", e.getMessage());
            }
        };
        if (async) executor.submit(run); else run.run();
    }

    private static Map<String, Object> summary(String type, int orders, int farmers, int workers, int skipped) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("success", true);
        s.put("type", type);
        s.put("orders", orders);
        s.put("farmerRecipients", farmers);
        s.put("workerRecipients", workers);
        s.put("skippedQuietHours", skipped);
        return s;
    }

    private static String period(Order o) {
        if (o.getStartDate() == null) return "";
        return o.getStartDate() + (o.getEndDate() != null && !o.getEndDate().equals(o.getStartDate()) ? " ～ " + o.getEndDate() : "")
                + (o.isHalfDay() ? "（" + Order.slotLabel(o.slot()) + "）" : "");
    }

    private static int num(Object o) {
        return o instanceof Number n ? n.intValue() : 0;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
