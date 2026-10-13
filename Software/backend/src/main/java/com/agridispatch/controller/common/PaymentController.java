package com.agridispatch.controller.common;

import com.agridispatch.common.ApiException;
import com.agridispatch.security.AuthContext;
import com.agridispatch.service.PaymentService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 定金金流 API
 * - POST /api/payments/checkout：建立待付款交易（金額由後端計算）
 * - POST /api/payments/{txId}/mock-confirm：mock 金流確認付款（僅 demo 模式）
 * - POST /api/payments/ecpay/notify：綠界付款結果通知（CheckMacValue 驗證，回 1|OK）
 * - POST /api/payments/{orderId}/balance：登錄尾款收款（admin，完工訂單）
 * - PUT  /api/payments/transactions/{txId}/invoice：補登／更正發票號碼（admin）
 * - POST /api/payments/transactions/{txId}/void：作廢尾款登錄（admin）
 */
@RestController
@RequestMapping("/api/payments")
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    /**
     * 支援的金流管道查詢
     */
    @GetMapping("/methods")
    public ResponseEntity<Map<String, Object>> getPaymentMethods() {
        List<Map<String, Object>> methods = paymentService.getSupportedMethods();
        Map<String, Object> res = new HashMap<>();
        res.put("success", true);
        res.put("data", methods);
        return ResponseEntity.ok(res);
    }

    /**
     * 建立定金付款交易：body {orderId, paymentMethod?, payerName?}；client 傳入的 amount 一律忽略
     */
    @PostMapping("/checkout")
    public ResponseEntity<Map<String, Object>> processCheckout(@RequestBody Map<String, Object> payload) {
        AuthContext ctx = AuthContext.require();
        Map<String, Object> result = paymentService.checkout(ctx,
                str(payload.get("orderId")), str(payload.get("paymentMethod")), str(payload.get("payerName")));
        return ResponseEntity.ok(result);
    }

    /**
     * mock 金流確認付款（僅 demo 模式，非 demo 回 404）
     */
    @PostMapping("/{txId}/mock-confirm")
    public ResponseEntity<Map<String, Object>> mockConfirm(@PathVariable String txId) {
        AuthContext ctx = AuthContext.require();
        return ResponseEntity.ok(paymentService.mockConfirm(ctx, txId));
    }

    /**
     * 綠界 ECPay 付款結果通知（application/x-www-form-urlencoded）
     */
    @PostMapping(value = "/ecpay/notify", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> ecpayNotify(@RequestParam MultiValueMap<String, String> form) {
        Map<String, String> params = new LinkedHashMap<>();
        form.forEach((k, v) -> params.put(k, v == null || v.isEmpty() ? "" : v.get(0)));
        try {
            return ResponseEntity.ok(paymentService.handleEcpayNotify(params));
        } catch (ApiException e) {
            return ResponseEntity.status(e.getStatus()).contentType(MediaType.TEXT_PLAIN).body(e.getMessage());
        }
    }

    /**
     * 查詢訂單金流付款狀態（訂單擁有者或 admin）
     */
    @GetMapping("/{orderId}/status")
    public ResponseEntity<Map<String, Object>> getPaymentStatus(@PathVariable String orderId) {
        AuthContext ctx = AuthContext.require();
        Map<String, Object> res = new HashMap<>();
        res.put("success", true);
        res.put("data", paymentService.getPaymentStatus(ctx, orderId));
        return ResponseEntity.ok(res);
    }

    /**
     * 登錄尾款收款（admin）：body {amount?, paidDate?, paymentMethod?, invoiceNo?, note?}；amount 省略＝全部未結清尾款
     */
    @PostMapping("/{orderId}/balance")
    public ResponseEntity<Map<String, Object>> recordBalance(@PathVariable String orderId, @RequestBody(required = false) Map<String, Object> payload) {
        requireAdmin();
        Map<String, Object> p = payload == null ? Map.of() : payload;
        Integer amount = null;
        if (p.get("amount") != null && !p.get("amount").toString().isBlank()) {
            try {
                amount = (int) Math.round(Double.parseDouble(p.get("amount").toString()));
            } catch (NumberFormatException e) {
                throw ApiException.badRequest("收款金額 (amount) 必須為數字");
            }
        }
        Map<String, Object> data = paymentService.recordBalance(orderId, amount, str(p.get("paidDate")),
                str(p.get("paymentMethod")), str(p.get("invoiceNo")), str(p.get("note")));
        return ResponseEntity.ok(Map.of("success", true, "data", data, "message", "已登錄尾款收款"));
    }

    /** 補登／更正發票號碼（admin）：body {invoiceNo} */
    @PutMapping("/transactions/{txId}/invoice")
    public ResponseEntity<Map<String, Object>> setInvoiceNo(@PathVariable String txId, @RequestBody Map<String, Object> payload) {
        requireAdmin();
        Map<String, Object> data = paymentService.setInvoiceNo(txId, str(payload.get("invoiceNo")));
        return ResponseEntity.ok(Map.of("success", true, "data", data, "message", "已登錄發票號碼"));
    }

    /** 作廢尾款登錄（admin）：body {reason} */
    @PostMapping("/transactions/{txId}/void")
    public ResponseEntity<Map<String, Object>> voidBalance(@PathVariable String txId, @RequestBody Map<String, Object> payload) {
        requireAdmin();
        Map<String, Object> data = paymentService.voidBalance(txId, str(payload.get("reason")));
        return ResponseEntity.ok(Map.of("success", true, "data", data, "message", "已作廢尾款登錄"));
    }

    /** AuthInterceptor 已限 admin；這裡再確認一次 */
    private static void requireAdmin() {
        if (!AuthContext.require().isAdmin()) throw ApiException.forbidden();
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }
}

