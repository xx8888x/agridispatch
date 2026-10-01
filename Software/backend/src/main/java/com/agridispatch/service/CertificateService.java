package com.agridispatch.service;

import com.agridispatch.common.ApiException;
import com.agridispatch.entity.Attendance;
import com.agridispatch.entity.InsuranceRecord;
import com.agridispatch.entity.Order;
import com.agridispatch.entity.Worker;
import com.agridispatch.repository.AttendanceRepository;
import com.agridispatch.repository.InsuranceRecordRepository;
import com.agridispatch.repository.WorkerRepository;
import com.agridispatch.security.TokenService;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 合法用工證明：依訂單的出勤紀錄，逐日核對「工作許可在效期內」與「應加保險種皆在保」，
 * 全部符合才可開立；附查驗碼，通路稽核人員可在公開頁面核對證明內容未被竄改。
 *
 * 查驗碼由伺服器密鑰（AGRI_TOKEN_SECRET）簽出，未固定密鑰時每次重啟會改變。
 */
@Service
public class CertificateService {

    public static final String PREFIX = "LEC-";
    public static final String ISSUER = "有限責任智農農業勞務生產合作社";
    private static final Set<String> CERTIFIABLE_STATUS = Set.of(OrderService.IN_PROGRESS, OrderService.COMPLETED);
    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");

    private final OrderService orderService;
    private final AttendanceRepository attendanceRepository;
    private final WorkerRepository workerRepository;
    private final InsuranceRecordRepository insuranceRecordRepository;
    private final InsuranceService insuranceService;
    private final TokenService tokenService;

    public CertificateService(OrderService orderService, AttendanceRepository attendanceRepository,
                              WorkerRepository workerRepository, InsuranceRecordRepository insuranceRecordRepository,
                              InsuranceService insuranceService, TokenService tokenService) {
        this.orderService = orderService;
        this.attendanceRepository = attendanceRepository;
        this.workerRepository = workerRepository;
        this.insuranceRecordRepository = insuranceRecordRepository;
        this.insuranceService = insuranceService;
        this.tokenService = tokenService;
    }

    /** 產生證明內容（不論能否開立都回傳核對結果，issuable=false 時附 problems） */
    public Map<String, Object> build(Order order) {
        List<Attendance> rows = attendanceRepository.findByOrderIdOrderByWorkDateAscClockInTimeAsc(order.getId());
        Set<String> workerIds = new TreeSet<>();
        for (Attendance a : rows) workerIds.add(a.getWorkerId());
        Map<String, Worker> workers = new HashMap<>();
        for (Worker w : workerRepository.findAllById(workerIds)) workers.put(w.getId(), w);
        List<InsuranceRecord> records = workerIds.isEmpty() ? List.of() : insuranceRecordRepository.findByWorkerIdIn(workerIds);

        List<String> problems = new ArrayList<>();
        if (!CERTIFIABLE_STATUS.contains(order.getStatus())) {
            problems.add("訂單尚未出工（目前狀態：" + OrderService.statusLabel(order.getStatus()) + "）");
        }
        if (rows.isEmpty()) problems.add("此訂單尚無出勤紀錄");

        Map<String, Map<String, Object>> byWorker = new LinkedHashMap<>();
        TreeSet<LocalDate> dates = new TreeSet<>();
        double totalHours = 0;
        int openShifts = 0;
        for (Attendance a : rows) {
            Worker w = workers.get(a.getWorkerId());
            String name = w == null ? a.getWorkerId() : displayName(w);
            Map<String, Object> entry = byWorker.computeIfAbsent(a.getWorkerId(), id -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("workerId", id);
                m.put("name", name);
                m.put("nationality", w == null ? "" : w.getNationality());
                m.put("permitExpiry", w == null ? "" : w.getPermitExpiry());
                m.put("days", new ArrayList<Map<String, Object>>());
                m.put("hours", 0.0);
                return m;
            });

            LocalDate day = a.getWorkDate();
            dates.add(day);
            boolean permitOk = w != null && permitValidOn(w, day);
            List<String> missing = w == null ? List.of("工人資料不存在") : insuranceService.missingTypes(w, records, day)
                    .stream().map(InsuranceService.TYPE_LABELS::get).toList();
            if (w == null) problems.add(a.getWorkerId() + " 工人資料不存在");
            else if (!permitOk) problems.add(name + " 於 " + day + " 無有效工作許可（效期：" + nullToDash(w.getPermitExpiry()) + "）");
            if (w != null && !missing.isEmpty()) problems.add(name + " 於 " + day + " 未完整加保（缺" + String.join("、", missing) + "）");

            Double hours = a.getClockOutTime() == null ? null : a.getActualHours();
            if (hours == null) openShifts++;
            else {
                totalHours += hours;
                entry.put("hours", round1((Double) entry.get("hours") + hours));
            }
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("date", day.toString());
            d.put("clockIn", a.getClockInTime().format(HM));
            d.put("clockOut", a.getClockOutTime() == null ? null : a.getClockOutTime().format(HM));
            d.put("hours", hours);
            d.put("permitValid", permitOk);
            d.put("insured", missing.isEmpty());
            d.put("missingInsurance", missing);
            d.put("foremanSignature", a.getForemanSignature());
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> days = (List<Map<String, Object>>) entry.get("days");
            days.add(d);
        }

        boolean issuable = problems.isEmpty();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("certificateNo", PREFIX + order.getId());
        m.put("issuable", issuable);
        m.put("problems", problems);
        m.put("issuer", ISSUER);
        m.put("issuedAt", LocalDate.now().toString());
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("orderId", order.getId());
        o.put("status", order.getStatus());
        o.put("statusLabel", OrderService.statusLabel(order.getStatus()));
        o.put("farmerName", order.getFarmerName());
        o.put("farmLocation", order.getFarmLocation());
        o.put("cropName", order.getCropName());
        o.put("categoryName", order.getCategoryName());
        o.put("teamId", order.getAssignedTeamId());
        m.put("order", o);
        m.put("periodStart", dates.isEmpty() ? null : dates.first().toString());
        m.put("periodEnd", dates.isEmpty() ? null : dates.last().toString());
        m.put("workDays", dates.size());
        m.put("workerCount", byWorker.size());
        m.put("totalHours", round1(totalHours));
        m.put("openShifts", openShifts);
        m.put("workers", new ArrayList<>(byWorker.values()));
        m.put("verifyCode", issuable ? verifyCode(m, workerIds) : null);
        return m;
    }

    /** 訂單擁有者或 admin 取得證明 */
    public Map<String, Object> forOrder(com.agridispatch.security.AuthContext ctx, String orderId) {
        Order order = orderService.requireOrder(orderId);
        orderService.checkOwnerOrAdmin(ctx, order);
        return build(order);
    }

    /** 公開查驗：只回傳摘要，不含工人姓名 */
    public Map<String, Object> verify(String certificateNo, String code) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("certificateNo", certificateNo);
        if (certificateNo == null || !certificateNo.startsWith(PREFIX) || code == null || code.isBlank()) {
            result.put("valid", false);
            result.put("reason", "證明編號或查驗碼格式不正確");
            return result;
        }
        Optional<Order> order = orderService.getOrderById(certificateNo.substring(PREFIX.length()));
        if (order.isEmpty()) {
            result.put("valid", false);
            result.put("reason", "查無此證明");
            return result;
        }
        Map<String, Object> cert = build(order.get());
        String expected = (String) cert.get("verifyCode");
        boolean valid = expected != null && MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.US_ASCII), code.trim().toUpperCase().getBytes(StandardCharsets.US_ASCII));
        result.put("valid", valid);
        if (!valid) {
            result.put("reason", expected == null ? "此訂單目前不符合開立條件" : "查驗碼不符（證明可能遭竄改，或內容已更新，請向農場索取最新版本）");
            return result;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> o = (Map<String, Object>) cert.get("order");
        result.put("issuer", ISSUER);
        result.put("farmerName", o.get("farmerName"));
        result.put("cropName", o.get("cropName"));
        result.put("categoryName", o.get("categoryName"));
        result.put("periodStart", cert.get("periodStart"));
        result.put("periodEnd", cert.get("periodEnd"));
        result.put("workerCount", cert.get("workerCount"));
        result.put("totalHours", cert.get("totalHours"));
        return result;
    }

    private String verifyCode(Map<String, Object> cert, Set<String> workerIds) {
        @SuppressWarnings("unchecked")
        Map<String, Object> o = (Map<String, Object>) cert.get("order");
        String canonical = String.join("|",
                String.valueOf(cert.get("certificateNo")), String.valueOf(o.get("farmerName")),
                String.valueOf(o.get("cropName")), String.valueOf(cert.get("periodStart")),
                String.valueOf(cert.get("periodEnd")), String.valueOf(cert.get("workerCount")),
                String.valueOf(cert.get("totalHours")), String.join(",", workerIds));
        return tokenService.signHex("labor-certificate", canonical).substring(0, 12);
    }

    /** 本國籍免工作許可；外籍須在聘僱許可效期內（含效期當日） */
    static boolean permitValidOn(Worker w, LocalDate day) {
        if (InsuranceService.isLocal(w)) return true;
        String expiry = w.getPermitExpiry();
        if (expiry == null || expiry.isBlank()) return false;
        try {
            return !day.isAfter(LocalDate.parse(expiry.trim()));
        } catch (Exception e) {
            return false;
        }
    }

    private static String displayName(Worker w) {
        return w.getChineseName() != null && !w.getChineseName().isBlank() ? w.getChineseName() : w.getName();
    }

    private static String nullToDash(String s) {
        return s == null || s.isBlank() ? "未登錄" : s;
    }

    private static double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }
}
