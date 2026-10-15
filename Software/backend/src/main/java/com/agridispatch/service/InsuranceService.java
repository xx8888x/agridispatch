package com.agridispatch.service;

import com.agridispatch.common.ApiException;
import com.agridispatch.common.LaborConstants;
import com.agridispatch.entity.InsuranceRecord;
import com.agridispatch.entity.Worker;
import com.agridispatch.repository.DispatchTeamRepository;
import com.agridispatch.repository.InsuranceRecordRepository;
import com.agridispatch.repository.WorkerRepository;
import com.agridispatch.security.AuthContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;

/**
 * 保險登錄：合作社（雇主）為工人登錄勞保／就保／職保／健保的加退保紀錄，
 * 並在派工前檢查工班成員是否都已完整加保。
 *
 * 應加保險種依身分判定（【待確認】由律師／勞保局確認）：
 * - 本國籍：勞保、就保、職保、健保
 * - 外籍（移工）：勞保、職保、健保（就業保險不適用；外籍配偶等例外情形另行確認）
 */
@Service
@Transactional
public class InsuranceService {

    public static final String LABOR = "labor";
    public static final String EMPLOYMENT = "employment";
    public static final String OCCUPATIONAL = "occupational";
    public static final String HEALTH = "health";

    /** 險種代碼 → 中文簡稱（依序顯示） */
    public static final Map<String, String> TYPE_LABELS;
    static {
        Map<String, String> m = new LinkedHashMap<>();
        m.put(LABOR, "勞保");
        m.put(EMPLOYMENT, "就保");
        m.put(OCCUPATIONAL, "職保");
        m.put(HEALTH, "健保");
        TYPE_LABELS = Collections.unmodifiableMap(m);
    }

    private static final DateTimeFormatter ID_DATE = DateTimeFormatter.BASIC_ISO_DATE;

    private final InsuranceRecordRepository recordRepository;
    private final WorkerRepository workerRepository;
    private final DispatchTeamRepository teamRepository;

    public InsuranceService(InsuranceRecordRepository recordRepository, WorkerRepository workerRepository,
                            DispatchTeamRepository teamRepository) {
        this.recordRepository = recordRepository;
        this.workerRepository = workerRepository;
        this.teamRepository = teamRepository;
    }

    // =========================================================================
    // 應加保險種與在保狀態
    // =========================================================================

    public static boolean isLocal(Worker w) {
        return "本國".equals(w.getNationality());
    }

    public static List<String> requiredTypes(Worker w) {
        return isLocal(w) ? List.of(LABOR, EMPLOYMENT, OCCUPATIONAL, HEALTH) : List.of(LABOR, OCCUPATIONAL, HEALTH);
    }

    /** 指定日期尚未在保的應加保險種 */
    public List<String> missingTypes(Worker w, List<InsuranceRecord> records, LocalDate date) {
        Set<String> active = new HashSet<>();
        for (InsuranceRecord r : records) {
            if (r.getWorkerId().equals(w.getId()) && r.isActiveOn(date)) active.add(r.getType());
        }
        List<String> missing = new ArrayList<>();
        for (String t : requiredTypes(w)) if (!active.contains(t)) missing.add(t);
        return missing;
    }

    /** 全部工人的在保摘要（admin） */
    public List<Map<String, Object>> workerSummaries(LocalDate date) {
        List<Worker> workers = workerRepository.findAll();
        workers.sort(Comparator.comparing(Worker::getId));
        List<InsuranceRecord> all = recordRepository.findAll();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Worker w : workers) out.add(summary(w, all, date));
        return out;
    }

    /** 單一工人的在保摘要＋完整加退保歷史（本人或 admin） */
    public Map<String, Object> workerDetail(AuthContext ctx, String workerId, LocalDate date) {
        ctx.requireSelfOrAdmin(workerId);
        Worker w = requireWorker(workerId);
        List<InsuranceRecord> records = recordRepository.findByWorkerIdOrderByEnrollDateDesc(workerId);
        Map<String, Object> m = summary(w, records, date);
        List<Map<String, Object>> history = new ArrayList<>();
        for (InsuranceRecord r : records) history.add(toView(r, date));
        m.put("records", history);
        return m;
    }

    /** 工班加保概況；非 admin 只回人數，不列姓名 */
    public Map<String, Object> teamCoverage(AuthContext ctx, String teamId, LocalDate date) {
        if (!teamRepository.existsById(teamId)) throw ApiException.notFound("找不到工班：" + teamId);
        List<Worker> members = workerRepository.findByTeamId(teamId);
        List<InsuranceRecord> records = recordsOf(members);
        int insured = 0;
        List<Map<String, Object>> gaps = new ArrayList<>();
        for (Worker w : members) {
            List<String> missing = missingTypes(w, records, date);
            if (missing.isEmpty()) {
                insured++;
            } else {
                gaps.add(Map.of("workerId", w.getId(), "name", displayName(w), "missing", labels(missing)));
            }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("teamId", teamId);
        m.put("date", date.toString());
        m.put("total", members.size());
        m.put("insured", insured);
        m.put("allInsured", insured == members.size());
        if (ctx != null && ctx.isStaff()) m.put("gaps", gaps);
        return m;
    }

    /** 派工前檢查：工班任一成員未完整加保即拒絕（OrderService 轉為 in_progress 時呼叫） */
    public void assertTeamInsured(String teamId, LocalDate date) {
        if (teamId == null || !teamRepository.existsById(teamId)) return; // 未分派工班不在此檢查
        assertInsured(workerRepository.findByTeamId(teamId), date);
    }

    /** 派工前檢查：只檢查出工名單上的工人（訂單已排定出工名單時使用） */
    public void assertWorkersInsured(Collection<String> workerIds, LocalDate date) {
        assertInsured(workerRepository.findAllById(workerIds), date);
    }

    private void assertInsured(List<Worker> members, LocalDate date) {
        List<InsuranceRecord> records = recordsOf(members);
        List<String> problems = new ArrayList<>();
        for (Worker w : members) {
            List<String> missing = missingTypes(w, records, date);
            if (!missing.isEmpty()) problems.add(displayName(w) + "（缺" + String.join("、", labels(missing)) + "）");
        }
        if (!problems.isEmpty()) {
            throw ApiException.conflict("工班有成員尚未完整加保，不可出工：" + String.join("；", problems));
        }
    }

    // =========================================================================
    // 加保／退保
    // =========================================================================

    public record EnrollResult(InsuranceRecord record, List<String> warnings) {}

    public EnrollResult enroll(String workerId, Object typeRaw, Object salaryRaw, Object dateRaw, Object filingRefRaw) {
        Worker w = requireWorker(workerId);
        String type = typeRaw == null ? "" : String.valueOf(typeRaw).trim();
        if (!TYPE_LABELS.containsKey(type)) {
            throw ApiException.badRequest("type 必須為 labor / employment / occupational / health");
        }
        if (!requiredTypes(w).contains(type)) {
            throw ApiException.badRequest(displayName(w) + " 的身分不適用" + TYPE_LABELS.get(type) + "【待確認】");
        }
        int salary = parsePositiveInt(salaryRaw, "insuredSalary（投保薪資）");
        LocalDate enrollDate = parseDate(dateRaw, "enrollDate（加保日）");

        for (InsuranceRecord r : recordRepository.findByWorkerIdOrderByEnrollDateDesc(workerId)) {
            if (r.getType().equals(type) && (r.getWithdrawDate() == null || r.getWithdrawDate().isAfter(enrollDate))) {
                throw ApiException.conflict(displayName(w) + " 的" + TYPE_LABELS.get(type) + "在該日期已在保（" + r.getId() + "），請先辦理退保");
            }
        }

        List<String> warnings = new ArrayList<>();
        if (salary < LaborConstants.MIN_MONTHLY_WAGE) {
            warnings.add("投保薪資低於基本工資 " + LaborConstants.formatMoney(LaborConstants.MIN_MONTHLY_WAGE)
                    + " 元，僅部分工時人員可適用較低級距，請對照投保薪資分級表【待確認】");
        }
        if (w.getBaseSalary() > 0 && salary < w.getBaseSalary()) {
            warnings.add("投保薪資低於該員月薪 " + LaborConstants.formatMoney(w.getBaseSalary()) + " 元，可能有短報疑慮");
        }

        LocalDateTime now = LocalDateTime.now();
        InsuranceRecord r = new InsuranceRecord();
        r.setId("INS-" + w.getId() + "-" + type + "-" + enrollDate.format(ID_DATE));
        if (recordRepository.existsById(r.getId())) {
            throw ApiException.conflict("同一天已有相同險種的加保紀錄：" + r.getId());
        }
        r.setWorkerId(w.getId());
        r.setType(type);
        r.setInsuredSalary(salary);
        r.setEnrollDate(enrollDate);
        r.setFilingRef(cleanText(filingRefRaw, 40));
        r.setCreatedAt(now);
        r.setUpdatedAt(now);
        recordRepository.save(r);
        syncWorkerStatus(w);
        return new EnrollResult(r, warnings);
    }

    public InsuranceRecord withdraw(String recordId, Object dateRaw, Object reasonRaw) {
        InsuranceRecord r = recordRepository.findById(recordId)
                .orElseThrow(() -> ApiException.notFound("找不到加保紀錄：" + recordId));
        if (r.getWithdrawDate() != null) {
            throw ApiException.conflict("此筆紀錄已於 " + r.getWithdrawDate() + " 退保");
        }
        LocalDate date = parseDate(dateRaw, "withdrawDate（退保日）");
        if (date.isBefore(r.getEnrollDate())) {
            throw ApiException.badRequest("退保日不可早於加保日 " + r.getEnrollDate());
        }
        r.setWithdrawDate(date);
        r.setWithdrawReason(cleanText(reasonRaw, 100));
        r.setUpdatedAt(LocalDateTime.now());
        recordRepository.save(r);
        workerRepository.findById(r.getWorkerId()).ifPresent(this::syncWorkerStatus);
        return r;
    }

    /** 把既有的文字欄位 workers.insuranceStatus 同步為依紀錄計算的結果，舊畫面也會顯示正確狀態 */
    public void syncWorkerStatus(Worker w) {
        List<InsuranceRecord> records = recordRepository.findByWorkerIdOrderByEnrollDateDesc(w.getId());
        List<String> missing = missingTypes(w, records, LocalDate.now());
        String label = missing.isEmpty()
                ? "已加保（" + String.join("／", labels(requiredTypes(w))) + "）"
                : "未完整加保：缺" + String.join("、", labels(missing));
        w.setInsuranceStatus(label);
        workerRepository.save(w);
    }

    // =========================================================================
    // 輸出與工具
    // =========================================================================

    public Map<String, Object> toView(InsuranceRecord r, LocalDate date) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("workerId", r.getWorkerId());
        m.put("type", r.getType());
        m.put("typeLabel", TYPE_LABELS.get(r.getType()));
        m.put("insuredSalary", r.getInsuredSalary());
        m.put("enrollDate", r.getEnrollDate().toString());
        m.put("withdrawDate", r.getWithdrawDate() == null ? null : r.getWithdrawDate().toString());
        m.put("withdrawReason", r.getWithdrawReason());
        m.put("filingRef", r.getFilingRef());
        m.put("active", r.isActiveOn(date));
        return m;
    }

    private Map<String, Object> summary(Worker w, List<InsuranceRecord> records, LocalDate date) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("workerId", w.getId());
        m.put("name", displayName(w));
        m.put("nationality", w.getNationality());
        m.put("teamId", w.getTeamId());
        m.put("baseSalary", w.getBaseSalary());
        m.put("required", requiredTypes(w));
        Map<String, Object> active = new LinkedHashMap<>();
        for (InsuranceRecord r : records) {
            if (r.getWorkerId().equals(w.getId()) && r.isActiveOn(date)) active.put(r.getType(), toView(r, date));
        }
        m.put("active", active);
        List<String> missing = missingTypes(w, records, date);
        m.put("missing", missing);
        m.put("fullyInsured", missing.isEmpty());
        return m;
    }

    private List<InsuranceRecord> recordsOf(List<Worker> workers) {
        if (workers.isEmpty()) return List.of();
        return recordRepository.findByWorkerIdIn(workers.stream().map(Worker::getId).toList());
    }

    private Worker requireWorker(String workerId) {
        return workerRepository.findById(workerId).orElseThrow(() -> ApiException.notFound("找不到工人：" + workerId));
    }

    private static String displayName(Worker w) {
        return w.getChineseName() != null && !w.getChineseName().isBlank() ? w.getChineseName() : w.getName();
    }

    private static List<String> labels(List<String> types) {
        return types.stream().map(TYPE_LABELS::get).toList();
    }

    private static int parsePositiveInt(Object raw, String field) {
        int v;
        try {
            v = raw instanceof Number n ? n.intValue() : Integer.parseInt(String.valueOf(raw).trim());
            if (raw instanceof Number n && n.doubleValue() != v) throw new NumberFormatException();
        } catch (Exception e) {
            throw ApiException.badRequest(field + " 必須為正整數");
        }
        if (v <= 0 || v > 1_000_000) throw ApiException.badRequest(field + " 必須為正整數");
        return v;
    }

    private static LocalDate parseDate(Object raw, String field) {
        if (raw == null || String.valueOf(raw).isBlank()) throw ApiException.badRequest(field + " 必填（yyyy-MM-dd）");
        try {
            return LocalDate.parse(String.valueOf(raw).trim());
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest(field + " 格式須為 yyyy-MM-dd");
        }
    }

    private static String cleanText(Object raw, int max) {
        if (raw == null) return null;
        String s = String.valueOf(raw).replaceAll("\\p{Cntrl}", "").trim();
        if (s.isEmpty()) return null;
        if (s.length() > max) throw ApiException.badRequest("文字欄位最多 " + max + " 字");
        return s;
    }
}
