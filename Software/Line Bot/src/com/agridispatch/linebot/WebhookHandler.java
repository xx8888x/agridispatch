package com.agridispatch.linebot;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * 智農派工 - LINE Webhook 處理器（每個官方帳號一個實例）
 * 以該帳號的 channel secret 校驗 X-Line-Signature (HMAC-SHA256)、解析 Event、交給 LineBotDispatcher 產出回覆。
 *
 * 路徑（規格 6.2）：/callback/farmer、/callback/worker；舊的 /callback、/webhook 過渡期視同農場主帳號。
 */
public class WebhookHandler implements HttpHandler {
    private final Channel channel;
    private final boolean legacyPath;

    public WebhookHandler(Channel channel, boolean legacyPath) {
        this.channel = channel;
        this.legacyPath = legacyPath;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod().toUpperCase();

        // /callback/* 為 LINE 伺服器對伺服器呼叫，非瀏覽器，CORS 與其無關，套用白名單無副作用
        HttpUtils.applyCors(exchange, "GET, POST, OPTIONS", "Content-Type, X-Line-Signature");

        if ("OPTIONS".equals(method)) {
            exchange.sendResponseHeaders(204, -1);
            return;
        }
        if (!"POST".equals(method)) {
            sendResponse(exchange, 405, "Method Not Allowed");
            return;
        }
        if (legacyPath) {
            String path = exchange.getRequestURI().getPath().replaceAll("/+$", "");
            if (!"/callback".equals(path) && !"/webhook".equals(path)) {
                sendResponse(exchange, 404, "Not Found: 請使用 /callback/farmer 或 /callback/worker");
                return;
            }
            System.err.println("⚠️ 收到舊路徑 " + exchange.getRequestURI().getPath()
                    + " 的 webhook，已視為農場主帳號；請在 LINE Developers 改設 /callback/farmer 或 /callback/worker");
        }

        byte[] bodyBytes = exchange.getRequestBody().readAllBytes();
        String body = new String(bodyBytes, StandardCharsets.UTF_8);

        String signature = exchange.getRequestHeaders().getFirst("X-Line-Signature");
        if (!verifySignature(bodyBytes, signature)) {
            System.err.println("⚠️ 簽章驗證失敗 (" + channel.key + "，Invalid X-Line-Signature)");
            sendResponse(exchange, 403, "Forbidden: Invalid Signature");
            return;
        }

        try {
            processEvents(body);
            sendResponse(exchange, 200, "OK");
        } catch (Exception e) {
            System.err.println("處理 Webhook 事件異常 (" + channel.key + "): " + e.getMessage());
            sendResponse(exchange, 500, "Internal Server Error");
        }
    }

    /**
     * 以本帳號的 channel secret 驗證 HMAC-SHA256 簽章 (constant-time 比對)；Mock 模式允許通過
     */
    private boolean verifySignature(byte[] bodyBytes, String signature) {
        if (channel.isMock()) {
            return true;
        }
        if (signature == null || channel.secret().isBlank()) {
            return false;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(channel.secret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] expectedBytes = Base64.getEncoder().encode(mac.doFinal(bodyBytes));
            return MessageDigest.isEqual(expectedBytes, signature.trim().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 解析 { "events": [ {...}, {...} ] }，逐一處理每一筆事件；單筆失敗不影響其餘事件
     */
    @SuppressWarnings("unchecked")
    private void processEvents(String body) {
        Object root = SimpleJson.parse(body);
        if (!(root instanceof Map)) {
            System.err.println("⚠️ Webhook body 非合法 JSON 物件，略過處理");
            return;
        }
        Object eventsObj = ((Map<String, Object>) root).get("events");
        if (!(eventsObj instanceof List)) {
            return; // 無 events 陣列 (例如 LINE Verify 測試請求) 時安靜略過
        }
        for (Object evObj : (List<Object>) eventsObj) {
            if (!(evObj instanceof Map)) continue;
            try {
                handleSingleEvent((Map<String, Object>) evObj);
            } catch (Exception e) {
                System.err.println("處理單筆 Webhook 事件時發生例外 (" + channel.key + "): " + e.getMessage());
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void handleSingleEvent(Map<String, Object> event) {
        String userId = null;
        if (event.get("source") instanceof Map<?, ?> source && source.get("userId") != null) {
            userId = String.valueOf(source.get("userId"));
        }
        if (userId == null) return; // 群組／聊天室事件不處理

        // 工作者帳號語音（規格 5.5）：辨識要數秒，改在背景處理並回覆，webhook 先回 200
        if (channel == Channel.WORKER && SpeechToText.enabled()
                && event.get("message") instanceof Map<?, ?> m && "audio".equals(String.valueOf(m.get("type")))) {
            long duration = m.get("duration") instanceof Number n ? n.longValue() : 0L;
            Object token = event.get("replyToken");
            SpeechToText.handleAsync(channel, userId, String.valueOf(m.get("id")), duration,
                    token == null ? null : String.valueOf(token));
            return;
        }

        BotEvent ev = toBotEvent(channel, userId, event);
        if (ev == null) return;

        System.out.println("📩 [" + channel.key + "] " + ev.type() + " 使用者: " + maskUserId(userId)
                + (ev.type() == BotEvent.Type.TEXT ? " 內容: " + ev.text() : "")
                + (ev.type() == BotEvent.Type.POSTBACK ? " 動作: " + ev.action() : ""));

        List<String> replies = LineBotDispatcher.handle(ev);
        Object replyToken = event.get("replyToken");
        if (replyToken != null && !replies.isEmpty()) {
            LineApiClient.of(channel).replyMessages(String.valueOf(replyToken), replies);
        }
    }

    /** LINE 事件 → BotEvent；不處理的事件類型回傳 null */
    @SuppressWarnings("unchecked")
    static BotEvent toBotEvent(Channel channel, String userId, Map<String, Object> event) {
        String type = String.valueOf(event.getOrDefault("type", ""));
        switch (type) {
            case "follow":
                // 工作者帳號依 LINE 介面語言預設越語／印尼語（規格 3.2）；P2 起改由後端記錄
                String profileLang = channel == Channel.WORKER ? LineApiClient.of(channel).getProfileLanguage(userId) : null;
                return BotEvent.follow(channel, userId, profileLang);
            case "unfollow":
                return BotEvent.of(channel, userId, BotEvent.Type.UNFOLLOW);
            case "postback":
                Object pb = event.get("postback");
                String data = pb instanceof Map ? String.valueOf(((Map<String, Object>) pb).getOrDefault("data", "")) : "";
                return BotEvent.postback(channel, userId, data);
            case "message":
                Object msg = event.get("message");
                if (msg instanceof Map && "text".equals(String.valueOf(((Map<String, Object>) msg).get("type")))) {
                    return BotEvent.text(channel, userId, String.valueOf(((Map<String, Object>) msg).getOrDefault("text", "")));
                }
                return BotEvent.of(channel, userId, BotEvent.Type.OTHER_MESSAGE);
            default:
                return null;
        }
    }

    /** LINE userId 視為個資：log 只印前 6 碼（規格第拾章第 2 點） */
    static String maskUserId(String userId) {
        if (userId == null) return "";
        return userId.length() <= 6 ? "******" : userId.substring(0, 6) + "****";
    }

    private void sendResponse(HttpExchange exchange, int status, String message) throws IOException {
        byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=UTF-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
