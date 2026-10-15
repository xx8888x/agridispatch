package com.agridispatch.service;

import com.agridispatch.common.ApiException;
import com.agridispatch.common.IdGenerator;
import com.agridispatch.entity.Order;
import com.agridispatch.entity.SharedRoster;
import com.agridispatch.entity.Worker;
import com.agridispatch.entity.WorkerLeave;
import com.agridispatch.repository.OrderRepository;
import com.agridispatch.repository.SharedRosterRepository;
import com.agridispatch.repository.WorkerLeaveRepository;
import com.agridispatch.security.AuthContext;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * 工人請假：工人本人（或合作社代為）申請 → 合作社（coop_admin，科技後台 admin_tech 亦可）核准或駁回；
 * 待審核的假可由本人或合作社取消，已核准的假在開始前本人可取消，合作社隨時可取消。
 *
 * 已核准的假在派工媒合視同撞期（MatchingService.busyWorkers），該期間不列入出工名單、指名與共享班表。
 * 申請與核准時回傳 conflicts：本人已排入、工期與請假重疊的訂單或共享班表，提醒合作社改派（系統不自動移出名單）。
 *
 * 假別與給薪、天數上限依勞動基準法與勞工請假規則【待確認】，系統只記錄假別，不檢查額度、不影響薪資試算。
 *
 * 通知（背景送出，失敗不影響審核）：工人本人申請 → App 推播給合作社與科技後台（後台人員不使用 LINE）；
 * 核准、駁回、合作社取消已核准的假 → LINE 工作者帳號（三語，時段外略過）與 App 推播給工人本人。
 */
@Service
public class LeaveService {
    /** 單次請假最長天數（含頭尾），更長的假（例如產假）請分次申請或由合作社處理 */
    public static final int MAX_DAYS = 31;
    /** 補請：開始日最早可到幾天前（例如病假事後補請） */
    public static final int BACKDATE_DAYS = 30;

    /** 假別代碼 → 名稱；順序即前端選單順序 */
    public static final Map<String, String> TYPE_LABELS;
    static {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("annual", "特別休假");
        m.put("personal", "事假");
        m.put("sick", "普通傷病假");
        m.put("menstrual", "生理假");
        m.put("family_care", "家庭照顧假");
        m.put("marriage", "婚假");
        m.put("bereavement", "喪假");
        m.put("official", "公假");
        m.put("injury", "公傷病假");
        m.put("maternity", "產假／產檢假");
        m.put("paternity", "陪產檢及陪產假");
        m.put("other", "其他");
        TYPE_LABELS = Collections.unmodifiableMap(m);
    }

    public static final Map<String, String> STATUS_LABELS = Map.of(
            WorkerLeave.PENDING, "待審核", WorkerLeave.APPROVED, "已核准",
            WorkerLeave.REJECTED, "已駁回", WorkerLeave.CANCELLED, "已取消");

    private static final Set<String> ACTIVE_ORDER = Set.of(OrderService.PENDING, OrderService.CONFIRMED, OrderService.IN_PROGRESS);
    private static final List<String> HOLDING = List.of(WorkerLeave.PENDING, WorkerLeave.APPROVED);

    private final WorkerLeaveRepository leaveRepository;
    private final WorkerService workerService;
    private final OrderRepository orderRepository;
    private final SharedRosterRepository rosterRepository;
    private final LineNotifyService lineNotifyService;
    private final FcmPushService pushService;
    private final Object lock = new Object();

    public LeaveService(WorkerLeaveRepository leaveRepository, WorkerService workerService,
                        OrderRepository orderRepository, SharedRosterRepository rosterRepository,
                        LineNotifyService lineNotifyService, FcmPushService pushService) {
        this.leaveRepository = leaveRepository;
        this.workerService = workerService;
        this.orderRepository = orderRepository;
        this.rosterRepository = rosterRepository;
        this.lineNotifyService = lineNotifyService;
        this.pushService = pushService;
    }

    public List<Map<String, Object>> types() {
        List<Map<String, Object>> out = new ArrayList<>();
        TYPE_LABELS.forEach((k, v) -> out.add(Map.of("key", k, "label", v)));
        return out;
    }

    /** 本人或 admin：某位工人的請假紀錄（新到舊） */
    public List<Map<String, Object>> listForWorker(AuthContext ctx, String workerId) {
        ctx.requireSelfOrAdmin(workerId);
        Worker w = workerService.requireWorker(workerId);
        List<Map<String, Object>> out = new ArrayList<>();
        for (WorkerLeave l : leaveRepository.findByWorkerIdOrderByStartDateDesc(workerId)) out.add(view(l, w, true));
        return out;
    }

    /** admin：全部請假，可依狀態篩選；待審核排最前，其餘依開始日新到舊 */
    public List<Map<String, Object>> listAll(AuthContext ctx, String status) {
        ctx.requireStaff();
        List<WorkerLeave> leaves = status == null || status.isBlank()
                ? leaveRepository.findAll()
                : leaveRepository.findByStatusIn(List.of(status.trim()));
        Map<String, Worker> workers = new HashMap<>();
        List<Map<String, Object>> out = new ArrayList<>();
        leaves.stream()
                .sorted(Comparator.comparing((WorkerLeave l) -> !WorkerLeave.PENDING.equals(l.getStatus()))
                        .thenComparing(WorkerLeave::getStartDate, Comparator.reverseOrder())
                        .thenComparing(WorkerLeave::getId))
                .forEach(l -> {
                    Worker w = workers.computeIfAbsent(l.getWorkerId(), id -> workerService.getWorkerById(id).orElse(null));
                    out.add(view(l, w, true));
                });
        return out;
    }

    /** 申請請假：{leaveType, startDate, endDate?, timeSlot?, reason?}；本人或 admin 代為申請 */
    public Map<String, Object> create(AuthContext ctx, String workerId, Map<String, Object> req) {
        ctx.requireSelfOrAdmin(workerId);
        Worker w = workerService.requireWorker(workerId);
        String type = str(req.get("leaveType"));
        if (type == null || !TYPE_LABELS.containsKey(type)) throw ApiException.badRequest("請選擇假別");
        LocalDate start = date(req.get("startDate"), "開始日 (startDate)");
        if (start == null) throw ApiException.badRequest("請填寫開始日 (startDate)");
        LocalDate end = date(req.get("endDate"), "結束日 (endDate)");
        if (end == null) end = start;
        if (end.isBefore(start)) throw ApiException.badRequest("結束日不可早於開始日");
        if (ChronoUnit.DAYS.between(start, end) + 1 > MAX_DAYS) {
            throw ApiException.badRequest("單次請假最多 " + MAX_DAYS + " 天，更長的假請分次申請或聯絡合作社");
        }
        if (start.isBefore(LocalDate.now().minusDays(BACKDATE_DAYS))) {
            throw ApiException.badRequest("只能補請 " + BACKDATE_DAYS + " 天內的假，更早的請聯絡合作社");
        }
        String slot = OrderService.normalizeSlot(str(req.get("timeSlot")));
        if (!Order.FULL_DAY.equals(slot) && !start.equals(end)) throw ApiException.badRequest("半天假只能請單日");
        String reason = str(req.get("reason"));
        if (reason != null && reason.length() > 200) throw ApiException.badRequest("事由請在 200 字以內");

        WorkerLeave l = new WorkerLeave();
        l.setWorkerId(w.getId());
        l.setLeaveType(type);
        l.setStartDate(start);
        l.setEndDate(end);
        l.setTimeSlot(slot);
        l.setReason(reason);
        l.setStatus(WorkerLeave.PENDING);
        l.setRequestedBy(ctx.sub());
        synchronized (lock) {
            Order probe = l.asHold();
            for (WorkerLeave other : leaveRepository.findByWorkerIdAndStatusIn(w.getId(), HOLDING)) {
                if (other.asHold().overlaps(probe)) {
                    throw ApiException.conflict("與已申請的假 " + other.getId() + "（" + period(other) + "）重疊");
                }
            }
            String prefix = "LV-" + LocalDate.now().getYear() + "-";
            l.setId(IdGenerator.next(prefix, 3, leaveRepository.findIdsByPrefix(prefix), leaveRepository::existsById));
            leaveRepository.save(l);
        }
        if (!ctx.isStaff()) {
            notifySafely(() -> pushService.send(AuthContext.ROLE_COOP, "新的請假申請",
                    MatchingService.displayName(w) + "（" + w.getId() + "）" + TYPE_LABELS.get(type) + "・" + period(l) + "，請到「請假審核」處理", "dispatch"));
            notifySafely(() -> pushService.send(AuthContext.ROLE_ADMIN, "新的請假申請",
                    MatchingService.displayName(w) + "（" + w.getId() + "）" + TYPE_LABELS.get(type) + "・" + period(l) + "，請到「請假審核」處理", "dispatch"));
        }
        return view(l, w, true);
    }

    /** admin 核准：note 選填；回傳含 conflicts，提醒改派已排入的訂單 */
    public Map<String, Object> approve(AuthContext ctx, String id, String note) {
        ctx.requireStaff();
        synchronized (lock) {
            WorkerLeave l = requirePending(id);
            decide(l, WorkerLeave.APPROVED, ctx, note);
            notifyWorker(l);
            return view(l, workerService.getWorkerById(l.getWorkerId()).orElse(null), true);
        }
    }

    /** admin 駁回：須填原因 */
    public Map<String, Object> reject(AuthContext ctx, String id, String note) {
        ctx.requireStaff();
        if (note == null || note.isBlank()) throw ApiException.badRequest("請填寫駁回原因");
        synchronized (lock) {
            WorkerLeave l = requirePending(id);
            decide(l, WorkerLeave.REJECTED, ctx, note);
            notifyWorker(l);
            return view(l, workerService.getWorkerById(l.getWorkerId()).orElse(null), false);
        }
    }

    /** 取消：待審核本人或 admin 皆可；已核准本人只能在開始日前取消，admin 隨時可取消 */
    public Map<String, Object> cancel(AuthContext ctx, String id, String note) {
        synchronized (lock) {
            WorkerLeave l = leaveRepository.findById(id).orElseThrow(() -> ApiException.notFound("查無請假紀錄：" + id));
            ctx.requireSelfOrAdmin(l.getWorkerId());
            if (WorkerLeave.APPROVED.equals(l.getStatus())) {
                if (!ctx.isStaff() && !LocalDate.now().isBefore(l.getStartDate())) {
                    throw ApiException.conflict("假期已開始，要取消請聯絡合作社");
                }
            } else if (!WorkerLeave.PENDING.equals(l.getStatus())) {
                throw ApiException.conflict("這筆假「" + STATUS_LABELS.get(l.getStatus()) + "」，不能取消");
            }
            boolean wasApproved = WorkerLeave.APPROVED.equals(l.getStatus());
            decide(l, WorkerLeave.CANCELLED, ctx, note);
            // 合作社取消已核准的假才通知（本人取消自己的假不必通知）
            if (wasApproved && ctx.isStaff()) notifyWorker(l);
            return view(l, workerService.getWorkerById(l.getWorkerId()).orElse(null), false);
        }
    }

    // =========================================================================

    private WorkerLeave requirePending(String id) {
        WorkerLeave l = leaveRepository.findById(id).orElseThrow(() -> ApiException.notFound("查無請假紀錄：" + id));
        if (!WorkerLeave.PENDING.equals(l.getStatus())) {
            throw ApiException.conflict("這筆假已「" + STATUS_LABELS.get(l.getStatus()) + "」，不能再審核");
        }
        return l;
    }

    /** 審核結果通知工人本人：LINE 工作者帳號（訊息由 LINE Bot 依語言產生）＋ App 推播 */
    private void notifyWorker(WorkerLeave l) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("leaveId", l.getId());
        p.put("status", l.getStatus());
        p.put("leaveType", TYPE_LABELS.getOrDefault(l.getLeaveType(), l.getLeaveType()));   // 中文假別（LINE Bot 沒有該語言翻譯時用）
        p.put("leaveTypeCode", l.getLeaveType());                                          // LINE Bot 依代碼翻成工人的語言
        p.put("period", period(l));
        p.put("note", l.getDecisionNote() == null ? "" : l.getDecisionNote());
        notifySafely(() -> lineNotifyService.leaveDecided(l.getWorkerId(), p));
        String title = "請假" + STATUS_LABELS.get(l.getStatus());
        String body = TYPE_LABELS.getOrDefault(l.getLeaveType(), l.getLeaveType()) + "・" + period(l)
                + (l.getDecisionNote() == null ? "" : "（" + l.getDecisionNote() + "）");
        notifySafely(() -> pushService.send(l.getWorkerId(), title, body, "dispatch"));
    }

    /** 通知失敗只記錄，不影響請假本身的操作 */
    private static void notifySafely(Runnable r) {
        try {
            r.run();
        } catch (RuntimeException e) {
            org.slf4j.LoggerFactory.getLogger(LeaveService.class).warn("[請假] 通知失敗：{}", e.toString());
        }
    }

    private void decide(WorkerLeave l, String status, AuthContext ctx, String note) {
        if (note != null && note.length() > 200) throw ApiException.badRequest("備註請在 200 字以內");
        l.setStatus(status);
        l.setDecidedBy(ctx.sub());
        l.setDecidedAt(LocalDateTime.now());
        l.setDecisionNote(note == null || note.isBlank() ? null : note.trim());
        leaveRepository.save(l);
    }

    /** 本人已排入、與請假期間重疊的訂單（出工名單有本人，或尚無名單但被指名）與共享班表 */
    List<Map<String, Object>> conflicts(WorkerLeave l) {
        Order hold = l.asHold();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Order o : orderRepository.findAll()) {
            if (!ACTIVE_ORDER.contains(o.getStatus()) || !o.overlaps(hold)) continue;
            List<String> crew = o.crewList();
            boolean mine = crew.contains(l.getWorkerId()) || (crew.isEmpty() && l.getWorkerId().equals(o.getPreferredWorkerId()));
            if (!mine) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("kind", "order");
            m.put("id", o.getId());
            m.put("startDate", o.getStartDate().toString());
            m.put("endDate", o.getEndDate().toString());
            m.put("label", (o.getCropName() == null ? "" : o.getCropName() + "・") + (o.getFarmLocation() == null ? "" : o.getFarmLocation()));
            m.put("via", crew.isEmpty() ? "preferred" : "crew");
            out.add(m);
        }
        for (SharedRoster r : rosterRepository.findByStatusIn(List.of(SharedRoster.OPEN, SharedRoster.CONFIRMED))) {
            if (!r.crewList().contains(l.getWorkerId())) continue;
            Order rh = new Order();
            rh.setStartDate(r.getStartDate());
            rh.setEndDate(r.getEndDate());
            rh.setTimeSlot(Order.FULL_DAY);
            if (!rh.overlaps(hold)) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("kind", "roster");
            m.put("id", r.getId());
            m.put("startDate", r.getStartDate().toString());
            m.put("endDate", r.getEndDate().toString());
            m.put("label", r.getTitle());
            m.put("via", "crew");
            out.add(m);
        }
        out.sort(Comparator.comparing(m -> m.get("startDate").toString()));
        return out;
    }

    private Map<String, Object> view(WorkerLeave l, Worker w, boolean withConflicts) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", l.getId());
        m.put("workerId", l.getWorkerId());
        m.put("workerName", w == null ? null : MatchingService.displayName(w));
        m.put("teamId", w == null ? null : w.getTeamId());
        m.put("leaveType", l.getLeaveType());
        m.put("leaveTypeLabel", TYPE_LABELS.getOrDefault(l.getLeaveType(), l.getLeaveType()));
        m.put("startDate", l.getStartDate().toString());
        m.put("endDate", l.getEndDate().toString());
        m.put("timeSlot", l.getTimeSlot());
        m.put("timeSlotLabel", Order.FULL_DAY.equals(l.getTimeSlot()) ? "整天" : Order.MORNING.equals(l.getTimeSlot()) ? "上午" : "下午");
        m.put("days", Order.FULL_DAY.equals(l.getTimeSlot()) ? ChronoUnit.DAYS.between(l.getStartDate(), l.getEndDate()) + 1 : 0.5);
        m.put("reason", l.getReason());
        m.put("status", l.getStatus());
        m.put("statusLabel", STATUS_LABELS.getOrDefault(l.getStatus(), l.getStatus()));
        m.put("requestedBy", l.getRequestedBy());
        m.put("createdAt", l.getCreatedAt() == null ? null : l.getCreatedAt().toString());
        m.put("decidedAt", l.getDecidedAt() == null ? null : l.getDecidedAt().toString());
        m.put("decisionNote", l.getDecisionNote());
        // 已核准、尚未開始的假，本人還能取消
        m.put("cancellable", WorkerLeave.PENDING.equals(l.getStatus())
                || (WorkerLeave.APPROVED.equals(l.getStatus()) && LocalDate.now().isBefore(l.getStartDate())));
        boolean holding = HOLDING.contains(l.getStatus());
        m.put("conflicts", withConflicts && holding ? conflicts(l) : List.of());
        return m;
    }

    private static String period(WorkerLeave l) {
        String p = l.getStartDate().equals(l.getEndDate()) ? l.getStartDate().toString() : l.getStartDate() + "～" + l.getEndDate();
        return Order.FULL_DAY.equals(l.getTimeSlot()) ? p : p + (Order.MORNING.equals(l.getTimeSlot()) ? " 上午" : " 下午");
    }

    private static String str(Object o) {
        if (o == null) return null;
        String s = o.toString().trim();
        return s.isEmpty() ? null : s;
    }

    private static LocalDate date(Object o, String field) {
        String s = str(o);
        if (s == null) return null;
        try {
            return LocalDate.parse(s);
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest(field + " 格式須為 yyyy-MM-dd");
        }
    }
}
