package com.agridispatch.controller.common;

import com.agridispatch.security.AuthContext;
import com.agridispatch.service.CertificateService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 合法用工證明 API
 * - GET /api/certificates/orders/{orderId}：訂單擁有者或 admin 取得證明內容與核對結果（需登入）
 * - GET /api/public/certificates/{certificateNo}?code=...：公開查驗（通路稽核用，只回摘要、不含工人姓名）
 */
@RestController
public class CertificateController {

    private final CertificateService certificateService;

    public CertificateController(CertificateService certificateService) {
        this.certificateService = certificateService;
    }

    @GetMapping("/api/certificates/orders/{orderId}")
    public ResponseEntity<?> forOrder(@PathVariable String orderId) {
        AuthContext ctx = AuthContext.require();
        return ResponseEntity.ok(Map.of("success", true, "data", certificateService.forOrder(ctx, orderId)));
    }

    @GetMapping("/api/public/certificates/{certificateNo}")
    public ResponseEntity<?> verify(@PathVariable String certificateNo, @RequestParam(required = false) String code) {
        return ResponseEntity.ok(Map.of("success", true, "data", certificateService.verify(certificateNo, code)));
    }
}
