package com.agridispatch.service;

import com.agridispatch.common.ApiException;
import com.agridispatch.common.IdGenerator;
import com.agridispatch.config.AgriSettings;
import com.agridispatch.entity.DispatchTeam;
import com.agridispatch.entity.InsuranceRecord;
import com.agridispatch.entity.Order;
import com.agridispatch.entity.SharedRoster;
import com.agridispatch.entity.Worker;
import com.agridispatch.repository.DispatchTeamRepository;
import com.agridispatch.repository.InsuranceRecordRepository;
import com.agridispatch.repository.OrderRepository;
import com.agridispatch.repository.SharedRosterRepository;
import com.agridispatch.repository.WorkerRepository;
import com.agridispatch.security.AuthContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.*;

/**
 * 社區共享班表（第三階段）：合作社開班表、當地農戶認領半日時段。
 *
 * - 開班（admin）：指定工班與固定出工名單、派駐地區、期間（最長 31 天）、派工類別、認領門檻與截止日；
 *   名單成員須工作許可有效、完整加保，且整段期間沒有其他訂單或班表（同 MatchingService 撞期規則）。
 * - 認領（farmer）：每天上午、下午兩個時段，可一次認領整天（佔兩格）；最早認領明天，開放中須在截止日前；
 *   每次認領成立一張訂單（工班、名單沿用班表），金額照一般計價不打折（OrderService.quoteRosterSlot）。
 * - 門檻：截止日過後，認領比例達 minClaimPercent 就開班（confirmed），未達就取消整個班表與其認領訂單；
 *   合作社也可在截止前手動開班（須已達標）或取消。已付定金的退款由合作社人工處理【待確認：退款流程】。
 * - 隱私：農戶只看得到時段「已被認領」，看不到是誰；admin 才看得到認領農戶與訂單編號。
 */
@Service
public class RosterService {
    private static final Logger log = LoggerFactory.getLogger(RosterService.class);

    public static final int MAX_DAYS = 31;
    public static final int DEFAULT_MIN_PERCENT = 60;
    public static final int DEFAULT_DEADLINE_DAYS_BEFORE = 3;
    private static final Set<String> ACTIVE_ORDER = Set.of(OrderService.PENDING, OrderService.CONFIRMED, OrderService.IN_PROGRESS);
    private static final Map<String, String> STATUS_LABELS = Map.of(
            SharedRoster.OPEN, "開放認領", SharedRoster.CONFIRMED, "已開班",
            SharedRoster.CANCELLED, "已取消", SharedRoster.COMPLETED, "已結束");

    private final SharedRosterRepository rosterRepository;
    private final OrderRepository orderRepository;
    private final OrderService orderService;
    private final MatchingService matchingService;
    private final WorkerRepository workerRepository;
    private final DispatchTeamRepository teamRepository;
    private final InsuranceRecordRepository insuranceRecordRepository;
    private final AgriSettings settings;
    private final Object lock = new Object();

    public RosterService(SharedRosterRepository rosterRepository, OrderRepository orderRepository, OrderService orderService,
                         MatchingService matchingService, WorkerRepository workerRepository, DispatchTeamRepository teamRepository,
                         InsuranceRecordRepository insuranceRecordRepository, AgriSettings settings) {
        this.rosterRepository = rosterRepository;
        this.orderRepository = orderRepository;
        this.orderService = orderService;
        this.matchingService = matchingService;
        this.workerRepository = workerRepository;
        this.teamRepository = teamRepository;
        this.insuranceRecordRepository = insuranceRecordRepository;
        this.settings = settings;
    }

    // =========================================================================
    // 查詢
    // =========================================================================

    /** admin：全部班表；farmer：開放認領與已開班的班表 */
    public List<Map<String, Object>> list(AuthContext ctx) {
        requireFarmerOrAdmin(ctx);
        List<SharedRoster> rosters = ctx.isAdmin() ? rosterRepository.findAll()
                : rosterRepository.findByStatusIn(List.of(SharedRoster.OPEN, SharedRoster.CONFIRMED));
        rosters = new ArrayList<>(rosters);
        rosters.sort(Comparator.comparing(SharedRoster::getStartDate).thenComparing(SharedRoster::getId));
        List<Map<String, Object>> out = new ArrayList<>();
        for (SharedRoster r : rosters) out.add(view(r, ctx));
        return out;
    }

    public Map<String, Object> get(AuthContext ctx, String id) {
        requireFarmerOrAdmin(ctx);
        SharedRoster r = require(id);
        if (!ctx.isAdmin() && !r.isActive()) throw ApiException.notFound("找不到共享班表：" + id);
        return view(r, ctx);
    }

    // =========================================================================
    // 開班（admin）
    // =========================================================================

    public Map<String, Object> create(AuthContext ctx, Map<String, Object> req) {
        ctx.requireAdmin();
        LocalDate today = LocalDate.now();
        SharedRoster r = new SharedRoster();
        r.setTitle(text(req.get("title"), "班表名稱 (title)", 100, true));
        r.setRegion(text(req.get("region"), "派駐地區 (region)", 100, true));
        r.setDescription(text(req.get("description"), "說明 (description)", 500, false));

        String category = str(req.get("dispatchCategory"));
        if (category == null) category = "harvest_grading";
        if (!OrderService.CATEGORIES.contains(category)) throw ApiException.badRequest("不支援的派工類別：" + category);
        if ("typhoon_emergency".equals(category)) throw ApiException.badRequest("防颱搶收為緊急派工，不適用共享班表");
        r.setDispatchCategory(category);

        LocalDate start = date(req.get("startDate"), "startDate", true);
        LocalDate end = date(req.get("endDate"), "endDate", true);
        if (!start.isAfter(today)) throw ApiException.badRequest("開工日須在今天之後");
        if (end.isBefore(start)) throw ApiException.badRequest("結束日不可早於開工日");
        if (start.plusDays(MAX_DAYS - 1).isBefore(end)) throw ApiException.badRequest("共享班表最長 " + MAX_DAYS + " 天");
        r.setStartDate(start);
        r.setEndDate(end);

        LocalDate deadline = date(req.get("claimDeadline"), "claimDeadline", false);
        if (deadline == null) deadline = start.minusDays(DEFAULT_DEADLINE_DAYS_BEFORE);
        if (deadline.isBefore(today)) deadline = today;
        if (!deadline.isBefore(start)) throw ApiException.badRequest("認領截止日須早於開工日");
        r.setClaimDeadline(deadline);

        int min = req.get("minClaimPercent") == null ? DEFAULT_MIN_PERCENT : intOf(req.get("minClaimPercent"), "minClaimPercent");
        if (min < 1 || min > 100) throw ApiException.badRequest("認領門檻 (minClaimPercent) 須介於 1～100");
        r.setMinClaimPercent(min);

        String teamId = str(req.get("teamId"));
        if (teamId == null || !teamRepository.existsById(teamId)) throw ApiException.badRequest("找不到工班：" + teamId);
        r.setTeamId(teamId);
        if (!(req.get("crew") instanceof Collection<?> c) || c.isEmpty()) throw ApiException.badRequest("crew 必須為至少 1 位工人的陣列");
        LinkedHashSet<String> crew = new LinkedHashSet<>();
        for (Object o : c) crew.add(String.valueOf(o).trim());
        checkCrew(teamId, crew, start, end, today);
        r.setCrewWorkerIds(String.join(",", crew));
        r.setStatus(SharedRoster.OPEN);

        synchronized (lock) {
            String prefix = "SR-" + today.getYear() + "-";
            r.setId(IdGenerator.next(prefix, 3, rosterRepository.findIdsByPrefix(prefix), rosterRepository::existsById));
            r = rosterRepository.save(r);
        }
        log.info("[共享班表] 開班 {}（{}，{}～{}，名單 {} 人，門檻 {}%）", r.getId(), r.getRegion(), start, end, crew.size(), min);
        return view(r, ctx);
    }

    /** 名單須為該工班成員、目前開放派工、工作許可有效、完整加保，且整段期間沒有其他排班 */
    private void checkCrew(String teamId, Collection<String> crew, LocalDate start, LocalDate end, LocalDate today) {
        Map<String, Worker> members = new HashMap<>();
        for (Worker w : workerRepository.findByTeamId(teamId)) members.put(w.getId(), w);
        List<InsuranceRecord> records = insuranceRecordRepository.findByWorkerIdIn(members.keySet());
        Order probe = new Order();
        probe.setStartDate(start);
        probe.setEndDate(end);
        probe.setTimeSlot(Order.FULL_DAY);
        Map<String, Order> busy = matchingService.busyWorkers(probe);
        List<String> problems = new ArrayList<>();
        for (String id : crew) {
            Worker w = members.get(id);
            if (w == null) throw ApiException.badRequest(id + " 不是 " + teamId + " 的成員");
            String name = MatchingService.displayName(w);
            if (!settings.isWorkerAvailable(w)) { problems.add(name + "：外籍移工服務暫停中"); continue; }
            String reason = matchingService.ineligibleReason(w, records, today);
            if (reason == null && busy.containsKey(id)) {
                Order o = busy.get(id);
                reason = "期間內已排入 " + o.getId() + "（" + o.getStartDate() + "～" + o.getEndDate() + "）";
            }
            if (reason != null) problems.add(name + "：" + reason);
        }
        if (!problems.isEmpty()) throw ApiException.conflict("出工名單有不符合資格的工人：" + String.join("；", problems));
    }

    // =========================================================================
    // 認領（farmer）
    // =========================================================================

    /** 認領一個時段（morning／afternoon）或整天（full_day，佔上午、下午兩格），成立一張訂單 */
    public Order claim(AuthContext ctx, String rosterId, Map<String, Object> req) {
        if (!ctx.isFarmer() || ctx.ref() == null || ctx.ref().isBlank()) throw ApiException.forbidden();
        LocalDate today = LocalDate.now();
        LocalDate day = date(req.get("date"), "date", true);
        String slot = OrderService.normalizeSlot(str(req.get("timeSlot")));

        Order input = new Order();
        input.setFarmerName(str(req.get("farmerName")));
        input.setPhone(str(req.get("phone")));
        input.setFarmLocation(str(req.get("farmLocation")));
        input.setFarmId(str(req.get("farmId")));
        input.setCropId(str(req.get("cropId")));
        input.setArea(req.get("area") == null ? 0 : doubleOf(req.get("area")));
        input.setPlan(str(req.get("plan")));
        input.setForemanName(str(req.get("foremanName")));
        input.setForemanPhone(str(req.get("foremanPhone")));
        input.setForemanNotes(str(req.get("foremanNotes")));

        synchronized (lock) {
            SharedRoster r = require(rosterId);
            if (!r.isActive()) throw ApiException.conflict("班表" + STATUS_LABELS.get(r.getStatus()) + "，無法認領");
            if (SharedRoster.OPEN.equals(r.getStatus()) && today.isAfter(r.getClaimDeadline())) {
                throw ApiException.conflict("班表已過認領截止日（" + r.getClaimDeadline() + "），等待合作社確認是否開班");
            }
            if (day.isBefore(r.getStartDate()) || day.isAfter(r.getEndDate())) {
                throw ApiException.badRequest("日期不在班表期間（" + r.getStartDate() + "～" + r.getEndDate() + "）內");
            }
            if (!day.isAfter(today)) throw ApiException.badRequest("最早只能認領明天的時段");
            for (Order o : activeClaims(r)) {
                if (day.equals(o.getStartDate()) && Order.slotsOverlap(slot, o.slot())) {
                    throw ApiException.conflict(day + " " + Order.slotLabel(slot) + " 已被認領，請改選其他時段");
                }
            }
            Order created = orderService.createRosterClaim(input, ctx.ref(), r, day, slot);
            log.info("[共享班表] {} 認領 {} {} → {}", r.getId(), day, slot, created.getId());
            return created;
        }
    }

    // =========================================================================
    // 開班確認／取消（admin）與截止日判斷
    // =========================================================================

    public Map<String, Object> confirm(AuthContext ctx, String id) {
        ctx.requireAdmin();
        synchronized (lock) {
            SharedRoster r = require(id);
            if (!SharedRoster.OPEN.equals(r.getStatus())) throw ApiException.conflict("班表" + STATUS_LABELS.get(r.getStatus()) + "，無法開班");
            int pct = claimPercent(r);
            if (pct < r.getMinClaimPercent()) {
                throw ApiException.conflict("認領 " + pct + "%，未達門檻 " + r.getMinClaimPercent() + "%，無法開班");
            }
            r.setStatus(SharedRoster.CONFIRMED);
            rosterRepository.save(r);
            return view(r, ctx);
        }
    }

    public Map<String, Object> cancel(AuthContext ctx, String id, String reason) {
        ctx.requireAdmin();
        String why = reason == null || reason.isBlank() ? "合作社取消" : reason.trim();
        if (why.length() > 200) throw ApiException.badRequest("取消原因過長（上限 200 字）");
        synchronized (lock) {
            SharedRoster r = require(id);
            Map<String, Object> result = cancelRoster(r, why);
            result.put("roster", view(r, ctx));
            return result;
        }
    }

    /** 取消班表與尚未施工的認領訂單；施工中的訂單不能連帶取消（409） */
    private Map<String, Object> cancelRoster(SharedRoster r, String reason) {
        if (!r.isActive()) throw ApiException.conflict("班表" + STATUS_LABELS.get(r.getStatus()) + "，無法取消");
        List<Order> claims = activeClaims(r);
        if (claims.stream().anyMatch(o -> OrderService.IN_PROGRESS.equals(o.getStatus()))) {
            throw ApiException.conflict("班表已有施工中的訂單，請先處理施工中的時段再取消");
        }
        int refunds = 0;
        List<String> cancelled = new ArrayList<>();
        for (Order o : claims) {
            if (o.isDepositPaid()) refunds++;
            o.setStatus(OrderService.CANCELLED);
            orderRepository.save(o);
            cancelled.add(o.getId());
        }
        r.setStatus(SharedRoster.CANCELLED);
        r.setCancelReason(reason.length() > 200 ? reason.substring(0, 200) : reason);
        rosterRepository.save(r);
        log.info("[共享班表] 取消 {}（{}），連帶取消 {} 張訂單，待退定金 {} 筆", r.getId(), reason, cancelled.size(), refunds);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("cancelledOrders", cancelled);
        m.put("refundsNeeded", refunds);
        return m;
    }

    /** 每天 00:05 判斷：過了截止日的開放班表達標就開班、未達標就取消；已開班且期間已過的改為已結束 */
    @Scheduled(cron = "${agri.roster.evaluate-cron:0 5 0 * * *}")
    public void evaluateDeadlinesDaily() {
        evaluateDeadlines(LocalDate.now());
    }

    public Map<String, Object> evaluateDeadlines(LocalDate today) {
        List<String> confirmed = new ArrayList<>(), cancelled = new ArrayList<>(), completed = new ArrayList<>();
        synchronized (lock) {
            for (SharedRoster r : rosterRepository.findByStatusIn(List.of(SharedRoster.OPEN, SharedRoster.CONFIRMED))) {
                if (SharedRoster.OPEN.equals(r.getStatus()) && today.isAfter(r.getClaimDeadline())) {
                    int pct = claimPercent(r);
                    if (pct >= r.getMinClaimPercent()) {
                        r.setStatus(SharedRoster.CONFIRMED);
                        rosterRepository.save(r);
                        confirmed.add(r.getId());
                    } else {
                        try {
                            cancelRoster(r, "截止日 " + r.getClaimDeadline() + " 認領 " + pct + "%，未達門檻 " + r.getMinClaimPercent() + "%");
                            cancelled.add(r.getId());
                        } catch (ApiException e) {
                            log.warn("[共享班表] {} 未達門檻但無法自動取消：{}", r.getId(), e.getMessage());
                        }
                    }
                } else if (SharedRoster.CONFIRMED.equals(r.getStatus()) && today.isAfter(r.getEndDate())) {
                    r.setStatus(SharedRoster.COMPLETED);
                    rosterRepository.save(r);
                    completed.add(r.getId());
                }
            }
        }
        if (!confirmed.isEmpty() || !cancelled.isEmpty() || !completed.isEmpty()) {
            log.info("[共享班表] 截止日判斷：開班 {}、取消 {}、結束 {}", confirmed, cancelled, completed);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("confirmed", confirmed);
        m.put("cancelled", cancelled);
        m.put("completed", completed);
        return m;
    }

    // =========================================================================
    // 檢視
    // =========================================================================

    private List<Order> activeClaims(SharedRoster r) {
        return orderRepository.findBySharedRosterId(r.getId()).stream()
                .filter(o -> ACTIVE_ORDER.contains(o.getStatus()) || OrderService.COMPLETED.equals(o.getStatus()))
                .toList();
    }

    /** 已認領格數（整天佔兩格） */
    private static int claimedSlots(List<Order> claims) {
        return claims.stream().mapToInt(o -> o.isHalfDay() ? 1 : 2).sum();
    }

    private int claimPercent(SharedRoster r) {
        return (int) Math.floor(claimedSlots(activeClaims(r)) * 100.0 / (r.days() * 2));
    }

    private Map<String, Object> view(SharedRoster r, AuthContext ctx) {
        boolean admin = ctx.isAdmin();
        LocalDate today = LocalDate.now();
        List<Order> claims = activeClaims(r);
        int total = r.days() * 2;
        int claimed = claimedSlots(claims);
        int pct = (int) Math.floor(claimed * 100.0 / total);
        boolean claimable = r.isActive() && !(SharedRoster.OPEN.equals(r.getStatus()) && today.isAfter(r.getClaimDeadline()));

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("title", r.getTitle());
        m.put("region", r.getRegion());
        m.put("description", r.getDescription());
        m.put("status", r.getStatus());
        m.put("statusLabel", STATUS_LABELS.get(r.getStatus()));
        m.put("cancelReason", r.getCancelReason());
        m.put("dispatchCategory", r.getDispatchCategory());
        m.put("categoryName", orderService.categoryNameOf(r.getDispatchCategory()));
        m.put("startDate", r.getStartDate().toString());
        m.put("endDate", r.getEndDate().toString());
        m.put("claimDeadline", r.getClaimDeadline().toString());
        m.put("minClaimPercent", r.getMinClaimPercent());
        m.put("totalSlots", total);
        m.put("claimedSlots", claimed);
        m.put("claimPercent", pct);
        m.put("thresholdMet", pct >= r.getMinClaimPercent());
        m.put("claimable", claimable);

        DispatchTeam team = teamRepository.findById(r.getTeamId()).orElse(null);
        m.put("teamId", r.getTeamId());
        m.put("teamName", team == null ? null : team.getName());
        List<String> crew = r.crewList();
        m.put("crewSize", crew.size());
        List<Worker> workers = workerRepository.findAllById(crew);
        workers.stream().filter(Worker::isLeader).findFirst().ifPresent(w -> m.put("leaderName", MatchingService.displayName(w)));
        if (admin) {
            Map<String, Worker> byId = new HashMap<>();
            for (Worker w : workers) byId.put(w.getId(), w);
            List<Map<String, Object>> crewView = new ArrayList<>();
            for (String id : crew) {
                Worker w = byId.get(id);
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("workerId", id);
                c.put("name", w == null ? id : MatchingService.displayName(w));
                c.put("leader", w != null && w.isLeader());
                crewView.add(c);
            }
            m.put("crew", crewView);
        }
        // 每格報價（拼團不打折）：早鳥與彈性計時
        Map<String, Object> prices = new LinkedHashMap<>();
        for (String slot : List.of(Order.MORNING, Order.FULL_DAY)) {
            Map<String, Object> p = new LinkedHashMap<>();
            for (String plan : List.of("early_bird", "hourly_flex")) {
                Map<String, Object> q = orderService.quoteRosterSlot(crew.size(), slot, plan);
                p.put(plan, Map.of("total", q.get("actualTotal"), "deposit", q.get("deposit"), "hours", q.get("totalHours")));
            }
            prices.put(Order.MORNING.equals(slot) ? "halfDay" : "fullDay", p);
        }
        m.put("prices", prices);

        // 時段格：free 可認領／taken 已被認領／mine 本人認領／closed 已過或不開放
        Map<LocalDate, List<Order>> byDay = new HashMap<>();
        for (Order o : claims) byDay.computeIfAbsent(o.getStartDate(), k -> new ArrayList<>()).add(o);
        List<Map<String, Object>> days = new ArrayList<>();
        for (LocalDate d = r.getStartDate(); !d.isAfter(r.getEndDate()); d = d.plusDays(1)) {
            Map<String, Object> day = new LinkedHashMap<>();
            day.put("date", d.toString());
            for (String slot : List.of(Order.MORNING, Order.AFTERNOON)) {
                LocalDate dd = d;
                Order taken = byDay.getOrDefault(d, List.of()).stream()
                        .filter(o -> Order.slotsOverlap(slot, o.slot())).findFirst().orElse(null);
                Map<String, Object> cell = new LinkedHashMap<>();
                boolean mine = taken != null && ctx.isFarmer() && Objects.equals(taken.getFarmerId(), ctx.ref());
                cell.put("state", taken != null ? (mine ? "mine" : "taken") : (claimable && dd.isAfter(today) ? "free" : "closed"));
                if (taken != null && (admin || mine)) {
                    cell.put("orderId", taken.getId());
                    cell.put("orderStatus", taken.getStatus());
                    cell.put("fullDay", !taken.isHalfDay());
                }
                if (taken != null && admin) {
                    cell.put("farmerName", taken.getFarmerName());
                    cell.put("farmLocation", taken.getFarmLocation());
                }
                day.put(slot, cell);
            }
            days.add(day);
        }
        m.put("days", days);
        return m;
    }

    // =========================================================================
    // 工具
    // =========================================================================

    private SharedRoster require(String id) {
        return rosterRepository.findById(id == null ? "" : id).orElseThrow(() -> ApiException.notFound("找不到共享班表：" + id));
    }

    private static void requireFarmerOrAdmin(AuthContext ctx) {
        if (!ctx.isAdmin() && !ctx.isFarmer()) throw ApiException.forbidden();
    }

    private static String str(Object o) {
        return o == null || o.toString().isBlank() ? null : o.toString().trim();
    }

    private static String text(Object o, String name, int max, boolean required) {
        String s = str(o);
        if (s == null) {
            if (required) throw ApiException.badRequest(name + " 為必填欄位");
            return null;
        }
        if (s.length() > max) throw ApiException.badRequest(name + " 過長（上限 " + max + " 字）");
        return s;
    }

    private static LocalDate date(Object o, String name, boolean required) {
        String s = str(o);
        if (s == null) {
            if (required) throw ApiException.badRequest(name + " 為必填欄位（YYYY-MM-DD）");
            return null;
        }
        try {
            return LocalDate.parse(s);
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest(name + " 須為 YYYY-MM-DD 日期");
        }
    }

    private static int intOf(Object o, String name) {
        try {
            return (int) Math.round(Double.parseDouble(o.toString()));
        } catch (NumberFormatException e) {
            throw ApiException.badRequest(name + " 必須為數字");
        }
    }

    private static double doubleOf(Object o) {
        try {
            return Double.parseDouble(o.toString());
        } catch (NumberFormatException e) {
            throw ApiException.badRequest("面積 (area) 必須為數字");
        }
    }
}
