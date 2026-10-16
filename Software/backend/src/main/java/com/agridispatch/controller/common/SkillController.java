package com.agridispatch.controller.common;

import com.agridispatch.common.ApiException;
import com.agridispatch.security.AuthContext;
import com.agridispatch.service.EvaluationService;
import com.agridispatch.service.SkillService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Map;

/**
 * 熟手標籤與領班逐人評核 API（皆需登入）
 * - GET  /api/skills/workers                         ：全部工人技能摘要（admin）
 * - GET  /api/skills/workers/{workerId}              ：本人或 admin 查看技能進度與履歷認證
 * - POST /api/skills/workers/{workerId}/{skill}/restore：admin 複核後恢復暫停中的標籤
 * - GET  /api/skills/teams/{teamId}                  ：工班標籤人數（不列姓名）
 * - GET  /api/evaluations/teams/{teamId}?date=       ：領班查看工班某日班次與評核（該工班領班或 admin）
 * - PUT  /api/evaluations/attendance/{attendanceId}  ：領班評核一班（優／可／需加強）
 * - GET  /api/evaluations/orders/{orderId}           ：訂單出工名單與農場主評核狀態（訂單農場主或 admin）
 * - PUT  /api/evaluations/orders/{orderId}/workers/{workerId}：農場主評核某工人在此訂單的表現（優／可／需加強）
 */
@RestController
public class SkillController {

    private final SkillService skillService;
    private final EvaluationService evaluationService;

    public SkillController(SkillService skillService, EvaluationService evaluationService) {
        this.skillService = skillService;
        this.evaluationService = evaluationService;
    }

    @GetMapping("/api/skills/workers")
    public ResponseEntity<?> workers() {
        AuthContext.require().requireStaff();
        return ResponseEntity.ok(Map.of("success", true, "data", skillService.allProfiles()));
    }

    @GetMapping("/api/skills/workers/{workerId}")
    public ResponseEntity<?> worker(@PathVariable String workerId) {
        AuthContext ctx = AuthContext.require();
        return ResponseEntity.ok(Map.of("success", true, "data", skillService.workerProfile(ctx, workerId)));
    }

    @PostMapping("/api/skills/workers/{workerId}/{skill}/restore")
    public ResponseEntity<?> restore(@PathVariable String workerId, @PathVariable String skill,
                                     @RequestBody(required = false) Map<String, Object> req) {
        AuthContext ctx = AuthContext.require();
        return ResponseEntity.ok(Map.of("success", true, "data",
                skillService.restore(ctx, workerId, skill, req == null ? null : req.get("note"))));
    }

    @GetMapping("/api/skills/teams/{teamId}")
    public ResponseEntity<?> team(@PathVariable String teamId) {
        AuthContext.require();
        return ResponseEntity.ok(Map.of("success", true, "data", skillService.teamSummary(teamId)));
    }

    @GetMapping("/api/evaluations/teams/{teamId}")
    public ResponseEntity<?> teamDay(@PathVariable String teamId, @RequestParam(required = false) String date) {
        AuthContext ctx = AuthContext.require();
        LocalDate day;
        try {
            day = date == null || date.isBlank() ? LocalDate.now() : LocalDate.parse(date.trim());
        } catch (Exception e) {
            throw ApiException.badRequest("date 格式須為 yyyy-MM-dd");
        }
        return ResponseEntity.ok(Map.of("success", true, "data", evaluationService.teamDay(ctx, teamId, day)));
    }

    @GetMapping("/api/evaluations/orders/{orderId}")
    public ResponseEntity<?> orderWorkers(@PathVariable String orderId) {
        AuthContext ctx = AuthContext.require();
        return ResponseEntity.ok(Map.of("success", true, "data", evaluationService.orderWorkers(ctx, orderId)));
    }

    @PutMapping("/api/evaluations/orders/{orderId}/workers/{workerId}")
    public ResponseEntity<?> rateWorker(@PathVariable String orderId, @PathVariable String workerId,
                                        @RequestBody Map<String, Object> req) {
        AuthContext ctx = AuthContext.require();
        return ResponseEntity.ok(Map.of("success", true, "data",
                evaluationService.rateWorkerOnOrder(ctx, orderId, workerId, req.get("rating"))));
    }

    @PutMapping("/api/evaluations/attendance/{attendanceId}")
    public ResponseEntity<?> evaluate(@PathVariable Long attendanceId, @RequestBody Map<String, Object> req) {
        AuthContext ctx = AuthContext.require();
        return ResponseEntity.ok(Map.of("success", true, "data",
                evaluationService.toView(evaluationService.evaluate(ctx, attendanceId, req.get("rating")))));
    }
}
