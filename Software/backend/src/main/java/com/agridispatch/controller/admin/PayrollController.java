package com.agridispatch.controller.admin;

import com.agridispatch.security.AuthContext;
import com.agridispatch.service.PayrollService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 科技後台：出勤補登／修正與月結算（僅 admin；AuthInterceptor 的 ADMIN_ONLY 也列了 /api/payroll/**）
 * 規則見 PayrollService。
 */
@RestController
@RequestMapping("/api/payroll")
public class PayrollController {

    private final PayrollService payrollService;

    public PayrollController(PayrollService payrollService) {
        this.payrollService = payrollService;
    }

    private static AuthContext admin() {
        AuthContext ctx = AuthContext.require();
        ctx.requireAdmin();
        return ctx;
    }

    private static ResponseEntity<?> ok(Object data) {
        return ResponseEntity.ok(Map.of("success", true, "data", data));
    }

    /** 本月與前 11 個月的結算狀態 */
    @GetMapping("/months")
    public ResponseEntity<?> months() {
        admin();
        return ok(payrollService.months());
    }

    /** 某月總覽（YYYY-MM）：狀態、待處理出勤、每位工人工時與薪資 */
    @GetMapping("/months/{month}")
    public ResponseEntity<?> overview(@PathVariable String month) {
        admin();
        return ok(payrollService.overview(month));
    }

    @PostMapping("/months/{month}/settle")
    public ResponseEntity<?> settle(@PathVariable String month) {
        return ok(payrollService.settle(admin(), month));
    }

    /** 重新開放：{reason} */
    @PostMapping("/months/{month}/reopen")
    public ResponseEntity<?> reopen(@PathVariable String month, @RequestBody(required = false) Map<String, Object> body) {
        return ok(payrollService.reopen(admin(), month, body == null ? null : (String) body.get("reason")));
    }

    /** 某月出勤（?month=YYYY-MM&workerId=） */
    @GetMapping("/attendance")
    public ResponseEntity<?> attendance(@RequestParam String month, @RequestParam(required = false) String workerId) {
        admin();
        return ok(payrollService.attendance(month, workerId));
    }

    /** 補登：{workerId, workDate, clockIn, clockOut, orderId?, reason} */
    @PostMapping("/attendance")
    public ResponseEntity<?> create(@RequestBody Map<String, Object> body) {
        return ok(payrollService.createAttendance(admin(), body));
    }

    /** 修正：{clockIn?, clockOut?, reason} */
    @PutMapping("/attendance/{id}")
    public ResponseEntity<?> update(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        return ok(payrollService.updateAttendance(admin(), id, body));
    }

    /** 一筆出勤的補登／修正歷程 */
    @GetMapping("/attendance/{id}/corrections")
    public ResponseEntity<?> corrections(@PathVariable Long id) {
        admin();
        return ok(payrollService.corrections(id));
    }
}
