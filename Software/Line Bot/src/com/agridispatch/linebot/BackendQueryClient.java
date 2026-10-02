package com.agridispatch.linebot;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

/**
 * 智農派工 - 後端即時查詢轉發 (純 Java 零依賴)
 * 農場主帳號的訂單、產曆、費率等需要真實資料的查詢由 Spring Boot 後端 (/api/line/webhook) 產生 Flex 卡片，
 * （工班名冊不再經 LINE 提供，規格 4.4）
 * 本類別把使用者訊息轉送過去並取回第一則回覆；後端離線或拒絕時回傳 null，由呼叫端改用本地卡片。
 */
public class BackendQueryClient {
    private static final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(1))
            .build();

    /**
     * 轉發文字訊息至後端，回傳 LINE 訊息物件 JSON（例：{"type":"flex",...}），失敗回傳 null
     */
    public static String query(String userId, String text) {
        String body = "{\"events\":[{\"type\":\"message\",\"source\":{\"userId\":\"" + escape(userId)
                + "\"},\"message\":{\"type\":\"text\",\"text\":\"" + escape(text) + "\"}}]}";
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(Config.BACKEND_API_BASE + "/line/webhook"))
                    .timeout(Duration.ofSeconds(3))
                    .header("Content-Type", "application/json; charset=UTF-8");
            // 過渡期（P1）：以農場主帳號的 channel secret 簽章，後端 demo 模式未設金鑰時不驗證；
            // P2 改呼叫 /api/line/internal/query 並以 X-Internal-Key 驗證（規格 6.3）
            String secret = Channel.FARMER.secret();
            if (!secret.isBlank()) builder.header("X-Line-Signature", sign(secret, body));
            HttpRequest request = builder.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                System.err.println("⚠️ 後端查詢失敗 [HTTP " + response.statusCode() + "]，改用本地卡片");
                return null;
            }
            return firstArrayElement(response.body(), "\"replies\"");
        } catch (Exception e) {
            System.err.println("⚠️ 後端查詢無法連線 (" + Config.BACKEND_API_BASE + ")：" + e.getMessage());
            return null;
        }
    }

    private static String sign(String secret, String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return Base64.getEncoder().encodeToString(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }

    /** 取出 JSON 中指定陣列欄位的第一個元素原文（含字串跳脫處理），找不到回傳 null */
    static String firstArrayElement(String json, String key) {
        if (json == null) return null;
        int k = json.indexOf(key);
        if (k < 0) return null;
        int start = json.indexOf('[', k);
        if (start < 0) return null;
        int i = start + 1;
        while (i < json.length() && Character.isWhitespace(json.charAt(i))) i++;
        if (i >= json.length() || json.charAt(i) != '{') return null;
        int depth = 0;
        boolean inString = false;
        for (int j = i; j < json.length(); j++) {
            char c = json.charAt(j);
            if (inString) {
                if (c == '\\') j++;
                else if (c == '"') inString = false;
            } else if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return json.substring(i, j + 1);
            }
        }
        return null;
    }

    private static String escape(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.toString();
    }
}
