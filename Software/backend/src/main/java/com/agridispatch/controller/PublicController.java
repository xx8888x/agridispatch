package com.agridispatch.controller;

import com.agridispatch.config.AgriSettings;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 公開設定查詢（前端判斷後端模式用）
 */
@RestController
@RequestMapping("/api/public")
public class PublicController {

    private final AgriSettings settings;

    public PublicController(AgriSettings settings) {
        this.settings = settings;
    }

    @GetMapping("/config")
    public ResponseEntity<?> getConfig() {
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("success", true);
        res.put("demoEnabled", settings.isDemoEnabled());
        res.put("paymentProvider", settings.getPaymentProvider());
        return ResponseEntity.ok(res);
    }
}
