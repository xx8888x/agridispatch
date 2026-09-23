package com.agridispatch.controller;

import com.agridispatch.service.PaymentService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/payments")
@CrossOrigin(origins = "*")
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
     * 執行金流結帳扣款 / 定金付款 (支援 ECPay / LINE Pay / ATM 轉帳)
     */
    @PostMapping("/checkout")
    public ResponseEntity<Map<String, Object>> processCheckout(@RequestBody Map<String, Object> payload) {
        try {
            Map<String, Object> result = paymentService.processCheckout(payload);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException | java.util.NoSuchElementException e) {
            Map<String, Object> err = new HashMap<>();
            err.put("success", false);
            err.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(err);
        } catch (Exception e) {
            Map<String, Object> err = new HashMap<>();
            err.put("success", false);
            err.put("message", "金流處理發生異常：" + e.getMessage());
            return ResponseEntity.internalServerError().body(err);
        }
    }

    /**
     * 查詢訂單金流付款狀態
     */
    @GetMapping("/{orderId}/status")
    public ResponseEntity<Map<String, Object>> getPaymentStatus(@PathVariable String orderId) {
        try {
            Map<String, Object> status = paymentService.getPaymentStatus(orderId);
            Map<String, Object> res = new HashMap<>();
            res.put("success", true);
            res.put("data", status);
            return ResponseEntity.ok(res);
        } catch (java.util.NoSuchElementException e) {
            Map<String, Object> err = new HashMap<>();
            err.put("success", false);
            err.put("message", e.getMessage());
            return ResponseEntity.notFound().build();
        }
    }
}
