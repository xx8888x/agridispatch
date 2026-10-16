package com.agridispatch.controller.common;

import com.agridispatch.security.AuthContext;
import com.agridispatch.service.AttendanceRequestService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 工人出勤補登／修正申請 API（規則見 AttendanceRequestService）
 * - GET  /api/workers/{id}/attendance-requests          ：某位工人的申請紀錄（本人或 admin）
 * - POST /api/workers/{id}/attendance-requests          ：申請 {attendanceId?, workDate, clockIn, clockOut, orderId?, reason}
 * - POST /api/attendance-requests/{id}/cancel           ：取消待審核的申請（本人或 admin）
 * - GET  /api/payroll/attendance-requests?status=       ：全部申請（科技後台／合作社；AuthInterceptor STAFF_ONLY 的 /api/payroll/**）
 * - POST /api/payroll/attendance-requests/{id}/approve  ：核准 {note?, clockIn?, clockOut?}，核准即補登／修正出勤
 * - POST /api/payroll/attendance-requests/{id}/reject   ：駁回 {note}
 */
@RestController
@RequestMapping("/api")
public class AttendanceRequestController {

    private final AttendanceRequestService requestService;

    public AttendanceRequestController(AttendanceRequestService requestService) {
        this.requestService = requestService;
    }

    @GetMapping("/workers/{workerId}/attendance-requests")
    public ResponseEntity<?> listForWorker(@PathVariable String workerId) {
        return ResponseEntity.ok(Map.of("success", true, "data", requestService.listForWorker(AuthContext.require(), workerId)));
    }

    @PostMapping("/workers/{workerId}/attendance-requests")
    public ResponseEntity<?> create(@PathVariable String workerId, @RequestBody Map<String, Object> req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("success", true,
                "data", requestService.create(AuthContext.require(), workerId, req), "message", "已送出補登申請，等合作社審核"));
    }

    @PostMapping("/attendance-requests/{id}/cancel")
    public ResponseEntity<?> cancel(@PathVariable String id) {
        return ResponseEntity.ok(Map.of("success", true,
                "data", requestService.cancel(AuthContext.require(), id), "message", "已取消申請"));
    }

    @GetMapping("/payroll/attendance-requests")
    public ResponseEntity<?> listAll(@RequestParam(required = false) String status) {
        return ResponseEntity.ok(Map.of("success", true, "data", requestService.listAll(AuthContext.require(), status)));
    }

    @PostMapping("/payroll/attendance-requests/{id}/approve")
    public ResponseEntity<?> approve(@PathVariable String id, @RequestBody(required = false) Map<String, Object> req) {
        return ResponseEntity.ok(Map.of("success", true,
                "data", requestService.approve(AuthContext.require(), id, req), "message", "已核准，出勤已補登"));
    }

    @PostMapping("/payroll/attendance-requests/{id}/reject")
    public ResponseEntity<?> reject(@PathVariable String id, @RequestBody(required = false) Map<String, Object> req) {
        return ResponseEntity.ok(Map.of("success", true,
                "data", requestService.reject(AuthContext.require(), id, req), "message", "已駁回申請"));
    }
}
