package com.agridispatch.controller.common;

import com.agridispatch.entity.Attendance;
import com.agridispatch.entity.Worker;
import com.agridispatch.security.AuthContext;
import com.agridispatch.service.AttendanceService;
import com.agridispatch.service.WorkerService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 農工名冊 API
 * - GET /api/workers（完整名冊）與新增 / 修改 / 刪除：僅 admin_tech（AuthInterceptor 檢查）
 * - /api/workers/{id}/**：本人或 admin_tech
 * - arcNumber / passportNumber 一律遮罩；只有 admin_tech 呼叫 GET /api/workers/{id} 取得完整值
 */
@RestController
@RequestMapping("/api/workers")
public class WorkerController {
    private static final DateTimeFormatter HMS = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final WorkerService workerService;
    private final AttendanceService attendanceService;
    private final ObjectMapper objectMapper;

    public WorkerController(WorkerService workerService, AttendanceService attendanceService, ObjectMapper objectMapper) {
        this.workerService = workerService;
        this.attendanceService = attendanceService;
        this.objectMapper = objectMapper;
    }

    @GetMapping
    public ResponseEntity<?> getAllWorkers(
            @RequestParam(required = false) String teamId,
            @RequestParam(required = false) String tier) {
        List<Worker> list;
        if (teamId != null && !teamId.isEmpty()) {
            list = workerService.getWorkersByTeam(teamId);
        } else if (tier != null && !tier.isEmpty()) {
            list = workerService.getWorkersByTier(tier);
        } else {
            list = workerService.getAllWorkers();
        }
        return ResponseEntity.ok(Map.of("success", true, "data", list));
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getWorkerById(@PathVariable String id) {
        Worker worker = workerService.requireWorker(id);
        AuthContext ctx = AuthContext.require();
        if (ctx.isAdmin()) {
            // 僅管理員取得完整證號（規格第 6 節）
            Map<String, Object> full = objectMapper.convertValue(worker, new TypeReference<LinkedHashMap<String, Object>>() {});
            full.put("arcNumber", worker.getArcNumber());
            full.put("passportNumber", worker.getPassportNumber());
            return ResponseEntity.ok(Map.of("success", true, "data", full));
        }
        return ResponseEntity.ok(Map.of("success", true, "data", worker));
    }

    @PostMapping
    public ResponseEntity<?> createWorker(@RequestBody Worker worker) {
        Worker created = workerService.createWorker(worker);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Map.of("success", true, "data", created, "message", "成功新增農工"));
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> updateWorker(@PathVariable String id, @RequestBody Map<String, Object> body) {
        Worker updated = workerService.updateWorker(id, body);
        return ResponseEntity.ok(Map.of("success", true, "data", updated, "message", "成功更新農工資料"));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteWorker(@PathVariable String id) {
        workerService.deleteWorker(id);
        return ResponseEntity.ok(Map.of("success", true, "message", "成功刪除農工: " + id));
    }

    /**
     * 上班打卡 (Clock-In) — 委派 AttendanceService（重複打卡回 409、查無工人回 404）
     */
    @PostMapping("/{id}/clock-in")
    public ResponseEntity<?> clockIn(@PathVariable String id,
                                     @RequestBody(required = false) Map<String, Object> req) {
        String location = req != null && req.get("location") != null ? req.get("location").toString() : null;
        Double accuracy = req != null && req.get("accuracy") != null
                ? Double.parseDouble(req.get("accuracy").toString())
                : null;

        String orderId = req != null && req.get("orderId") != null ? req.get("orderId").toString() : null;

        Attendance record = attendanceService.clockIn(id, location, accuracy, orderId);

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("workerId", id);
        res.put("orderId", record.getOrderId());
        res.put("date", record.getWorkDate().toString());
        res.put("clockInTime", record.getClockInTime().format(HMS));
        res.put("status", record.getStatus());
        res.put("location", record.getLocation());
        res.put("gpsAccuracy", record.getGpsAccuracyMeters() != null ? record.getGpsAccuracyMeters() + "m" : null);
        res.put("recordId", record.getId());
        res.put("message", "上班打卡成功！GPS 現場打卡已記錄入系統（" + record.getClockInTime().format(HMS) + "）");
        return ResponseEntity.ok(Map.of("success", true, "data", res));
    }

    /**
     * 下班打卡 (Clock-Out) — 關閉最近一筆未下班紀錄（可跨日），查無上班紀錄回 409
     */
    @PostMapping("/{id}/clock-out")
    public ResponseEntity<?> clockOut(@PathVariable String id,
                                      @RequestBody(required = false) Map<String, Object> req) {
        String foreman = req != null && req.get("foremanSignature") != null ? req.get("foremanSignature").toString() : null;

        Attendance record = attendanceService.clockOut(id, foreman);

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("workerId", id);
        res.put("date", record.getWorkDate().toString());
        res.put("clockInTime", record.getClockInTime().format(HMS));
        res.put("clockOutTime", record.getClockOutTime().format(HMS));
        res.put("clockOutDate", record.getClockOutTime().toLocalDate().toString());
        res.put("status", record.getStatus());
        res.put("actualHours", record.getActualHours());
        res.put("normalHours", record.getNormalHours());
        res.put("overtimeHours", record.getOvertimeHours());
        res.put("foremanSignature", record.getForemanSignature());
        res.put("recordId", record.getId());
        if (AttendanceService.isLongShift(record)) {
            res.put("warning", "本次工時超過 " + AttendanceService.LONG_SHIFT_WARNING_HOURS + " 小時，疑似忘記下班打卡，請工頭確認");
        }
        res.put("message", "下班打卡成功！本次工時 " + record.getActualHours() +
                           " 小時（加班 " + record.getOvertimeHours() + " h）已自動核算並記入薪資系統");
        return ResponseEntity.ok(Map.of("success", true, "data", res));
    }

    /**
     * 取得勞工出勤打卡紀錄（真實 DB 查詢；查無工人回 404，無紀錄回空統計）
     */
    @GetMapping("/{id}/attendance")
    public ResponseEntity<?> getAttendance(
            @PathVariable String id,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month) {
        workerService.requireWorker(id);

        List<Attendance> dbRecords;
        if (year != null && month != null) {
            dbRecords = attendanceService.getAttendanceByMonth(id, year, month);
        } else {
            dbRecords = attendanceService.getAttendanceHistory(id);
        }

        double totalHours = dbRecords.stream().mapToDouble(a -> a.getActualHours() != null ? a.getActualHours() : 0).sum();
        double overtimeHours = dbRecords.stream().mapToDouble(a -> a.getOvertimeHours() != null ? a.getOvertimeHours() : 0).sum();
        long daysWorked = dbRecords.stream().filter(a -> "completed".equals(a.getStatus())).count();

        Map<String, Object> summary = new LinkedHashMap<>();
        if (year != null && month != null) {
            summary.put("month", year + "-" + String.format("%02d", month));
        }
        summary.put("daysWorked", daysWorked);
        summary.put("totalHours", Math.round(totalHours * 10.0) / 10.0);
        summary.put("overtimeHours", Math.round(overtimeHours * 10.0) / 10.0);
        summary.put("normalHours", Math.round((totalHours - overtimeHours) * 10.0) / 10.0);
        summary.put("records", dbRecords);
        return ResponseEntity.ok(Map.of("success", true, "data", summary, "source", "db"));
    }

    /**
     * 取得勞工薪資試算明細（依工人實際資料計算，標示 simulated:true）
     */
    @GetMapping("/{id}/payroll")
    public ResponseEntity<?> getPayroll(@PathVariable String id) {
        return ResponseEntity.ok(Map.of("success", true, "data", workerService.calculatePayroll(id)));
    }
}

