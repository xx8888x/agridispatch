package com.agridispatch.controller.common;

import com.agridispatch.service.FcmPushService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 智農派工 (AgriDispatch) - 跨平台推播與通知中心控制器
 * 支援 FCM 雲端推播權杖註冊、天候避險廣播與原生系統通知紀錄
 * 通知紀錄存記憶體；FCM token 存 push_tokens 表，/send 同時以 FcmPushService 推播到 App（未設定 FCM 時只留紀錄）
 */
@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private static final int MAX_HISTORY = 500;

    private final List<Map<String, Object>> notificationHistory = new CopyOnWriteArrayList<>();
    private final FcmPushService pushService;

    public NotificationController(com.agridispatch.config.AgriSettings settings, FcmPushService pushService) {
        this.pushService = pushService;
        // 預載示範通知紀錄（agri.seed.demo-data；正式環境不預載，避免假的天候廣播出現在所有人的通知裡）
        if (!settings.isSeedDemoData()) return;
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
        // 非 admin 只能看全體公告與發給自己的通知
        com.agridispatch.security.AuthContext ctx = com.agridispatch.security.AuthContext.require();
        if (!ctx.isAdmin()) target = ctx.ref() != null ? ctx.ref() : ctx.sub();
        if (target != null && !target.isBlank()) {
            final String who = target;
            List<Map<String, Object>> filtered = notificationHistory.stream()
                    .filter(n -> "all".equals(n.get("target")) || who.equals(n.get("target")))
                    .toList();
            return ResponseEntity.ok(Map.of("success", true, "data", filtered));
        }
        return ResponseEntity.ok(Map.of("success", true, "data", notificationHistory));
    }

    @PostMapping("/send")
    public ResponseEntity<?> sendNotification(@RequestBody Map<String, Object> payload) {
        String title = Objects.toString(payload.get("title"), "智農派工訊息");
        String message = Objects.toString(payload.get("message"), "");
        if (message.isBlank()) {
            throw new IllegalArgumentException("通知內容 (message) 為必填欄位");
        }
        if (title.length() > 100 || message.length() > 2000) {
            throw new IllegalArgumentException("通知標題或內容過長");
        }
        String channel = Objects.toString(payload.get("channel"), "dispatch");
        String target = Objects.toString(payload.get("target"), "all");

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

        Map<String, Object> push = pushService.send(target, title, message, channel);

        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "通知已發送",
                "data", notif,
                "push", push
        ));
    }

    @PostMapping("/fcm/register")
    public ResponseEntity<?> registerFcmToken(@RequestBody Map<String, String> req) {
        // FCM token 一律綁定目前登入者，不接受替他人註冊；同一台裝置換人登入時改綁
        com.agridispatch.security.AuthContext ctx = com.agridispatch.security.AuthContext.require();
        String token = validToken(req.get("token"));
        if (token != null) {
            pushService.register(token, ctx.sub(), ctx.ref(), ctx.role(), req.get("platform"));
        }
        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", token != null ? "FCM Token 註冊成功" : "未提供 FCM Token",
                "pushConfigured", pushService.isConfigured()
        ));
    }

    /** 登出時移除本裝置 token（只刪屬於目前登入者的） */
    @PostMapping("/fcm/unregister")
    public ResponseEntity<?> unregisterFcmToken(@RequestBody Map<String, String> req) {
        com.agridispatch.security.AuthContext ctx = com.agridispatch.security.AuthContext.require();
        String token = validToken(req.get("token"));
        boolean removed = token != null && pushService.unregister(token, ctx.sub());
        return ResponseEntity.ok(Map.of("success", true, "removed", removed));
    }

    /** 僅 admin：App 推播設定狀態（不回傳金鑰與 token） */
    @GetMapping("/fcm/status")
    public ResponseEntity<?> fcmStatus() {
        com.agridispatch.security.AuthContext.require().requireAdmin();
        return ResponseEntity.ok(Map.of(
                "success", true,
                "configured", pushService.isConfigured(),
                "registeredDevices", pushService.tokenCount()
        ));
    }

    private static String validToken(String raw) {
        String token = raw == null ? "" : raw.trim();
        if (token.length() > 1024) {
            throw new IllegalArgumentException("FCM token 過長");
        }
        // App 未接上 Firebase 時舊版會回傳 MOCK_FCM_TOKEN_*，不登記
        return token.isEmpty() || token.startsWith("MOCK_FCM_TOKEN") ? null : token;
    }
}
