package com.agridispatch.service;

import com.agridispatch.common.ApiException;
import com.agridispatch.common.IdGenerator;
import com.agridispatch.common.LaborConstants;
import com.agridispatch.entity.Attendance;
import com.agridispatch.entity.Worker;
import com.agridispatch.repository.WorkerRepository;
import com.agridispatch.security.Masking;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.YearMonth;
import java.util.*;

@Service
@Transactional
public class WorkerService {

    public static final Set<String> TIERS = Set.of("tier1_foreign", "tier2_local_flex", "tier3_turnkey");

    private final WorkerRepository workerRepository;
    private final AttendanceService attendanceService;
    private final ObjectMapper objectMapper;
    private final Object idLock = new Object();

    public WorkerService(WorkerRepository workerRepository, AttendanceService attendanceService, ObjectMapper objectMapper) {
        this.workerRepository = workerRepository;
        this.attendanceService = attendanceService;
        this.objectMapper = objectMapper;
    }

    public List<Worker> getAllWorkers() {
        return workerRepository.findAll();
    }

    public Optional<Worker> getWorkerById(String id) {
        return workerRepository.findById(id);
    }

    public Worker requireWorker(String id) {
        return workerRepository.findById(id).orElseThrow(() -> ApiException.notFound("找不到該農工編號: " + id));
    }

    public List<Worker> getWorkersByTeam(String teamId) {
        return workerRepository.findByTeamId(teamId);
    }

    public List<Worker> getWorkersByTier(String tier) {
        return workerRepository.findByTier(tier);
    }

    /**
     * 新增農工：ID 未指定時自動取「現有最大序號 +1」；指定的 ID 已存在時回 409，不覆寫
     * （不包外層交易：在鎖內即時寫入並提交，避免併發產生相同 ID）
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Worker createWorker(Worker worker) {
        if (worker.getName() == null || worker.getName().isBlank()) {
            throw ApiException.badRequest("姓名 (name) 為必填欄位");
        }
        if (worker.getTier() == null || !TIERS.contains(worker.getTier())) {
            throw ApiException.badRequest("tier 必須為 tier1_foreign / tier2_local_flex / tier3_turnkey");
        }
        validateMoney(worker);
        if (Masking.isMasked(worker.getArcNumber()) || Masking.isMasked(worker.getPassportNumber())) {
            throw ApiException.badRequest("證件號碼不可為遮罩值");
        }
        worker.setPasswordHash(null);
        synchronized (idLock) {
            if (worker.getId() == null || worker.getId().trim().isEmpty()) {
                worker.setId(IdGenerator.next("W", 3, workerRepository.findIdsByPrefix("W"), workerRepository::existsById));
            } else {
                worker.setId(worker.getId().trim().toUpperCase(Locale.ROOT));
                if (workerRepository.existsById(worker.getId())) {
                    throw ApiException.conflict("農工編號已存在: " + worker.getId());
                }
            }
            return workerRepository.saveAndFlush(worker);
        }
    }

    /**
     * 更新農工：只更新 body 中有出現的欄位；遮罩後的證號（含 ****）視為未變更
     */
    public Worker updateWorker(String id, Map<String, Object> body) {
        Worker existing = requireWorker(id);
        Map<String, Object> copy = new HashMap<>(body);
        copy.remove("id");
        copy.remove("passwordHash");
        if (copy.containsKey("isLeader") && !copy.containsKey("leader")) {
            copy.put("leader", copy.get("isLeader"));
        }
        copy.remove("isLeader");
        Worker u;
        try {
            u = objectMapper.convertValue(copy, Worker.class);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("農工資料欄位格式錯誤");
        }

        if (copy.containsKey("name")) {
            if (u.getName() == null || u.getName().isBlank()) throw ApiException.badRequest("姓名 (name) 不可為空");
            existing.setName(u.getName());
        }
        if (copy.containsKey("chineseName")) existing.setChineseName(u.getChineseName());
        if (copy.containsKey("nationality")) existing.setNationality(u.getNationality());
        if (copy.containsKey("teamId")) existing.setTeamId(u.getTeamId());
        if (copy.containsKey("leader")) existing.setLeader(u.isLeader());
        if (copy.containsKey("tier")) {
            if (!TIERS.contains(u.getTier())) throw ApiException.badRequest("tier 不合法");
            existing.setTier(u.getTier());
        }
        if (copy.containsKey("baseSalary")) existing.setBaseSalary(u.getBaseSalary());
        if (copy.containsKey("agriBonus")) existing.setAgriBonus(u.getAgriBonus());
        if (copy.containsKey("transportAllowance")) existing.setTransportAllowance(u.getTransportAllowance());
        if (copy.containsKey("arcNumber") && !Masking.isMasked(u.getArcNumber())) existing.setArcNumber(emptyToNull(u.getArcNumber()));
        if (copy.containsKey("passportNumber") && !Masking.isMasked(u.getPassportNumber())) existing.setPassportNumber(emptyToNull(u.getPassportNumber()));
        if (copy.containsKey("permitExpiry")) existing.setPermitExpiry(u.getPermitExpiry());
        if (copy.containsKey("insuranceStatus")) existing.setInsuranceStatus(u.getInsuranceStatus());
        if (copy.containsKey("currentTask")) existing.setCurrentTask(u.getCurrentTask());
        if (copy.containsKey("dispatchStatus")) existing.setDispatchStatus(u.getDispatchStatus());
        if (copy.containsKey("phone")) existing.setPhone(u.getPhone());
        validateMoney(existing);
        return workerRepository.save(existing);
    }

    public void deleteWorker(String id) {
        if (!workerRepository.existsById(id)) {
            throw ApiException.notFound("找不到該農工編號: " + id);
        }
        workerRepository.deleteById(id);
    }

    private static String emptyToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static void validateMoney(Worker w) {
        if (w.getBaseSalary() < 0 || w.getAgriBonus() < 0 || w.getTransportAllowance() < 0) {
            throw ApiException.badRequest("薪資與津貼金額不得為負數");
        }
    }

    // =========================================================================
    // 薪資試算（依工人實際資料 + 本月出勤計算；勞健保扣款為概估，標示 simulated）
    // =========================================================================

    public Map<String, Object> calculatePayroll(String workerId) {
        Worker w = requireWorker(workerId);
        YearMonth ym = YearMonth.now();
        List<Attendance> records = attendanceService.getAttendanceByMonth(workerId, ym.getYear(), ym.getMonthValue());
        double overtimeHours = records.stream().mapToDouble(a -> a.getOvertimeHours() != null ? a.getOvertimeHours() : 0).sum();
        double totalHours = records.stream().mapToDouble(a -> a.getActualHours() != null ? a.getActualHours() : 0).sum();

        Map<String, Object> current = buildSlip(w, overtimeHours);
        current.put("month", ym.getYear() + "年" + String.format("%02d", ym.getMonthValue()) + "月 (預估結算)");
        current.put("payDate", ym.plusMonths(1).atDay(5) + " (預定入帳)");
        current.put("bankAccount", "（尚未登錄薪轉帳戶）");
        current.put("status", "試算中");
        current.put("workedHours", Math.round(totalHours * 10.0) / 10.0);
        current.put("overtimeHours", Math.round(overtimeHours * 10.0) / 10.0);

        List<Map<String, Object>> pastSlips = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            YearMonth past = ym.minusMonths(i);
            // 歷月薪資單也依該月實際出勤的加班時數試算（與本月同一套 buildSlip），前端不需自備數字
            List<Attendance> pastRecords = attendanceService.getAttendanceByMonth(workerId, past.getYear(), past.getMonthValue());
            double pastOvertime = pastRecords.stream().mapToDouble(a -> a.getOvertimeHours() != null ? a.getOvertimeHours() : 0).sum();
            Map<String, Object> brief = buildSlip(w, pastOvertime);
            brief.put("period", past.getYear() + "年" + String.format("%02d", past.getMonthValue()) + "月");
            brief.put("overtimeHours", Math.round(pastOvertime * 10.0) / 10.0);
            brief.put("status", "試算");
            brief.put("slipId", "SIM-" + past.toString().replace("-", "") + "-" + w.getId());
            brief.put("simulated", true);
            pastSlips.add(brief);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("workerId", w.getId());
        data.put("simulated", true);
        data.put("note", "依農工底薪、津貼與本月出勤試算；勞健保自付額為概估值，實際以合作社薪資系統為準");
        data.put("currentMonth", current);
        data.put("historicalSlips", pastSlips);
        return data;
    }

    private Map<String, Object> buildSlip(Worker w, double overtimeHours) {
        boolean turnkey = "tier3_turnkey".equals(w.getTier());
        // 月薪不得低於基本工資（機耕統包師傅為承攬計酬，沿用資料值）
        int base = turnkey ? w.getBaseSalary() : Math.max(w.getBaseSalary(), LaborConstants.MIN_MONTHLY_WAGE);
        double hourly = Math.max(base / (double) LaborConstants.MONTHLY_HOURS_BASIS, LaborConstants.MIN_HOURLY_WAGE);
        int overtimePay = (int) Math.round(overtimeHours * hourly * LaborConstants.OVERTIME_RATE_FIRST_2H);
        // TODO 待農業部確認適用對象（就業獎勵金 8,800）
        int agriBonus = w.getAgriBonus();
        int transport = w.getTransportAllowance();
        // 概估：勞保費率 12.5% × 自付 20%、健保費率 5.17% × 自付 30%
        int laborIns = (int) Math.round(base * 0.125 * 0.2);
        int healthIns = (int) Math.round(base * 0.0517 * 0.3);
        int gross = base + agriBonus + transport + overtimePay;
        int deductions = laborIns + healthIns;

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("baseSalary", base);
        m.put("minimumWage", LaborConstants.MIN_MONTHLY_WAGE);
        m.put("minimumHourlyWage", LaborConstants.MIN_HOURLY_WAGE);
        m.put("agriSubsidy", agriBonus);
        m.put("transportHeatAllowance", transport);
        m.put("overtimePay", overtimePay);
        if (!"tier1_foreign".equals(w.getTier())) {
            m.put("pension6Percent", (int) Math.round(base * 0.06)); // 雇主提撥勞退 6%（不自薪資扣除）
        }
        m.put("laborInsuranceDeduction", laborIns);
        m.put("healthInsuranceDeduction", healthIns);
        m.put("taxWithholding", 0);
        m.put("grossPay", gross);
        m.put("totalDeductions", deductions);
        m.put("netPay", gross - deductions);
        m.put("simulated", true);
        return m;
    }
}
