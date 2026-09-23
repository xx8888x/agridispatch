package com.agridispatch.linebot;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

        // 允許 CORS 跨域 (方便前端體驗館呼叫)
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, X-Line-Signature");

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

        // 判斷是否為本地除錯路徑 /mock/chat
        if (path.endsWith("/mock/chat")) {
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
     * 驗證 HMAC-SHA256 簽章
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
            String expected = Base64.getEncoder().encodeToString(hash);
            return expected.equals(signature);
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    /**
     * 解析 LINE Webhook Events
     */
    private void processEvents(String body) {
        // 簡易 Regex 提取 text, replyToken, userId
        Pattern textPattern = Pattern.compile("\"text\"\\s*:\\s*\"([^\"]+)\"");
        Pattern replyTokenPattern = Pattern.compile("\"replyToken\"\\s*:\\s*\"([^\"]+)\"");
        Pattern userIdPattern = Pattern.compile("\"userId\"\\s*:\\s*\"([^\"]+)\"");

        Matcher textMatcher = textPattern.matcher(body);
        Matcher tokenMatcher = replyTokenPattern.matcher(body);
        Matcher userMatcher = userIdPattern.matcher(body);

        String text = textMatcher.find() ? textMatcher.group(1) : "";
        String replyToken = tokenMatcher.find() ? tokenMatcher.group(1) : "";
        String userId = userMatcher.find() ? userMatcher.group(1) : "U_DEFAULT";

        System.out.println("📩 收到 LINE 訊息: [使用者: " + userId + "] 內容: " + text);

        if (!text.isEmpty() && !replyToken.isEmpty()) {
            String replyMessageJson = LineBotDispatcher.dispatch(userId, text);
            lineClient.replyMessage(replyToken, replyMessageJson);
        }
    }

    /**
     * 本地除錯模擬發言 API
     */
    private void handleMockChat(HttpExchange exchange, String body) throws IOException {
        Pattern textPattern = Pattern.compile("\"text\"\\s*:\\s*\"([^\"]+)\"");
        Matcher textMatcher = textPattern.matcher(body);
        String text = textMatcher.find() ? textMatcher.group(1) : (body.startsWith("{") ? "" : body.trim());

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
