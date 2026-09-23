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
 * 智農派工 - LINE Webhook 處理器
 * 負責校驗 X-Line-Signature (HMAC-SHA256)、解析 Event、並調用 Dispatcher 產出回覆
 */
public class WebhookHandler implements HttpHandler {
    private final LineApiClient lineClient = new LineApiClient();

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod().toUpperCase();
        String path = exchange.getRequestURI().getPath();

        // CORS 白名單 (取代先前的 Access-Control-Allow-Origin: *)
        // /callback、/webhook 為 LINE 伺服器對伺服器呼叫，非瀏覽器，CORS 與其無關，套用白名單無副作用
        HttpUtils.applyCors(exchange, "GET, POST, OPTIONS", "Content-Type, X-Line-Signature");

        if ("OPTIONS".equals(method)) {
            exchange.sendResponseHeaders(204, -1);
            return;
        }

        if (!"POST".equals(method)) {
            sendResponse(exchange, 405, "Method Not Allowed");
            return;
        }

        // 讀取請求內容
        byte[] bodyBytes = exchange.getRequestBody().readAllBytes();
        String body = new String(bodyBytes, StandardCharsets.UTF_8);

        // 判斷是否為本地除錯路徑 /mock/chat；此路徑僅供本地離線模擬使用，正式金鑰模式一律停用
        if (path.endsWith("/mock/chat")) {
            if (!Config.IS_MOCK_MODE) {
                sendResponse(exchange, 403, "Forbidden: /mock/chat 僅於本地模擬模式 (Mock Mode) 開放");
                return;
            }
            handleMockChat(exchange, body);
            return;
        }

        // 驗證 LINE Webhook HMAC-SHA256 簽章
        String signature = exchange.getRequestHeaders().getFirst("X-Line-Signature");
        if (!verifySignature(bodyBytes, signature)) {
            System.err.println("⚠️ 簽章驗證失敗 (Invalid X-Line-Signature)");
            sendResponse(exchange, 403, "Forbidden: Invalid Signature");
            return;
        }

        // 解析 Webhook 事件
        try {
            processEvents(body);
            sendResponse(exchange, 200, "OK");
        } catch (Exception e) {
            System.err.println("處理 Webhook 事件異常: " + e.getMessage());
            e.printStackTrace();
            sendResponse(exchange, 500, "Internal Server Error");
        }
    }

    /**
     * 驗證 HMAC-SHA256 簽章 (constant-time 比對，避免逐位元比對造成的時間側錄攻擊)
     */
    private boolean verifySignature(byte[] bodyBytes, String signature) {
        if (Config.IS_MOCK_MODE) {
            return true; // 模擬模式允許通過
        }
        if (signature == null || Config.CHANNEL_SECRET == null || Config.CHANNEL_SECRET.isBlank()) {
            return false;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec secretKey = new SecretKeySpec(Config.CHANNEL_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(secretKey);
            byte[] hash = mac.doFinal(bodyBytes);
            byte[] expectedBytes = Base64.getEncoder().encode(hash);
            byte[] providedBytes = signature.getBytes(StandardCharsets.UTF_8);
            return MessageDigest.isEqual(expectedBytes, providedBytes);
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    /**
     * 解析 LINE Webhook Events：body 為 { "events": [ {...}, {...} ] } 結構，
     * 使用 SimpleJson 正確解析整個陣列，逐一處理「每一筆」事件 (先前僅用全域 Regex 抓第一筆，會漏掉其餘事件)。
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
            if (!(evObj instanceof Map)) {
                continue;
            }
            try {
                handleSingleEvent((Map<String, Object>) evObj);
            } catch (Exception e) {
                // 單筆事件處理失敗不應影響其餘事件
                System.err.println("處理單筆 Webhook 事件時發生例外: " + e.getMessage());
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void handleSingleEvent(Map<String, Object> event) {
        String type = String.valueOf(event.getOrDefault("type", ""));
        if (!"message".equals(type)) {
            return; // 目前僅處理文字訊息事件，follow/unfollow/postback 等其他事件類型暫略過
        }

        Object messageObj = event.get("message");
        if (!(messageObj instanceof Map)) {
            return;
        }
        Map<String, Object> message = (Map<String, Object>) messageObj;
        if (!"text".equals(String.valueOf(message.getOrDefault("type", "")))) {
            return; // 目前僅支援文字訊息，貼圖/圖片/位置等訊息類型暫略過
        }
        String text = String.valueOf(message.getOrDefault("text", ""));

        Object replyTokenObj = event.get("replyToken");
        String replyToken = replyTokenObj != null ? String.valueOf(replyTokenObj) : "";

        String userId = "U_DEFAULT";
        Object sourceObj = event.get("source");
        if (sourceObj instanceof Map) {
            Object uid = ((Map<String, Object>) sourceObj).get("userId");
            if (uid != null) {
                userId = String.valueOf(uid);
            }
        }

        System.out.println("📩 收到 LINE 訊息: [使用者: " + userId + "] 內容: " + text);

        if (!text.isEmpty() && !replyToken.isEmpty()) {
            String replyMessageJson = LineBotDispatcher.dispatch(userId, text);
            lineClient.replyMessage(replyToken, replyMessageJson);
        }
    }

    /**
     * 本地除錯模擬發言 API
     */
    @SuppressWarnings("unchecked")
    private void handleMockChat(HttpExchange exchange, String body) throws IOException {
        String trimmed = body == null ? "" : body.trim();
        String text;
        if (trimmed.startsWith("{")) {
            Object parsed = SimpleJson.parse(trimmed);
            Object t = (parsed instanceof Map) ? ((Map<String, Object>) parsed).get("text") : null;
            text = t != null ? String.valueOf(t) : "";
        } else {
            text = trimmed;
        }

        String replyJson = LineBotDispatcher.dispatch("U_MOCK_USER", text);
        byte[] respBytes = replyJson.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(200, respBytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(respBytes);
        }
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
