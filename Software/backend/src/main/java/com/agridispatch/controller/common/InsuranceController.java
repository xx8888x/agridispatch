package com.agridispatch.controller.common;

import com.agridispatch.security.AuthContext;
import com.agridispatch.service.InsuranceService;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 保險登錄 API（皆需登入）
 * - GET  /api/insurance/workers                  ：全部工人在保摘要（admin）
 * - GET  /api/insurance/workers/{workerId}       ：單一工人摘要＋加退保歷史（本人或 admin）
 * - POST /api/insurance/workers/{workerId}/enroll：登錄加保（admin）
 * - POST /api/insurance/records/{id}/withdraw    ：登錄退保（admin）
 * - GET  /api/insurance/teams/{teamId}           ：工班加保概況（admin 另回缺保名單）
 */
@RestController
@RequestMapping("/api/insurance")
public class InsuranceController {

    private final InsuranceService insuranceService;

    public InsuranceController(InsuranceService insuranceService) {
        this.insuranceService = insuranceService;
    }

    @GetMapping("/types")
    public ResponseEntity<?> types() {
        AuthContext.require();
        return ResponseEntity.ok(Map.of("success", true, "data", InsuranceService.TYPE_LABELS));
    }

    @GetMapping("/workers")
    public ResponseEntity<?> workers(@RequestParam(required = false) String date) {
        AuthContext.require().requireAdmin();
        return ResponseEntity.ok(Map.of("success", true, "data", insuranceService.workerSummaries(dateOrToday(date))));
    }

    @GetMapping("/workers/{workerId}")
    public ResponseEntity<?> worker(@PathVariable String workerId) {
        AuthContext ctx = AuthContext.require();
        return ResponseEntity.ok(Map.of("success", true, "data", insuranceService.workerDetail(ctx, workerId, LocalDate.now())));
    }

    @PostMapping("/workers/{workerId}/enroll")
    public ResponseEntity<?> enroll(@PathVariable String workerId, @RequestBody Map<String, Object> req) {
        AuthContext.require().requireAdmin();
        InsuranceService.EnrollResult result = insuranceService.enroll(workerId, req.get("type"),
                req.get("insuredSalary"), req.get("enrollDate"), req.get("filingRef"));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("data", insuranceService.toView(result.record(), LocalDate.now()));
        body.put("warnings", result.warnings());
        return ResponseEntity.ok(body);
    }

    @PostMapping("/records/{recordId}/withdraw")
    public ResponseEntity<?> withdraw(@PathVariable String recordId, @RequestBody Map<String, Object> req) {
        AuthContext.require().requireAdmin();
        return ResponseEntity.ok(Map.of("success", true, "data",
                insuranceService.toView(insuranceService.withdraw(recordId, req.get("withdrawDate"), req.get("reason")), LocalDate.now()),
                "warnings", List.of()));
    }

    @GetMapping("/teams/{teamId}")
    public ResponseEntity<?> team(@PathVariable String teamId) {
        AuthContext ctx = AuthContext.require();
        return ResponseEntity.ok(Map.of("success", true, "data", insuranceService.teamCoverage(ctx, teamId, LocalDate.now())));
    }

    private static LocalDate dateOrToday(String date) {
        if (date == null || date.isBlank()) return LocalDate.now();
        try {
            return LocalDate.parse(date.trim());
        } catch (Exception e) {
            throw com.agridispatch.common.ApiException.badRequest("date 格式須為 yyyy-MM-dd");
        }
    }
}
