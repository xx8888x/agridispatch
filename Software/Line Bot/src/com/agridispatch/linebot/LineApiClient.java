package com.agridispatch.linebot;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

/**
 * 智農派工 - LINE Messaging API HTTP 客戶端 (純 Java 零依賴)
 * 支援 Reply (回覆)、Push (推播) 與 Broadcast (廣播)
 */
public class LineApiClient {
    private static final String REPLY_URL = "https://api.line.me/v2/bot/message/reply";
    private static final String PUSH_URL = "https://api.line.me/v2/bot/message/push";
    private static final String BROADCAST_URL = "https://api.line.me/v2/bot/message/broadcast";

    private final HttpClient httpClient = HttpClient.newHttpClient();

    /**
     * 回覆訊息 (Reply Message)
     */
    public boolean replyMessage(String replyToken, String messageJson) {
        if (Config.IS_MOCK_MODE) {
            System.out.println("🤖 [模擬模式] 回覆訊息 (ReplyToken: " + replyToken + "):");
            System.out.println(messageJson);
            return true;
        }

        String payload = """
        {
          "replyToken": "%s",
          "messages": [%s]
        }
        """.formatted(replyToken, messageJson);

        return sendPost(REPLY_URL, payload);
    }

    /**
     * 主動推播訊息 (Push Message)
     */
    public boolean pushMessage(String toUserId, String messageJson) {
        if (Config.IS_MOCK_MODE) {
            System.out.println("🤖 [模擬模式] 推播訊息至 " + toUserId + ":");
            System.out.println(messageJson);
            return true;
        }

        String payload = """
        {
          "to": "%s",
          "messages": [%s]
        }
        """.formatted(toUserId, messageJson);

        return sendPost(PUSH_URL, payload);
    }

    /**
     * 全員廣播訊息 (Broadcast Message，如暴雨避險緊急通報)
     */
    public boolean broadcast(String messageJson) {
        if (Config.IS_MOCK_MODE) {
            System.out.println("📢 [模擬模式] 全員廣播訊息:");
            System.out.println(messageJson);
            return true;
        }

        String payload = """
        {
          "messages": [%s]
        }
        """.formatted(messageJson);

        return sendPost(BROADCAST_URL, payload);
    }

    private boolean sendPost(String endpoint, String jsonBody) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .header("Authorization", "Bearer " + Config.CHANNEL_ACCESS_TOKEN)
                    .header("Content-Type", "application/json; charset=UTF-8")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return true;
            } else {
                System.err.println("❌ LINE API 呼叫失敗 [HTTP " + response.statusCode() + "]: " + response.body());
                return false;
            }
        } catch (Exception e) {
            System.err.println("❌ 發送請求異常: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }
}
