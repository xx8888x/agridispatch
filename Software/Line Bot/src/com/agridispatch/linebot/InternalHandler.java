package com.agridispatch.linebot;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.Set;

/**
 * 智農派工 - Line Bot 內部端點（後端呼叫，規格 05 第 6.3 節）
 *   POST /internal/richmenu/link  {channel, lineUserId, menuKey}
 * 以 X-Internal-Key（AGRI_LINEBOT_INTERNAL_KEY）constant-time 比對；未設定金鑰時整組停用（403）。
 *
 * P2：驗證並記錄請求；各選單的 richMenuId 於 P3（規格第捌章）建立後才實際呼叫 LINE API 連結，
 * 在此之前回傳 linked:false。/internal/push 於 P5 加入。
 */
public class InternalHandler implements HttpHandler {

    static final Set<String> MENU_KEYS = Set.of("farmer_guest", "farmer_main", "worker_guest",
            "worker_main_zh", "worker_main_vi", "worker_main_id");

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            HttpUtils.sendText(exchange, 405, "Method Not Allowed");
            return;
        }
        if (Config.INTERNAL_KEY == null) {
            HttpUtils.sendJson(exchange, 403, "{\"success\":false,\"error\":\"內部端點未啟用：請設定 AGRI_LINEBOT_INTERNAL_KEY\"}");
            return;
        }
        String provided = exchange.getRequestHeaders().getFirst("X-Internal-Key");
        if (provided == null || !MessageDigest.isEqual(Config.INTERNAL_KEY.getBytes(StandardCharsets.UTF_8),
                provided.trim().getBytes(StandardCharsets.UTF_8))) {
            HttpUtils.sendJson(exchange, 403, "{\"success\":false,\"error\":\"FORBIDDEN\"}");
            return;
        }

        String path = exchange.getRequestURI().getPath().replaceAll("/+$", "");
        if (!"/internal/richmenu/link".equals(path)) {
            HttpUtils.sendJson(exchange, 404, "{\"success\":false,\"error\":\"NOT_FOUND\"}");
            return;
        }

        Map<String, Object> f = SimpleJson.parseObject(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        Channel channel = Channel.fromKey(f.get("channel") == null ? null : String.valueOf(f.get("channel")));
        String userId = f.get("lineUserId") == null ? "" : String.valueOf(f.get("lineUserId"));
        String menuKey = f.get("menuKey") == null ? "" : String.valueOf(f.get("menuKey"));
        if (channel == null || userId.isBlank() || !MENU_KEYS.contains(menuKey)
                || !menuKey.startsWith(channel.key + "_")) {
            HttpUtils.sendJson(exchange, 400, "{\"success\":false,\"error\":\"channel、lineUserId 或 menuKey 不正確\"}");
            return;
        }

        System.out.println("🎛️ [" + channel.key + "] 圖文選單切換請求：" + WebhookHandler.maskUserId(userId) + " → " + menuKey
                + "（P3 建立選單後才會實際連結）");
        HttpUtils.sendJson(exchange, 200, "{\"success\":true,\"linked\":false,\"menuKey\":\"" + menuKey + "\"}");
    }
}
