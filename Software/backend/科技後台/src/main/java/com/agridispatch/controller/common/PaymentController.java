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

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }
}

