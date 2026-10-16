package com.agridispatch.controller.admin;

import com.agridispatch.security.AuthContext;
import com.agridispatch.service.FinanceReportService;
import com.agridispatch.service.FinanceVoucherService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 科技公司財務：財務報表與傳票（實際收支），僅科技後台最高權限（admin_tech）。
 * 合作社管理員（coop_admin）一律 403；AuthInterceptor 的 ADMIN_ONLY 也列了 /api/finance/**。
 */
@RestController
@RequestMapping("/api/finance")
public class FinanceController {

    private final FinanceReportService reportService;
    private final FinanceVoucherService voucherService;

    public FinanceController(FinanceReportService reportService, FinanceVoucherService voucherService) {
        this.reportService = reportService;
        this.voucherService = voucherService;
    }

    private static AuthContext admin() {
        AuthContext ctx = AuthContext.require();
        ctx.requireAdmin();
        return ctx;
    }

    private static ResponseEntity<?> ok(Object data) {
        return ResponseEntity.ok(Map.of("success", true, "data", data));
    }

    /** ?period=YYYY（年度累計）或 YYYY-MM（單月）；未帶為本年度 */
    @GetMapping("/report")
    public ResponseEntity<?> report(@RequestParam(required = false) String period) {
        admin();
        return ok(reportService.report(period));
    }

    /** 會計科目表 */
    @GetMapping("/accounts")
    public ResponseEntity<?> accounts() {
        admin();
        return ok(voucherService.accounts());
    }

    /** 傳票清單（?month=YYYY-MM 只列該月） */
    @GetMapping("/vouchers")
    public ResponseEntity<?> vouchers(@RequestParam(required = false) String month) {
        admin();
        return ok(voucherService.list(month));
    }

    /** 登錄傳票：{date, memo, lines:[{account, debit, credit, note?}]}，借貸須平衡 */
    @PostMapping("/vouchers")
    public ResponseEntity<?> create(@RequestBody Map<String, Object> body) {
        AuthContext ctx = admin();
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("success", true, "data", voucherService.create(ctx.sub(), body)));
    }

    /** 作廢傳票：{reason}；不刪除，保留紀錄 */
    @PostMapping("/vouchers/{id}/void")
    public ResponseEntity<?> voidVoucher(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> body) {
        AuthContext ctx = admin();
        return ok(voucherService.voidVoucher(ctx.sub(), id, body == null ? null : (String) body.get("reason")));
    }
}
