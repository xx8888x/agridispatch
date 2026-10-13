package com.agridispatch.controller.common;

import com.agridispatch.security.AuthContext;
import com.agridispatch.service.LeaveService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 工人請假 API
 * - GET  /api/leaves/types                 ：假別清單（登入即可）
 * - GET  /api/leaves?status=pending        ：全部請假（admin）
 * - POST /api/leaves/{id}/approve          ：核准（admin）{note?}；回傳 conflicts 提醒改派
 * - POST /api/leaves/{id}/reject           ：駁回（admin）{note}
 * - POST /api/leaves/{id}/cancel           ：取消（本人或 admin）{note?}
 * - GET  /api/workers/{id}/leaves          ：某位工人的請假紀錄（本人或 admin）
 * - POST /api/workers/{id}/leaves          ：申請（本人或 admin 代為）{leaveType, startDate, endDate?, timeSlot?, reason?}
 */
@RestController
@RequestMapping("/api")
public class LeaveController {

    private final LeaveService leaveService;

    public LeaveController(LeaveService leaveService) {
        this.leaveService = leaveService;
    }

    @GetMapping("/leaves/types")
    public ResponseEntity<?> types() {
        return ResponseEntity.ok(Map.of("success", true, "data", leaveService.types()));
    }

    @GetMapping("/leaves")
    public ResponseEntity<?> listAll(@RequestParam(required = false) String status) {
        return ResponseEntity.ok(Map.of("success", true, "data", leaveService.listAll(AuthContext.require(), status)));
    }

    @PostMapping("/leaves/{id}/approve")
    public ResponseEntity<?> approve(@PathVariable String id, @RequestBody(required = false) Map<String, Object> req) {
        return ResponseEntity.ok(Map.of("success", true,
                "data", leaveService.approve(AuthContext.require(), id, note(req)), "message", "已核准請假"));
    }

    @PostMapping("/leaves/{id}/reject")
    public ResponseEntity<?> reject(@PathVariable String id, @RequestBody(required = false) Map<String, Object> req) {
        return ResponseEntity.ok(Map.of("success", true,
                "data", leaveService.reject(AuthContext.require(), id, note(req)), "message", "已駁回請假"));
    }

    @PostMapping("/leaves/{id}/cancel")
    public ResponseEntity<?> cancel(@PathVariable String id, @RequestBody(required = false) Map<String, Object> req) {
        return ResponseEntity.ok(Map.of("success", true,
                "data", leaveService.cancel(AuthContext.require(), id, note(req)), "message", "已取消請假"));
    }

    @GetMapping("/workers/{workerId}/leaves")
    public ResponseEntity<?> listForWorker(@PathVariable String workerId) {
        return ResponseEntity.ok(Map.of("success", true, "data", leaveService.listForWorker(AuthContext.require(), workerId)));
    }

    @PostMapping("/workers/{workerId}/leaves")
    public ResponseEntity<?> create(@PathVariable String workerId, @RequestBody Map<String, Object> req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("success", true,
                "data", leaveService.create(AuthContext.require(), workerId, req), "message", "已送出請假申請，等合作社審核"));
    }

    private static String note(Map<String, Object> req) {
        Object n = req == null ? null : req.get("note");
        return n == null ? null : n.toString();
    }
}
