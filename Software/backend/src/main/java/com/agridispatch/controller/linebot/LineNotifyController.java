package com.agridispatch.controller.linebot;

import com.agridispatch.common.ApiException;
import com.agridispatch.security.AuthContext;
import com.agridispatch.service.LineNotifyService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * LINE 主動推播的後台操作（規格 05 第柒章；皆僅 admin，AuthInterceptor 另有檢查）
 * - GET  /api/line/notify/status            工作者帳號推播時段
 * - POST /api/line/notify/tomorrow          立即發送明日出工通知 {date?: yyyy-MM-dd，預設明天}
 * - POST /api/line/notify/farmer-broadcast  農場主帳號方案行銷 {text}
 * - POST /api/line/notify/salary-paid       薪資入帳通知 {month: yyyy-MM, payDate?, items: [{workerId, netPay}]}（合作社撥款後手動發送，金額為實發）
 * 訂單狀態、定金入帳、天候警戒的通知由對應操作自動觸發。
 */
@RestController
@RequestMapping("/api/line/notify")
public class LineNotifyController {

    private final LineNotifyService lineNotifyService;

    public LineNotifyController(LineNotifyService lineNotifyService) {
        this.lineNotifyService = lineNotifyService;
    }

    @GetMapping("/status")
    public ResponseEntity<?> status() {
        AuthContext.require().requireAdmin();
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("success", true);
        res.put("workerHours", lineNotifyService.workerHoursLabel());
        res.put("inWorkerHours", lineNotifyService.workerHoursNow());
        return ResponseEntity.ok(res);
    }

    @PostMapping("/tomorrow")
    public ResponseEntity<?> tomorrow(@RequestBody(required = false) Map<String, Object> req) {
        AuthContext.require().requireAdmin();
        LocalDate date = LocalDate.now().plusDays(1);
        if (req != null && req.get("date") != null && !String.valueOf(req.get("date")).isBlank()) {
            try {
                date = LocalDate.parse(String.valueOf(req.get("date")));
            } catch (Exception e) {
                throw ApiException.badRequest("date 格式須為 yyyy-MM-dd");
            }
        }
        return ResponseEntity.ok(lineNotifyService.notifyTomorrow(date));
    }

    @PostMapping("/farmer-broadcast")
    public ResponseEntity<?> farmerBroadcast(@RequestBody Map<String, Object> req) {
        AuthContext.require().requireAdmin();
        Object text = req.get("text");
        return ResponseEntity.ok(lineNotifyService.farmerBroadcast(text == null ? null : String.valueOf(text)));
    }

    @PostMapping("/salary-paid")
    public ResponseEntity<?> salaryPaid(@RequestBody Map<String, Object> req) {
        AuthContext.require().requireAdmin();
        Object items = req.get("items");
        if (items != null && !(items instanceof List)) throw ApiException.badRequest("items 須為陣列");
        return ResponseEntity.ok(lineNotifyService.salaryPaid(
                req.get("month") == null ? null : String.valueOf(req.get("month")).trim(),
                req.get("payDate") == null ? null : String.valueOf(req.get("payDate")),
                (List<?>) items));
    }
}
