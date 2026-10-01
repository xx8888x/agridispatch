package com.agridispatch.controller.common;

import com.agridispatch.common.ApiException;
import com.agridispatch.entity.Attendance;
import com.agridispatch.security.AuthContext;
import com.agridispatch.service.AttendanceService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/attendance")
public class AttendanceController {

    private final AttendanceService attendanceService;

    public AttendanceController(AttendanceService attendanceService) {
        this.attendanceService = attendanceService;
    }

    /**
     * 上班 GPS 打卡（worker 只能替自己打卡，admin 可代打）
     */
    @PostMapping("/clock-in")
    public ResponseEntity<Map<String, Object>> clockIn(@RequestBody Map<String, Object> req) {
        String workerId = requireWorkerId(req);
        AuthContext.require().requireSelfOrAdmin(workerId);
        String location = req.get("location") != null ? req.get("location").toString() : null;
        Double accuracy = req.get("accuracy") != null ? Double.parseDouble(req.get("accuracy").toString()) : null;

        String orderId = req.get("orderId") != null ? req.get("orderId").toString() : null;

        Attendance att = attendanceService.clockIn(workerId, location, accuracy, orderId);
        Map<String, Object> res = new HashMap<>();
        res.put("success", true);
        res.put("data", att);
        res.put("message", "📍 上班 GPS 打卡成功！位置：" + att.getLocation());
        return ResponseEntity.ok(res);
    }

    /**
     * 下班簽核打卡
     */
    @PostMapping("/clock-out")
    public ResponseEntity<Map<String, Object>> clockOut(@RequestBody Map<String, Object> req) {
        String workerId = requireWorkerId(req);
        AuthContext.require().requireSelfOrAdmin(workerId);
        String signature = req.get("foremanSignature") != null ? req.get("foremanSignature").toString() : null;

        Attendance att = attendanceService.clockOut(workerId, signature);
        Map<String, Object> res = new HashMap<>();
        res.put("success", true);
        res.put("data", att);
        res.put("message", "🏁 下班打卡與工頭簽核完成！本日工時：" + att.getActualHours() + " 小時 (含加班 " + att.getOvertimeHours() + " 小時)");
        if (AttendanceService.isLongShift(att)) {
            res.put("warning", "本次工時超過 " + AttendanceService.LONG_SHIFT_WARNING_HOURS + " 小時，疑似忘記下班打卡，請工頭確認");
        }
        return ResponseEntity.ok(res);
    }

    private static String requireWorkerId(Map<String, Object> req) {
        Object v = req.get("workerId");
        if (v == null || v.toString().isBlank()) {
            throw ApiException.badRequest("農工工號 (workerId) 不得為空！");
        }
        return v.toString().trim();
    }

    /**
     * 查詢指定農工歷史出勤打卡紀錄（本人或 admin，由 AuthInterceptor 檢查）
     */
    @GetMapping("/history/{workerId}")
    public ResponseEntity<Map<String, Object>> getAttendanceHistory(@PathVariable String workerId) {
        List<Attendance> history = attendanceService.getAttendanceHistory(workerId);
        Map<String, Object> res = new HashMap<>();
        res.put("success", true);
        res.put("workerId", workerId);
        res.put("totalRecords", history.size());
        res.put("data", history);
        return ResponseEntity.ok(res);
    }

    /**
     * 查詢今日出勤打卡現狀
     */
    @GetMapping("/today/{workerId}")
    public ResponseEntity<Map<String, Object>> getTodayAttendance(@PathVariable String workerId) {
        List<Attendance> list = attendanceService.getTodayAttendance(workerId);
        Map<String, Object> res = new HashMap<>();
        res.put("success", true);
        res.put("data", list);
        return ResponseEntity.ok(res);
    }

    /**
     * 今日在線出勤人數統計
     */
    @GetMapping("/summary/present-count")
    public ResponseEntity<Map<String, Object>> getPresentCount() {
        long count = attendanceService.getTodayPresentCount();
        Map<String, Object> res = new HashMap<>();
        res.put("success", true);
        res.put("presentCount", count);
        return ResponseEntity.ok(res);
    }
}

