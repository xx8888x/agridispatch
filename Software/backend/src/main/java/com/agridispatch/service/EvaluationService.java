package com.agridispatch.service;

import com.agridispatch.common.ApiException;
import com.agridispatch.entity.Attendance;
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
 */
@Service
public class EvaluationService {

    public static final String EXCELLENT = "excellent";
    public static final String OK = "ok";
    public static final String NEEDS_IMPROVEMENT = "needs_improvement";
    public static final Map<String, String> LABELS = Map.of(EXCELLENT, "優", OK, "可", NEEDS_IMPROVEMENT, "需加強");
    static final int LEADER_EDIT_DAYS = 7;
    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");

    private final AttendanceRepository attendanceRepository;
    private final WorkerRepository workerRepository;
    private final DispatchTeamRepository teamRepository;

    public EvaluationService(AttendanceRepository attendanceRepository, WorkerRepository workerRepository,
                             DispatchTeamRepository teamRepository) {
        this.attendanceRepository = attendanceRepository;
        this.workerRepository = workerRepository;
        this.teamRepository = teamRepository;
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
