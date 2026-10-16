package com.agridispatch.service;

import com.agridispatch.common.ApiException;
import com.agridispatch.entity.Attendance;
import com.agridispatch.entity.Order;
import com.agridispatch.entity.Worker;
import com.agridispatch.repository.AttendanceRepository;
import com.agridispatch.repository.DispatchTeamRepository;
import com.agridispatch.repository.WorkerRepository;
import com.agridispatch.security.AuthContext;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 領班逐人評核：每一班（下班簽核後）由該工人所屬工班的領班評「優／可／需加強」
 * - 評核者：admin，或與受評者同工班且為領班（isLeader）的工人；不可評自己
 * - 領班只能評 7 天內的班次（admin 不限），可修改
 *
 * 農場主逐人評核：訂單農場主（或 admin）對該訂單出工的工人評同樣三級，套用到該工人此訂單所有已下班班次；
 * 農場主只能改最後出工日 30 天內的評核。技能標籤採計時領班評核優先，該班沒有領班評核才採農場主評核。
 */
@Service
public class EvaluationService {

    public static final String EXCELLENT = "excellent";
    public static final String OK = "ok";
    public static final String NEEDS_IMPROVEMENT = "needs_improvement";
    public static final Map<String, String> LABELS = Map.of(EXCELLENT, "優", OK, "可", NEEDS_IMPROVEMENT, "需加強");
    static final int LEADER_EDIT_DAYS = 7;
    static final int FARMER_EDIT_DAYS = 30;
    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");

    private final AttendanceRepository attendanceRepository;
    private final WorkerRepository workerRepository;
    private final DispatchTeamRepository teamRepository;
    private final OrderService orderService;

    public EvaluationService(AttendanceRepository attendanceRepository, WorkerRepository workerRepository,
                             DispatchTeamRepository teamRepository, OrderService orderService) {
        this.attendanceRepository = attendanceRepository;
        this.workerRepository = workerRepository;
        this.teamRepository = teamRepository;
        this.orderService = orderService;
    }

    /** 是否為該工班的領班（admin 視同可評核所有工班） */
    public boolean canEvaluateTeam(AuthContext ctx, String teamId) {
        if (ctx.isStaff()) return true;
        if (!ctx.isWorker()) return false;
        return workerRepository.findById(ctx.ref())
                .filter(w -> w.isLeader() && teamId != null && teamId.equals(w.getTeamId()))
                .isPresent();
    }

    /** 工班某日的出勤與評核狀態 */
    public Map<String, Object> teamDay(AuthContext ctx, String teamId, LocalDate date) {
        if (!teamRepository.existsById(teamId)) throw ApiException.notFound("找不到工班：" + teamId);
        if (!canEvaluateTeam(ctx, teamId)) throw ApiException.forbidden();
        List<Worker> members = workerRepository.findByTeamId(teamId);
        Map<String, Worker> byId = new HashMap<>();
        for (Worker w : members) byId.put(w.getId(), w);
        List<Map<String, Object>> shifts = new ArrayList<>();
        if (!members.isEmpty()) {
            for (Attendance a : attendanceRepository.findByWorkerIdInAndWorkDateOrderByWorkerIdAsc(byId.keySet(), date)) {
                Worker w = byId.get(a.getWorkerId());
                Map<String, Object> m = toView(a);
                m.put("name", w.getChineseName() != null && !w.getChineseName().isBlank() ? w.getChineseName() : w.getName());
                m.put("self", a.getWorkerId().equalsIgnoreCase(ctx.ref()));
                shifts.add(m);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("teamId", teamId);
        out.put("date", date.toString());
        out.put("shifts", shifts);
        return out;
    }

    public Attendance evaluate(AuthContext ctx, Long attendanceId, Object ratingRaw) {
        String rating = ratingRaw == null ? "" : String.valueOf(ratingRaw).trim();
        if (!LABELS.containsKey(rating)) throw ApiException.badRequest("rating 必須為 excellent / ok / needs_improvement");
        Attendance a = attendanceRepository.findById(attendanceId)
                .orElseThrow(() -> ApiException.notFound("找不到出勤紀錄：" + attendanceId));
        Worker w = workerRepository.findById(a.getWorkerId())
                .orElseThrow(() -> ApiException.notFound("找不到工人：" + a.getWorkerId()));
        if (!canEvaluateTeam(ctx, w.getTeamId())) throw ApiException.forbidden();
        if (!ctx.isStaff() && w.getId().equalsIgnoreCase(ctx.ref())) {
            throw ApiException.forbidden();
        }
        if (a.getClockOutTime() == null) throw ApiException.conflict("尚未下班簽核，無法評核");
        if (!ctx.isStaff() && a.getWorkDate().isBefore(LocalDate.now().minusDays(LEADER_EDIT_DAYS))) {
            throw ApiException.conflict("超過 " + LEADER_EDIT_DAYS + " 天的班次須由合作社管理員評核");
        }
        a.setForemanRating(rating);
        a.setForemanRatedBy(ctx.ref());
        a.setForemanRatedAt(LocalDateTime.now());
        return attendanceRepository.save(a);
    }

    // =========================================================================
    // 農場主逐人評核（依訂單，一位工人一筆，套用到該工人在此訂單所有已下班的班次）
    // =========================================================================

    /** 訂單出工名單與農場主評核狀態（訂單農場主或 admin） */
    public Map<String, Object> orderWorkers(AuthContext ctx, String orderId) {
        Order order = orderService.requireOrder(orderId);
        orderService.checkOwnerOrAdmin(ctx, order);
        Map<String, List<Attendance>> byWorker = new LinkedHashMap<>();
        for (Attendance a : attendanceRepository.findByOrderIdOrderByWorkDateAscClockInTimeAsc(orderId)) {
            byWorker.computeIfAbsent(a.getWorkerId(), k -> new ArrayList<>()).add(a);
        }
        List<Map<String, Object>> workers = new ArrayList<>();
        for (Map.Entry<String, List<Attendance>> e : byWorker.entrySet()) {
            Worker w = workerRepository.findById(e.getKey()).orElse(null);
            List<Attendance> done = e.getValue().stream().filter(a -> a.getClockOutTime() != null).toList();
            double hours = done.stream().mapToDouble(a -> a.getActualHours() == null ? 0 : a.getActualHours()).sum();
            Attendance rated = done.stream().filter(a -> a.getFarmerRating() != null)
                    .max(Comparator.comparing(Attendance::getFarmerRatedAt, Comparator.nullsFirst(Comparator.naturalOrder())))
                    .orElse(null);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("workerId", e.getKey());
            m.put("name", w == null ? e.getKey()
                    : (w.getChineseName() != null && !w.getChineseName().isBlank() ? w.getChineseName() : w.getName()));
            m.put("shifts", done.size());
            m.put("hours", Math.round(hours * 10) / 10.0);
            m.put("ratable", !done.isEmpty());
            m.put("rating", rated == null ? null : rated.getFarmerRating());
            m.put("ratingLabel", rated == null ? null : LABELS.get(rated.getFarmerRating()));
            workers.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("orderId", orderId);
        out.put("workers", workers);
        return out;
    }

    /** 農場主（或 admin）評某位工人在某訂單的表現 */
    public Map<String, Object> rateWorkerOnOrder(AuthContext ctx, String orderId, String workerId, Object ratingRaw) {
        String rating = ratingRaw == null ? "" : String.valueOf(ratingRaw).trim();
        if (!LABELS.containsKey(rating)) throw ApiException.badRequest("rating 必須為 excellent / ok / needs_improvement");
        Order order = orderService.requireOrder(orderId);
        orderService.checkOwnerOrAdmin(ctx, order);
        List<Attendance> done = attendanceRepository.findByOrderIdOrderByWorkDateAscClockInTimeAsc(orderId).stream()
                .filter(a -> a.getWorkerId().equalsIgnoreCase(workerId) && a.getClockOutTime() != null).toList();
        if (done.isEmpty()) throw ApiException.conflict("這位工人在此訂單沒有已下班簽核的出勤，無法評核");
        LocalDate last = done.stream().map(Attendance::getWorkDate).max(Comparator.naturalOrder()).orElseThrow();
        if (!ctx.isStaff() && last.isBefore(LocalDate.now().minusDays(FARMER_EDIT_DAYS))) {
            throw ApiException.conflict("超過 " + FARMER_EDIT_DAYS + " 天的評核須由合作社管理員處理");
        }
        LocalDateTime now = LocalDateTime.now();
        for (Attendance a : done) {
            a.setFarmerRating(rating);
            a.setFarmerRatedBy(ctx.ref());
            a.setFarmerRatedAt(now);
        }
        attendanceRepository.saveAll(done);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("orderId", orderId);
        out.put("workerId", done.get(0).getWorkerId());
        out.put("shifts", done.size());
        out.put("rating", rating);
        out.put("ratingLabel", LABELS.get(rating));
        return out;
    }

    public Map<String, Object> toView(Attendance a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("attendanceId", a.getId());
        m.put("workerId", a.getWorkerId());
        m.put("workDate", a.getWorkDate().toString());
        m.put("orderId", a.getOrderId());
        m.put("clockIn", a.getClockInTime().format(HM));
        m.put("clockOut", a.getClockOutTime() == null ? null : a.getClockOutTime().format(HM));
        m.put("hours", a.getClockOutTime() == null ? null : a.getActualHours());
        m.put("rating", a.getForemanRating());
        m.put("ratingLabel", a.getForemanRating() == null ? null : LABELS.get(a.getForemanRating()));
        return m;
    }
}
