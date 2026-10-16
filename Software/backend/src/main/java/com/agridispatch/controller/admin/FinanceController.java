package com.agridispatch.controller.admin;

import com.agridispatch.security.AuthContext;
import com.agridispatch.service.FinanceReportService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 科技公司財務報表（損益表、資產負債表、現金流量表、淨利）：僅科技後台最高權限（admin_tech）。
 * 合作社管理員（coop_admin）一律 403；AuthInterceptor 的 ADMIN_ONLY 也列了 /api/finance/**。
 */
@RestController
@RequestMapping("/api/finance")
public class FinanceController {

    private final FinanceReportService financeReportService;

    public FinanceController(FinanceReportService financeReportService) {
        this.financeReportService = financeReportService;
    }

    /** ?period=YYYY（年度累計）或 YYYY-MM（單月）；未帶為本年度 */
    @GetMapping("/report")
    public ResponseEntity<?> report(@RequestParam(required = false) String period) {
        AuthContext.require().requireAdmin();
        return ResponseEntity.ok(Map.of("success", true, "data", financeReportService.report(period)));
    }
}
