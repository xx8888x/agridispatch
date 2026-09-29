package com.agridispatch.controller.common;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 智農派工 (AgriDispatch) - 跨平台推播與通知中心控制器
 * 支援 FCM 雲端推播權杖註冊、天候避險廣播與原生系統通知紀錄
 */
@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private static final int MAX_HISTORY = 500;

    private final List<Map<String, Object>> notificationHistory = new CopyOnWriteArrayList<>();
    private final Map<String, String> fcmTokens = new ConcurrentHashMap<>();

    public NotificationController() {
        // 預載通知紀錄
        Map<String, Object> n1 = new LinkedHashMap<>();
        n1.put("id", "NOTIF-001");
        n1.put("channel", "weather");
        n1.put("title", "🚨 午後雷陣雨避險廣播");
        n1.put("message", "枋山芒果產區田間泥濘，工班即刻移轉至屏南冷鏈預冷包裝線！");
        n1.put("timestamp", LocalDateTime.now().minusHours(2).toString());
        n1.put("target", "all");
        notificationHistory.add(n1);

        Map<String, Object> n2 = new LinkedHashMap<>();
        n2.put("id", "NOTIF-002");
        n2.put("channel", "dispatch");
        n2.put("title", "📋 明日出勤派工通知");
        n2.put("message", "工班 B 組接駁專車將於明早 06:30 準時由宿舍出發。");
        n2.put("timestamp", LocalDateTime.now().minusHours(1).toString());
        n2.put("target", "W001");
        notificationHistory.add(n2);
    }

    @GetMapping
    public ResponseEntity<?> getHistory(@RequestParam(required = false) String target) {
        if (target != null && !target.isBlank()) {
            List<Map<String, Object>> filtered = notificationHistory.stream()
                    .filter(n -> "all".equals(n.get("target")) || target.equals(n.get("target")))
                    .toList();
            return ResponseEntity.ok(Map.of("success", true, "data", filtered));
        }
        return ResponseEntity.ok(Map.of("success", true, "data", notificationHistory));
    }

    @PostMapping("/send")
    public ResponseEntity<?> sendNotification(@RequestBody Map<String, Object> payload) {
        String title = payload.getOrDefault("title", "智農派工訊息").toString();
        String message = payload.getOrDefault("message", "").toString();
        if (message.isBlank()) {
            throw new IllegalArgumentException("通知內容 (message) 為必填欄位");
        }
        if (title.length() > 100 || message.length() > 2000) {
            throw new IllegalArgumentException("通知標題或內容過長");
        }
        String channel = payload.getOrDefault("channel", "dispatch").toString();
        String target = payload.getOrDefault("target", "all").toString();

        Map<String, Object> notif = new LinkedHashMap<>();
        notif.put("id", "NOTIF-" + System.currentTimeMillis());
        notif.put("channel", channel);
        notif.put("title", title);
        notif.put("message", message);
        notif.put("timestamp", LocalDateTime.now().toString());
        notif.put("target", target);

        notificationHistory.add(0, notif);
        while (notificationHistory.size() > MAX_HISTORY) {
            notificationHistory.remove(notificationHistory.size() - 1);
        }

        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "通知已發送",
                "data", notif
        ));
    }

    @PostMapping("/fcm/register")
    public ResponseEntity<?> registerFcmToken(@RequestBody Map<String, String> req) {
        // FCM token 一律綁定目前登入者，不接受替他人註冊
        com.agridispatch.security.AuthContext ctx = com.agridispatch.security.AuthContext.require();
        String userId = ctx.sub();
        String token = req.getOrDefault("token", "");
        if (token.length() > 4096) {
            throw new IllegalArgumentException("FCM token 過長");
        }
        if (!token.isBlank()) {
            fcmTokens.put(userId, token);
        }
        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "FCM Token 註冊成功",
                "registeredUsersCount", fcmTokens.size()
        ));
    }
}

