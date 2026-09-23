package com.agridispatch.linebot;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 智農派工 - HTTP 共用工具方法
 * 提供 CORS 白名單套用、管理金鑰驗證 (constant-time 比對) 與統一的回應輸出方法，
 * 供 WebhookHandler、LineBotServer 等多個 HttpHandler 共用，避免各處重複實作且標準不一。
 */
public final class HttpUtils {
    private HttpUtils() {}

    /**
     * 依白名單 (Config.ALLOWED_ORIGINS) 設定 CORS 回應標頭。
     * 若請求的 Origin 不在白名單內，則不加註 Access-Control-Allow-Origin，
     * 瀏覽器會依同源政策擋下該跨域請求 (伺服器端仍會處理請求，僅瀏覽器端讀不到回應)。
     */
    public static void applyCors(HttpExchange exchange, String allowedMethods, String allowedHeaders) {
        String origin = exchange.getRequestHeaders().getFirst("Origin");
        if (origin != null && Config.ALLOWED_ORIGINS.contains(origin)) {
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", origin);
            exchange.getResponseHeaders().add("Vary", "Origin");
        }
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", allowedMethods);
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", allowedHeaders);
    }

    /**
     * 驗證管理端 API 的 X-Admin-Key 標頭是否與 Config.ADMIN_KEY 相符 (constant-time 比對，避免時間側錄攻擊)。
     * 若尚未設定管理金鑰 (Config.ADMIN_KEY 為 null)，一律回傳 false，呼叫端應將該 API 整體停用。
     * 注意：透過 localtunnel/ngrok 對外穿透時，請求的來源位址在本機端一律顯示為 127.0.0.1，
     * 因此「僅允許本機連線」並不能作為有效的保護機制，必須以此金鑰比對為準。
     */
    public static boolean isAdminAuthorized(HttpExchange exchange) {
        if (Config.ADMIN_KEY == null || Config.ADMIN_KEY.isBlank()) {
            return false;
        }
        String provided = exchange.getRequestHeaders().getFirst("X-Admin-Key");
        if (provided == null || provided.isEmpty()) {
            return false;
        }
        byte[] expected = Config.ADMIN_KEY.getBytes(StandardCharsets.UTF_8);
        byte[] actual = provided.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, actual);
    }

    public static void sendJson(HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    public static void sendText(HttpExchange exchange, int status, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=UTF-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
