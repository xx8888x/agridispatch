package com.agridispatch.controller;

import com.agridispatch.entity.Attendance;
import com.agridispatch.service.AttendanceService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/attendance")
@CrossOrigin(origins = "*")
public class AttendanceController {

    private final AttendanceService attendanceService;

    public AttendanceController(AttendanceService attendanceService) {
        this.attendanceService = attendanceService;
    }

    /**
     * 上班 GPS 打卡
     */
    @PostMapping("/clock-in")
    public ResponseEntity<Map<String, Object>> clockIn(@RequestBody Map<String, Object> req) {
        String workerId = (String) req.get("workerId");
        String location = (String) req.get("location");
        Double accuracy = req.get("accuracy") != null ? Double.parseDouble(req.get("accuracy").toString()) : 3.0;

        try {
            Attendance att = attendanceService.clockIn(workerId, location, accuracy);
            Map<String, Object> res = new HashMap<>();
            res.put("success", true);
            res.put("data", att);
            res.put("message", "📍 上班 GPS 打卡成功！位置：" + att.getLocation());
            return ResponseEntity.ok(res);
        } catch (Exception e) {
            Map<String, Object> err = new HashMap<>();
            err.put("success", false);
            err.put("message", "打卡失敗：" + e.getMessage());
            return ResponseEntity.badRequest().body(err);
        }
    }

    /**
     * 下班簽核打卡
     */
    @PostMapping("/clock-out")
    public ResponseEntity<Map<String, Object>> clockOut(@RequestBody Map<String, Object> req) {
        String workerId = (String) req.get("workerId");
        String signature = (String) req.get("foremanSignature");

        try {
            Attendance att = attendanceService.clockOut(workerId, signature);
            Map<String, Object> res = new HashMap<>();
            res.put("success", true);
            res.put("data", att);
            res.put("message", "🏁 下班打卡與工頭簽核完成！本日工時：" + att.getActualHours() + " 小時 (含加班 " + att.getOvertimeHours() + " 小時)");
            return ResponseEntity.ok(res);
        } catch (Exception e) {
            Map<String, Object> err = new HashMap<>();
            err.put("success", false);
            err.put("message", "下班打卡失敗：" + e.getMessage());
            return ResponseEntity.badRequest().body(err);
        }
    }

    /**
     * 查詢指定農工歷史出勤打卡紀錄
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
