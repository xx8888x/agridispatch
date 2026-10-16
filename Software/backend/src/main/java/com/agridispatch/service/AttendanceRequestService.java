package com.agridispatch.service;

import com.agridispatch.common.ApiException;
import com.agridispatch.common.IdGenerator;
import com.agridispatch.entity.Attendance;
import com.agridispatch.entity.AttendanceRequest;
import com.agridispatch.entity.Worker;
import com.agridispatch.repository.AttendanceRepository;
import com.agridispatch.repository.AttendanceRequestRepository;
import com.agridispatch.security.AuthContext;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 工人出勤補登／修正申請：工人本人（或合作社代為）申請 → 合作社（coop_admin，科技後台 admin_tech 亦可）核准或駁回。
 *
 * - 申請不改出勤；核准時才呼叫 PayrollService 補登（kind = create）或修正（kind = update），
 *   留 attendance_corrections 紀錄（原因註明申請編號），核准時合作社可調整上下班時間與備註。
 * - 申請當下與核准當下都做同一套檢查（時間格式、不晚於現在、不與其他出勤重疊、該月未結算）。
 * - 只能補登／修正 30 天內的出勤；同一天（補登）或同一筆出勤（修正）同時只能有一筆待審申請。
 * - 該月還有待審申請時不能月結算（PayrollService.settle）。
 * - 待審核可由本人或合作社取消；核准、駁回後定案。
 *
 * 通知（背景送出，失敗不影響審核）：工人本人申請 → App 推播給合作社；核准、駁回 → App 推播給工人本人。
 * 補登與修正的薪資影響、重複申請濫用的處理規則【待確認】由合作社訂定。
 */
@Service
public class AttendanceRequestService {
    /** 可補登／修正的出勤日期最早是幾天前 */
    public static final int BACKDATE_DAYS = 30;

    public static final Map<String, String> STATUS_LABELS = Map.of(
            AttendanceRequest.PENDING, "待審核", AttendanceRequest.APPROVED, "已核准",
            AttendanceRequest.REJECTED, "已駁回", AttendanceRequest.CANCELLED, "已取消");

    private final AttendanceRequestRepository requestRepository;
    private final AttendanceRepository attendanceRepository;
    private final PayrollService payrollService;
    private final WorkerService workerService;
    private final FcmPushService pushService;
    private final Object lock = new Object();

    public AttendanceRequestService(AttendanceRequestRepository requestRepository, AttendanceRepository attendanceRepository,
                                    PayrollService payrollService, WorkerService workerService, FcmPushService pushService) {
        this.requestRepository = requestRepository;
        this.attendanceRepository = attendanceRepository;
        this.payrollService = payrollService;
        this.workerService = workerService;
        this.pushService = pushService;
    }

    /** 本人或 admin：某位工人的申請紀錄（新到舊） */
    public List<Map<String, Object>> listForWorker(AuthContext ctx, String workerId) {
        ctx.requireSelfOrAdmin(workerId);
        Worker w = workerService.requireWorker(workerId);
        List<Map<String, Object>> out = new ArrayList<>();
        for (AttendanceRequest r : requestRepository.findByWorkerIdOrderByCreatedAtDesc(workerId)) out.add(view(r, w));
        return out;
    }

    /** admin：全部申請，可依狀態篩選；待審核排最前（舊到新），其餘依申請時間新到舊 */
    public List<Map<String, Object>> listAll(AuthContext ctx, String status) {
        ctx.requireStaff();
        List<AttendanceRequest> list = status == null || status.isBlank()
                ? requestRepository.findAll()
                : requestRepository.findByStatusIn(List.of(status.trim()));
        Map<String, Worker> workers = new HashMap<>();
        List<Map<String, Object>> out = new ArrayList<>();
        list.stream()
                .sorted(Comparator.comparing((AttendanceRequest r) -> !AttendanceRequest.PENDING.equals(r.getStatus()))
                        .thenComparing((a, b) -> AttendanceRequest.PENDING.equals(a.getStatus())
                                ? a.getCreatedAt().compareTo(b.getCreatedAt())
                                : b.getCreatedAt().compareTo(a.getCreatedAt()))
                        .thenComparing(AttendanceRequest::getId))
                .forEach(r -> out.add(view(r, workers.computeIfAbsent(r.getWorkerId(),
                        id -> workerService.getWorkerById(id).orElse(null)))));
        return out;
    }

    /**
     * 申請：{attendanceId?, workDate, clockIn, clockOut, orderId?, reason}
     * 給 attendanceId 是修正該筆出勤（workDate 沿用原出勤日，clockIn／clockOut 未給的沿用原值）；否則是補登一筆新出勤。
     */
    public Map<String, Object> create(AuthContext ctx, String workerId, Map<String, Object> req) {
        ctx.requireSelfOrAdmin(workerId);
        Worker w = workerService.requireWorker(workerId);
        Long attendanceId = longOf(req.get("attendanceId"));
        String reason = PayrollService.requireReason(req.get("reason"));
        Map<String, Object> shift = payrollService.checkRequest(w.getId(), attendanceId, req.get("workDate"),
                req.get("clockIn"), req.get("clockOut"));
        LocalDate date = (LocalDate) shift.get("date");
        if (date.isBefore(LocalDate.now().minusDays(BACKDATE_DAYS))) {
            throw ApiException.badRequest("只能申請補登或修正 " + BACKDATE_DAYS + " 天內的出勤，更早的請聯絡合作社");
        }
        String orderId = str(req.get("orderId"));
        if (orderId != null && orderId.length() > 32) throw ApiException.badRequest("訂單編號過長");

        AttendanceRequest r = new AttendanceRequest();
        r.setWorkerId(w.getId());
        r.setKind(attendanceId == null ? AttendanceRequest.CREATE : AttendanceRequest.UPDATE);
        r.setAttendanceId(attendanceId);
        r.setWorkDate(date);
        r.setClockIn((String) shift.get("clockIn"));
        r.setClockOut((String) shift.get("clockOut"));
        r.setOrderId(attendanceId == null ? orderId : null);
        r.setReason(reason);
        r.setStatus(AttendanceRequest.PENDING);
        r.setRequestedBy(ctx.sub());
        synchronized (lock) {
            for (AttendanceRequest o : requestRepository.findByWorkerIdAndStatus(w.getId(), AttendanceRequest.PENDING)) {
                boolean same = attendanceId != null ? attendanceId.equals(o.getAttendanceId())
                        : o.getAttendanceId() == null && date.equals(o.getWorkDate());
                if (same) throw ApiException.conflict("已有一筆待審核的申請（" + o.getId() + "），請等合作社處理或先取消");
            }
            String prefix = "AR-" + LocalDate.now().getYear() + "-";
            r.setId(IdGenerator.next(prefix, 3, requestRepository.findIdsByPrefix(prefix), requestRepository::existsById));
            requestRepository.save(r);
        }
        if (!ctx.isStaff()) {
            notifySafely(() -> pushService.send(AuthContext.ROLE_COOP, "新的出勤補登申請",
                    MatchingService.displayName(w) + "（" + w.getId() + "）" + date + " "
                            + (attendanceId == null ? "補登" : "修正") + "，請到「出勤與月結算」審核", "dispatch"));
        }
        return view(r, w);
    }

    /** admin 核准：{note?, clockIn?, clockOut?}；時間可調整，未給的用申請值。核准即寫入出勤 */
    public Map<String, Object> approve(AuthContext ctx, String id, Map<String, Object> body) {
        ctx.requireStaff();
        String note = note(body);
        synchronized (lock) {
            AttendanceRequest r = requirePending(id);
            Object in = body != null && body.get("clockIn") != null && !body.get("clockIn").toString().isBlank() ? body.get("clockIn") : r.getClockIn();
            Object out = body != null && body.get("clockOut") != null && !body.get("clockOut").toString().isBlank() ? body.get("clockOut") : r.getClockOut();
            String why = "工人申請 " + r.getId() + "：" + r.getReason();
            if (why.length() > PayrollService.REASON_MAX) why = why.substring(0, PayrollService.REASON_MAX);
            Map<String, Object> rec;
            if (AttendanceRequest.UPDATE.equals(r.getKind())) {
                Map<String, Object> req = new HashMap<>();
                req.put("clockIn", in);
                req.put("clockOut", out);
                req.put("reason", why);
                rec = payrollService.updateAttendance(ctx, r.getAttendanceId(), req);
            } else {
                Map<String, Object> req = new HashMap<>();
                req.put("workerId", r.getWorkerId());
                req.put("workDate", r.getWorkDate().toString());
                req.put("clockIn", in);
                req.put("clockOut", out);
                req.put("orderId", r.getOrderId());
                req.put("reason", why);
                rec = payrollService.createAttendance(ctx, req);
            }
            r.setClockIn(in.toString());
            r.setClockOut(out.toString());
            r.setResultAttendanceId(((Number) rec.get("id")).longValue());
            decide(r, AttendanceRequest.APPROVED, ctx, note);
            notifyWorker(r);
            return view(r, workerService.getWorkerById(r.getWorkerId()).orElse(null));
        }
    }

    /** admin 駁回：須填原因 */
    public Map<String, Object> reject(AuthContext ctx, String id, Map<String, Object> body) {
        ctx.requireStaff();
        String note = note(body);
        if (note == null) throw ApiException.badRequest("請填寫駁回原因");
        synchronized (lock) {
            AttendanceRequest r = requirePending(id);
            decide(r, AttendanceRequest.REJECTED, ctx, note);
            notifyWorker(r);
            return view(r, workerService.getWorkerById(r.getWorkerId()).orElse(null));
        }
    }

    /** 取消：待審核的申請，本人或 admin 皆可 */
    public Map<String, Object> cancel(AuthContext ctx, String id) {
        synchronized (lock) {
            AttendanceRequest r = requestRepository.findById(id).orElseThrow(() -> ApiException.notFound("查無申請：" + id));
            ctx.requireSelfOrAdmin(r.getWorkerId());
            if (!AttendanceRequest.PENDING.equals(r.getStatus())) {
                throw ApiException.conflict("這筆申請「" + STATUS_LABELS.get(r.getStatus()) + "」，不能取消");
            }
            decide(r, AttendanceRequest.CANCELLED, ctx, null);
            return view(r, workerService.getWorkerById(r.getWorkerId()).orElse(null));
        }
    }

    // =========================================================================

    private AttendanceRequest requirePending(String id) {
        AttendanceRequest r = requestRepository.findById(id).orElseThrow(() -> ApiException.notFound("查無申請：" + id));
        if (!AttendanceRequest.PENDING.equals(r.getStatus())) {
            throw ApiException.conflict("這筆申請已「" + STATUS_LABELS.get(r.getStatus()) + "」，不能再審核");
        }
        return r;
    }

    private void decide(AttendanceRequest r, String status, AuthContext ctx, String note) {
        r.setStatus(status);
        r.setDecidedBy(ctx.sub());
        r.setDecidedAt(LocalDateTime.now());
        r.setDecisionNote(note);
        requestRepository.save(r);
    }

    private void notifyWorker(AttendanceRequest r) {
        String title = "出勤補登申請" + STATUS_LABELS.get(r.getStatus());
        String body = r.getWorkDate() + " " + r.getClockIn() + "～" + r.getClockOut()
                + (r.getDecisionNote() == null ? "" : "（" + r.getDecisionNote() + "）");
        notifySafely(() -> pushService.send(r.getWorkerId(), title, body, "dispatch"));
    }

    /** 通知失敗只記錄，不影響申請本身的操作 */
    private static void notifySafely(Runnable r) {
        try {
            r.run();
        } catch (RuntimeException e) {
            org.slf4j.LoggerFactory.getLogger(AttendanceRequestService.class).warn("[出勤申請] 通知失敗：{}", e.toString());
        }
    }

    private Map<String, Object> view(AttendanceRequest r, Worker w) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("workerId", r.getWorkerId());
        m.put("workerName", w == null ? null : MatchingService.displayName(w));
        m.put("kind", r.getKind());
        m.put("kindLabel", AttendanceRequest.CREATE.equals(r.getKind()) ? "補登" : "修正");
        m.put("attendanceId", r.getAttendanceId());
        m.put("workDate", r.getWorkDate().toString());
        m.put("clockIn", r.getClockIn());
        m.put("clockOut", r.getClockOut());
        m.put("orderId", r.getOrderId());
        m.put("reason", r.getReason());
        m.put("status", r.getStatus());
        m.put("statusLabel", STATUS_LABELS.getOrDefault(r.getStatus(), r.getStatus()));
        m.put("requestedBy", r.getRequestedBy());
        m.put("createdAt", r.getCreatedAt() == null ? null : r.getCreatedAt().toString());
        m.put("decidedBy", r.getDecidedBy());
        m.put("decidedAt", r.getDecidedAt() == null ? null : r.getDecidedAt().toString());
        m.put("decisionNote", r.getDecisionNote());
        m.put("resultAttendanceId", r.getResultAttendanceId());
        m.put("cancellable", AttendanceRequest.PENDING.equals(r.getStatus()));
        // 修正申請：附上目前的出勤時間，供審核對照
        if (r.getAttendanceId() != null) {
            Attendance a = attendanceRepository.findById(r.getAttendanceId()).orElse(null);
            if (a != null) {
                m.put("currentClockIn", a.getClockInTime() == null ? null : a.getClockInTime().toString());
                m.put("currentClockOut", a.getClockOutTime() == null ? null : a.getClockOutTime().toString());
            }
        }
        return m;
    }

    private static String note(Map<String, Object> body) {
        String n = str(body == null ? null : body.get("note"));
        if (n != null && n.length() > 200) throw ApiException.badRequest("備註請在 200 字以內");
        return n;
    }

    private static String str(Object o) {
        if (o == null) return null;
        String s = o.toString().trim();
        return s.isEmpty() ? null : s;
    }

    private static Long longOf(Object o) {
        String s = str(o);
        if (s == null) return null;
        try {
            return Long.valueOf(s);
        } catch (NumberFormatException e) {
            throw ApiException.badRequest("attendanceId 格式錯誤");
        }
    }
}
