package com.agridispatch.service;

import com.agridispatch.common.ApiException;
import com.agridispatch.entity.*;
import com.agridispatch.repository.*;
import com.agridispatch.security.AuthContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 出勤補登／修正與月結算（科技後台，僅 admin；/api/payroll/**）
 *
 * 出勤補登與修正：
 * - 補登：替工人新增一筆已完成的出勤（漏打卡）；修正：改上班或下班時間（最常見是忘記下班打卡）。
 * - 一律要填原因，並在 attendance_corrections 留改前、改後與操作人；工時依 Attendance.completeShift 重算。
 * - 下班早於上班時視為跨日；不可晚於現在、不可與同日其他出勤重疊、已結算月份不可再改。
 *
 * 月結算：
 * - 只能結算已過完的月份；該月還有未下班的出勤時拒絕（請先修正）。
 * - 有出勤的工人各存一張薪資單快照（payroll_slips，WorkerService.slipFor），之後工人資料或出勤變動都不影響；
 *   不受外籍移工功能開關影響（有出勤就要發薪）。
 * - 重新開放須填原因：刪除該月快照，可再補登修正後重新結算。
 * - 計薪規則沿用 WorkerService（月薪制、勞健保概估）；依工作型態（兼職、打工）計時薪的規則【待確認】。
 */
@Service
@Transactional
public class PayrollService {
    public static final String MANUAL_LOCATION = "合作社補登";
    static final int MAX_SHIFT_HOURS = 24;
    static final int REASON_MAX = 200;
    /** 月結算清單列出的月份數（含本月） */
    static final int MONTH_LIST_SIZE = 12;

    private final AttendanceRepository attendanceRepository;
    private final AttendanceCorrectionRepository correctionRepository;
    private final PayrollMonthRepository monthRepository;
    private final PayrollSlipRepository slipRepository;
    private final WorkerRepository workerRepository;
    private final OrderRepository orderRepository;
    private final WorkerService workerService;
    private final Object lock = new Object();

    public PayrollService(AttendanceRepository attendanceRepository, AttendanceCorrectionRepository correctionRepository,
                          PayrollMonthRepository monthRepository, PayrollSlipRepository slipRepository,
                          WorkerRepository workerRepository, OrderRepository orderRepository, WorkerService workerService) {
        this.attendanceRepository = attendanceRepository;
        this.correctionRepository = correctionRepository;
        this.monthRepository = monthRepository;
        this.slipRepository = slipRepository;
        this.workerRepository = workerRepository;
        this.orderRepository = orderRepository;
        this.workerService = workerService;
    }

    // =========================================================================
    // 月結算
    // =========================================================================

    /** 本月與前 11 個月的結算狀態 */
    public List<Map<String, Object>> months() {
        YearMonth now = YearMonth.now();
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < MONTH_LIST_SIZE; i++) {
            YearMonth ym = now.minusMonths(i);
            out.add(monthInfo(ym, monthRepository.findById(ym.toString()).orElse(null)));
        }
        return out;
    }

    /** 某月總覽：狀態、待處理的出勤問題、每位工人的工時與薪資（已結算為快照，未結算為試算） */
    public Map<String, Object> overview(String month) {
        YearMonth ym = parseMonth(month);
        PayrollMonth pm = monthRepository.findById(ym.toString()).orElse(null);
        List<Attendance> records = monthRecords(ym);
        Map<String, Object> d = monthInfo(ym, pm);
        List<Map<String, Object>> issues = issuesOf(records);
        d.put("issues", issues);
        long blockers = issues.stream().filter(i -> "missing_clock_out".equals(i.get("type"))).count();
        d.put("blockers", blockers);
        d.put("canSettle", ym.isBefore(YearMonth.now()) && (pm == null || !pm.isSettled()) && blockers == 0);

        List<Map<String, Object>> workers = new ArrayList<>();
        if (pm != null && pm.isSettled()) {
            for (PayrollSlip s : slipRepository.findByMonthOrderByWorkerIdAsc(ym.toString())) {
                Map<String, Object> m = s.toSlipMap();
                m.put("workerId", s.getWorkerId());
                m.put("workerName", s.getWorkerName());
                m.put("tier", s.getTier());
                workers.add(m);
            }
        } else {
            for (Worker w : workersWithAttendance(records)) {
                Map<String, Object> m = workerService.slipFor(w, ym);
                m.put("workerId", w.getId());
                m.put("workerName", displayName(w));
                m.put("tier", w.getTier());
                m.put("status", "試算");
                m.put("settled", false);
                workers.add(m);
            }
        }
        d.put("workers", workers);
        d.put("totalWorkedHours", Math.round(workers.stream().mapToDouble(m -> num(m.get("workedHours"))).sum() * 10.0) / 10.0);
        d.put("totalNetPay", Math.round(workers.stream().mapToDouble(m -> num(m.get("netPay"))).sum()));
        d.put("note", "勞健保自付額為概估；依工作型態（兼職、打工）計時薪的規則【待確認】");
        return d;
    }

    public Map<String, Object> settle(AuthContext ctx, String month) {
        YearMonth ym = parseMonth(month);
        synchronized (lock) {
            if (!ym.isBefore(YearMonth.now())) throw ApiException.badRequest("只能結算已過完的月份（" + ym + " 尚未結束）");
            PayrollMonth pm = monthRepository.findById(ym.toString()).orElse(new PayrollMonth(ym.toString()));
            if (pm.isSettled()) throw ApiException.conflict(ym + " 已結算");
            List<Attendance> records = monthRecords(ym);
            List<String> open = records.stream().filter(a -> a.getClockOutTime() == null)
                    .map(a -> a.getWorkerId() + "（" + a.getWorkDate() + "）").toList();
            if (!open.isEmpty()) {
                throw ApiException.conflict("還有 " + open.size() + " 筆未下班的出勤，請先修正：" + String.join("、", open));
            }
            List<PayrollSlip> slips = new ArrayList<>();
            for (Worker w : workersWithAttendance(records)) slips.add(toSlip(ym, w, workerService.slipFor(w, ym)));
            pm.setStatus(PayrollMonth.SETTLED);
            pm.setSettledAt(LocalDateTime.now());
            pm.setSettledBy(ctx.sub());
            pm.setWorkerCount(slips.size());
            pm.setTotalNet(slips.stream().mapToLong(PayrollSlip::getNetPay).sum());
            // 先寫月份再寫薪資單（Oracle 的 payroll_slips.pay_month 參照 payroll_months）
            monthRepository.saveAndFlush(pm);
            slipRepository.saveAll(slips);
        }
        return overview(ym.toString());
    }

    public Map<String, Object> reopen(AuthContext ctx, String month, String reason) {
        YearMonth ym = parseMonth(month);
        String why = requireReason(reason);
        synchronized (lock) {
            PayrollMonth pm = monthRepository.findById(ym.toString())
                    .filter(PayrollMonth::isSettled)
                    .orElseThrow(() -> ApiException.conflict(ym + " 尚未結算，不需重新開放"));
            slipRepository.deleteByMonth(ym.toString());
            pm.setStatus(PayrollMonth.REOPENED);
            pm.setReopenedAt(LocalDateTime.now());
            pm.setReopenedBy(ctx.sub());
            pm.setReopenReason(why);
            monthRepository.save(pm);
        }
        return overview(ym.toString());
    }

    /** 該日期所屬月份是否已結算（出勤鎖定） */
    public boolean isLocked(LocalDate date) {
        return date != null && monthRepository.findById(YearMonth.from(date).toString()).map(PayrollMonth::isSettled).orElse(false);
    }

    // =========================================================================
    // 出勤補登與修正
    // =========================================================================

    /** 某月全部（或指定工人）的出勤，附修正次數 */
    public List<Map<String, Object>> attendance(String month, String workerId) {
        YearMonth ym = parseMonth(month);
        List<Attendance> records = monthRecords(ym).stream()
                .filter(a -> workerId == null || workerId.isBlank() || workerId.trim().equalsIgnoreCase(a.getWorkerId()))
                .toList();
        Map<Long, Long> counts = correctionRepository.findByClockInIdIn(records.stream().map(Attendance::getId).toList())
                .stream().collect(Collectors.groupingBy(AttendanceCorrection::getClockInId, Collectors.counting()));
        Map<String, String> names = new HashMap<>();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Attendance a : records) {
            String name = names.computeIfAbsent(a.getWorkerId(),
                    id -> workerRepository.findById(id).map(PayrollService::displayName).orElse(id));
            out.add(recordMap(a, name, counts.getOrDefault(a.getId(), 0L)));
        }
        return out;
    }

    /** 補登：{workerId, workDate, clockIn "HH:mm", clockOut "HH:mm", orderId?, reason} */
    public Map<String, Object> createAttendance(AuthContext ctx, Map<String, Object> req) {
        String workerId = text(req.get("workerId"));
        if (workerId == null) throw ApiException.badRequest("請選擇工人（workerId）");
        Worker w = workerRepository.findById(workerId).orElseThrow(() -> ApiException.notFound("找不到工人：" + workerId));
        LocalDate date = parseDate(req.get("workDate"));
        LocalDateTime[] shift = shiftOf(date, req.get("clockIn"), req.get("clockOut"));
        String reason = requireReason(req.get("reason"));
        String orderId = text(req.get("orderId"));
        if (orderId != null) {
            Order o = orderRepository.findById(orderId).orElseThrow(() -> ApiException.notFound("找不到訂單：" + orderId));
            if (OrderService.CANCELLED.equals(o.getStatus())) throw ApiException.badRequest("訂單 " + orderId + " 已取消");
        }
        synchronized (lock) {
            requireUnlocked(date);
            requireNoOverlap(w.getId(), date, shift, null);
            Attendance a = new Attendance(w.getId(), shift[0], MANUAL_LOCATION, null);
            a.setOrderId(orderId);
            a.completeShift(shift[1], null);
            a = attendanceRepository.save(a);
            audit(a, AttendanceCorrection.CREATE, null, null, reason, ctx);
            return recordMap(a, displayName(w), 1);
        }
    }

    /** 修正：{clockIn?, clockOut?, reason}；未給的時間維持原值，未下班的紀錄必須給 clockOut */
    public Map<String, Object> updateAttendance(AuthContext ctx, Long id, Map<String, Object> req) {
        String reason = requireReason(req.get("reason"));
        synchronized (lock) {
            Attendance a = attendanceRepository.findById(id).orElseThrow(() -> ApiException.notFound("找不到出勤紀錄：" + id));
            requireUnlocked(a.getWorkDate());
            LocalDateTime beforeIn = a.getClockInTime(), beforeOut = a.getClockOutTime();
            Object inText = req.get("clockIn") != null ? req.get("clockIn") : hhmm(beforeIn);
            Object outText = req.get("clockOut") != null ? req.get("clockOut") : (beforeOut == null ? null : hhmm(beforeOut));
            if (outText == null) throw ApiException.badRequest("這筆出勤尚未下班，請填下班時間");
            LocalDateTime[] shift = shiftOf(a.getWorkDate(), inText, outText);
            if (shift[0].equals(beforeIn) && shift[1].equals(beforeOut)) throw ApiException.badRequest("時間沒有變更");
            requireNoOverlap(a.getWorkerId(), a.getWorkDate(), shift, a.getId());
            a.setClockInTime(shift[0]);
            a.completeShift(shift[1], a.getForemanSignature());
            a = attendanceRepository.save(a);
            audit(a, AttendanceCorrection.UPDATE, beforeIn, beforeOut, reason, ctx);
            String name = workerRepository.findById(a.getWorkerId()).map(PayrollService::displayName).orElse(a.getWorkerId());
            return recordMap(a, name, correctionRepository.findByClockInIdOrderByCreatedAtAsc(a.getId()).size());
        }
    }

    /** 一筆出勤的補登／修正歷程 */
    public List<Map<String, Object>> corrections(Long id) {
        if (!attendanceRepository.existsById(id)) throw ApiException.notFound("找不到出勤紀錄：" + id);
        List<Map<String, Object>> out = new ArrayList<>();
        for (AttendanceCorrection c : correctionRepository.findByClockInIdOrderByCreatedAtAsc(id)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("action", c.getAction());
            m.put("actionLabel", AttendanceCorrection.CREATE.equals(c.getAction()) ? "補登" : "修正");
            m.put("beforeClockIn", c.getBeforeClockIn());
            m.put("beforeClockOut", c.getBeforeClockOut());
            m.put("afterClockIn", c.getAfterClockIn());
            m.put("afterClockOut", c.getAfterClockOut());
            m.put("reason", c.getReason());
            m.put("operator", c.getOperator());
            m.put("createdAt", c.getCreatedAt());
            out.add(m);
        }
        return out;
    }

    // =========================================================================

    private void audit(Attendance a, String action, LocalDateTime beforeIn, LocalDateTime beforeOut, String reason, AuthContext ctx) {
        AttendanceCorrection c = new AttendanceCorrection();
        c.setClockInId(a.getId());
        c.setWorkerId(a.getWorkerId());
        c.setWorkDate(a.getWorkDate());
        c.setAction(action);
        c.setBeforeClockIn(beforeIn);
        c.setBeforeClockOut(beforeOut);
        c.setAfterClockIn(a.getClockInTime());
        c.setAfterClockOut(a.getClockOutTime());
        c.setReason(reason);
        c.setOperator(ctx.sub());
        correctionRepository.save(c);
    }

    private void requireUnlocked(LocalDate date) {
        if (isLocked(date)) {
            throw ApiException.conflict(YearMonth.from(date) + " 已月結算，出勤不可再補登或修正；如需更正請先重新開放該月");
        }
    }

    /** 同一工人前後一天內的其他出勤不可與新時段重疊；未下班的紀錄視為一直延續 */
    private void requireNoOverlap(String workerId, LocalDate date, LocalDateTime[] shift, Long selfId) {
        for (LocalDate d = date.minusDays(1); !d.isAfter(date.plusDays(1)); d = d.plusDays(1)) {
            for (Attendance o : attendanceRepository.findByWorkerIdAndWorkDate(workerId, d)) {
                if (o.getId().equals(selfId)) continue;
                LocalDateTime oOut = o.getClockOutTime() != null ? o.getClockOutTime() : LocalDateTime.MAX;
                if (shift[0].isBefore(oOut) && o.getClockInTime().isBefore(shift[1])) {
                    throw ApiException.conflict("與同一工人 " + o.getWorkDate() + " 的出勤（" + hhmm(o.getClockInTime()) + " 起）時間重疊");
                }
            }
        }
    }

    /** 上下班時間（HH:mm）；下班早於上班視為隔天下班 */
    private static LocalDateTime[] shiftOf(LocalDate date, Object in, Object out) {
        LocalTime tin = parseTime(in, "上班時間");
        LocalTime tout = parseTime(out, "下班時間");
        if (tin.equals(tout)) throw ApiException.badRequest("上班與下班時間不可相同");
        LocalDateTime start = date.atTime(tin);
        LocalDateTime end = (tout.isAfter(tin) ? date : date.plusDays(1)).atTime(tout);
        if (Duration.between(start, end).toHours() >= MAX_SHIFT_HOURS) throw ApiException.badRequest("單筆出勤不可超過 24 小時");
        if (end.isAfter(LocalDateTime.now())) throw ApiException.badRequest("下班時間不可晚於現在");
        return new LocalDateTime[]{start, end};
    }

    private static LocalTime parseTime(Object v, String label) {
        String s = text(v);
        if (s == null) throw ApiException.badRequest("請填" + label + "（HH:mm）");
        try {
            return LocalTime.parse(s.length() == 4 ? "0" + s : s).withSecond(0).withNano(0);
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest(label + "須為 HH:mm");
        }
    }

    private static LocalDate parseDate(Object v) {
        String s = text(v);
        if (s == null) throw ApiException.badRequest("請填出勤日期（workDate）");
        try {
            LocalDate d = LocalDate.parse(s);
            if (d.isAfter(LocalDate.now())) throw ApiException.badRequest("出勤日期不可晚於今天");
            return d;
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest("出勤日期須為 YYYY-MM-DD");
        }
    }

    static YearMonth parseMonth(String month) {
        try {
            YearMonth ym = YearMonth.parse(month == null ? "" : month.trim());
            if (ym.isAfter(YearMonth.now())) throw ApiException.badRequest("不可查詢未來的月份");
            return ym;
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest("月份須為 YYYY-MM");
        }
    }

    private static String requireReason(Object v) {
        String s = text(v);
        if (s == null) throw ApiException.badRequest("請填寫原因");
        if (s.length() > REASON_MAX) throw ApiException.badRequest("原因最多 " + REASON_MAX + " 字");
        return s;
    }

    private List<Attendance> monthRecords(YearMonth ym) {
        return attendanceRepository.findByWorkDateBetweenOrderByWorkDateAscClockInTimeAsc(ym.atDay(1), ym.atEndOfMonth());
    }

    /** 有已下班出勤的工人（依工號排序） */
    private List<Worker> workersWithAttendance(List<Attendance> records) {
        Set<String> ids = records.stream().filter(a -> a.getClockOutTime() != null).map(Attendance::getWorkerId)
                .collect(Collectors.toCollection(TreeSet::new));
        List<Worker> out = new ArrayList<>();
        for (String id : ids) workerRepository.findById(id).ifPresent(out::add);
        return out;
    }

    private static List<Map<String, Object>> issuesOf(List<Attendance> records) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Attendance a : records) {
            String type = a.getClockOutTime() == null ? "missing_clock_out" : AttendanceService.isLongShift(a) ? "long_shift" : null;
            if (type == null) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("attendanceId", a.getId());
            m.put("workerId", a.getWorkerId());
            m.put("workDate", a.getWorkDate().toString());
            m.put("type", type);
            m.put("label", "missing_clock_out".equals(type) ? "未下班打卡（結算前須修正）"
                    : "工時超過 " + (int) AttendanceService.LONG_SHIFT_WARNING_HOURS + " 小時，請確認");
            out.add(m);
        }
        return out;
    }

    private static Map<String, Object> monthInfo(YearMonth ym, PayrollMonth pm) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("month", ym.toString());
        m.put("status", pm == null ? "open" : pm.getStatus());
        m.put("statusLabel", pm == null ? "未結算" : pm.isSettled() ? "已結算" : "已重新開放");
        m.put("settled", pm != null && pm.isSettled());
        m.put("ended", ym.isBefore(YearMonth.now()));
        if (pm != null) {
            m.put("settledAt", pm.getSettledAt());
            m.put("settledBy", pm.getSettledBy());
            m.put("workerCount", pm.getWorkerCount());
            m.put("totalNet", pm.getTotalNet());
            m.put("reopenedAt", pm.getReopenedAt());
            m.put("reopenedBy", pm.getReopenedBy());
            m.put("reopenReason", pm.getReopenReason());
        }
        return m;
    }

    private static Map<String, Object> recordMap(Attendance a, String workerName, long corrections) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.getId());
        m.put("workerId", a.getWorkerId());
        m.put("workerName", workerName);
        m.put("workDate", a.getWorkDate().toString());
        m.put("clockIn", a.getClockInTime());
        m.put("clockOut", a.getClockOutTime());
        m.put("actualHours", a.getActualHours());
        m.put("overtimeHours", a.getOvertimeHours());
        m.put("status", a.getStatus());
        m.put("orderId", a.getOrderId());
        m.put("location", a.getLocation());
        m.put("manual", MANUAL_LOCATION.equals(a.getLocation()));
        m.put("open", a.getClockOutTime() == null);
        m.put("longShift", AttendanceService.isLongShift(a));
        m.put("corrections", corrections);
        return m;
    }

    private static PayrollSlip toSlip(YearMonth ym, Worker w, Map<String, Object> s) {
        PayrollSlip p = new PayrollSlip();
        p.setId(PayrollSlip.idOf(ym.toString(), w.getId()));
        p.setMonth(ym.toString());
        p.setWorkerId(w.getId());
        p.setWorkerName(displayName(w));
        p.setTier(w.getTier());
        p.setWorkDays((int) num(s.get("workDays")));
        p.setWorkedHours(num(s.get("workedHours")));
        p.setOvertimeHours(num(s.get("overtimeHours")));
        p.setBaseSalary((int) num(s.get("baseSalary")));
        p.setAgriSubsidy((int) num(s.get("agriSubsidy")));
        p.setTransportAllowance((int) num(s.get("transportHeatAllowance")));
        p.setOvertimePay((int) num(s.get("overtimePay")));
        p.setLaborInsurance((int) num(s.get("laborInsuranceDeduction")));
        p.setHealthInsurance((int) num(s.get("healthInsuranceDeduction")));
        p.setGrossPay((int) num(s.get("grossPay")));
        p.setTotalDeductions((int) num(s.get("totalDeductions")));
        p.setNetPay((int) num(s.get("netPay")));
        return p;
    }

    private static String hhmm(LocalDateTime t) {
        return String.format("%02d:%02d", t.getHour(), t.getMinute());
    }

    private static String displayName(Worker w) {
        return w.getChineseName() != null && !w.getChineseName().isBlank() ? w.getChineseName() : w.getName();
    }

    private static String text(Object v) {
        return v == null || v.toString().isBlank() ? null : v.toString().trim();
    }

    private static double num(Object v) {
        return v instanceof Number n ? n.doubleValue() : 0;
    }
}
