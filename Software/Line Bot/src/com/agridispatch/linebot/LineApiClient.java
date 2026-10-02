package com.agridispatch.linebot;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 智農派工 - LINE Messaging API HTTP 客戶端 (純 Java 零依賴)
 * 每個官方帳號一個實例，使用該帳號自己的 channel access token。
 * 支援 Reply (回覆)、Push (推播)、Broadcast (僅農場主帳號，規格第柒章)、Get Profile 與 per-user 圖文選單連結。
 */
public class LineApiClient {
    private static final String REPLY_URL = "https://api.line.me/v2/bot/message/reply";
    private static final String PUSH_URL = "https://api.line.me/v2/bot/message/push";
    private static final String BROADCAST_URL = "https://api.line.me/v2/bot/message/broadcast";
    private static final String PROFILE_URL = "https://api.line.me/v2/bot/profile/";
    private static final String USER_URL = "https://api.line.me/v2/bot/user/";

    private static final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    private final Channel channel;

    public LineApiClient(Channel channel) {
        this.channel = channel;
    }

    /**
     * 回覆訊息 (Reply Message，不計推播則數)；messages 為 LINE 訊息物件 JSON，最多 5 則
     */
    public boolean replyMessages(String replyToken, List<String> messages) {
        if (messages.isEmpty()) return true;
        String joined = String.join(",", messages);
        if (channel.isMock()) {
            System.out.println("🤖 [模擬模式・" + channel.key + "] 回覆 " + messages.size() + " 則訊息 (ReplyToken: " + replyToken + "):");
            System.out.println(joined);
            return true;
        }
        return sendPost(REPLY_URL, "{\"replyToken\":\"" + replyToken + "\",\"messages\":[" + joined + "]}");
    }

    /**
     * 主動推播訊息 (Push Message)
     */
    public boolean pushMessage(String toUserId, String messageJson) {
        if (channel.isMock()) {
            System.out.println("🤖 [模擬模式・" + channel.key + "] 推播訊息至 " + toUserId + ":");
            System.out.println(messageJson);
            return true;
        }
        return sendPost(PUSH_URL, "{\"to\":\"" + toUserId + "\",\"messages\":[" + messageJson + "]}");
    }

    /**
     * 全員廣播訊息 (Broadcast)：只允許農場主帳號用於方案行銷；工作者帳號的工作通知只能推給名單上的人（規格第柒章第 1 點）
     */
    public boolean broadcast(String messageJson) {
        if (channel == Channel.WORKER) {
            System.err.println("❌ 工作者帳號禁止 broadcast，請改用 push／multicast 推給出工名單");
            return false;
        }
        if (channel.isMock()) {
            System.out.println("📢 [模擬模式・" + channel.key + "] 全員廣播訊息:");
            System.out.println(messageJson);
            return true;
        }
        return sendPost(BROADCAST_URL, "{\"messages\":[" + messageJson + "]}");
    }

    /**
     * 取得使用者的 LINE 介面語言（Get Profile 的 language 欄位；使用者未同意 LINE 隱私政策時沒有此欄位）。
     * Mock 模式或失敗時回傳 null。
     */
    @SuppressWarnings("unchecked")
    public String getProfileLanguage(String userId) {
        if (channel.isMock() || userId == null || userId.isBlank()) return null;
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(PROFILE_URL + URLEncoder.encode(userId, StandardCharsets.UTF_8)))
                    .timeout(Duration.ofSeconds(3))
                    .header("Authorization", "Bearer " + channel.accessToken())
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) return null;
            Object lang = SimpleJson.parseObject(response.body()).get("language");
            return lang == null ? null : String.valueOf(lang);
        } catch (Exception e) {
            System.err.println("⚠️ 取得 LINE profile 失敗 (" + channel.key + "): " + e.getMessage());
            return null;
        }
    }

    /** 把使用者連結到指定圖文選單（per-user rich menu） */
    public boolean linkRichMenu(String userId, String richMenuId) {
        return send("POST", USER_URL + URLEncoder.encode(userId, StandardCharsets.UTF_8) + "/richmenu/"
                + URLEncoder.encode(richMenuId, StandardCharsets.UTF_8), null);
    }

    /** 解除使用者的個人圖文選單，回到帳號預設選單 */
    public boolean unlinkRichMenu(String userId) {
        return send("DELETE", USER_URL + URLEncoder.encode(userId, StandardCharsets.UTF_8) + "/richmenu", null);
    }

    private boolean sendPost(String endpoint, String jsonBody) {
        return send("POST", endpoint, jsonBody);
    }

    private boolean send(String method, String endpoint, String jsonBody) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Bearer " + channel.accessToken());
            if (jsonBody != null) {
                b.header("Content-Type", "application/json; charset=UTF-8")
                 .method(method, HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8));
            } else {
                b.method(method, HttpRequest.BodyPublishers.noBody());
            }
            HttpRequest request = b.build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return true;
            }
            System.err.println("❌ LINE API 呼叫失敗 (" + channel.key + ") [HTTP " + response.statusCode() + "]: " + response.body());
            return false;
        } catch (Exception e) {
            System.err.println("❌ 發送請求異常 (" + channel.key + "): " + e.getMessage());
            return false;
        }
    }

    /** 每個帳號共用一個客戶端 */
    private static final Map<Channel, LineApiClient> CLIENTS = Map.of(
            Channel.FARMER, new LineApiClient(Channel.FARMER),
            Channel.WORKER, new LineApiClient(Channel.WORKER));

    public static LineApiClient of(Channel channel) {
        return CLIENTS.get(channel);
    }
}
