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
 * 支援 Reply (回覆)、Push (推播)、Broadcast (僅農場主帳號，規格第柒章)、Get Profile、per-user 圖文選單連結，
 * 以及語音訊息用的訊息內容下載與「處理中」動畫（規格 5.5）。
 */
public class LineApiClient {
    private static final String REPLY_URL = "https://api.line.me/v2/bot/message/reply";
    private static final String PUSH_URL = "https://api.line.me/v2/bot/message/push";
    private static final String BROADCAST_URL = "https://api.line.me/v2/bot/message/broadcast";
    private static final String MULTICAST_URL = "https://api.line.me/v2/bot/message/multicast";
    /** Multicast 一次最多 500 人（LINE 規定） */
    public static final int MULTICAST_MAX = 500;
    private static final String PROFILE_URL = "https://api.line.me/v2/bot/profile/";
    private static final String USER_URL = "https://api.line.me/v2/bot/user/";
    private static final String LOADING_URL = "https://api.line.me/v2/bot/chat/loading/start";
    /** 使用者傳來的檔案內容在 api-data 網域 */
    private static final String CONTENT_URL = "https://api-data.line.me/v2/bot/message/";

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
     * 多人推播 (Multicast)：自動每 500 人一批；每批失敗重試 1 次（規格第柒章第 5 點）。回傳成功送達的人數。
     */
    public int multicast(java.util.List<String> to, java.util.List<String> messages) {
        if (to.isEmpty() || messages.isEmpty()) return 0;
        String msgs = String.join(",", messages.size() > 5 ? messages.subList(0, 5) : messages);
        int ok = 0;
        for (int i = 0; i < to.size(); i += MULTICAST_MAX) {
            java.util.List<String> batch = to.subList(i, Math.min(to.size(), i + MULTICAST_MAX));
            if (channel.isMock()) {
                System.out.println("📨 [模擬模式・" + channel.key + "] multicast " + batch.size() + " 人：" + msgs.length() + " 字元");
                ok += batch.size();
                continue;
            }
            StringBuilder ids = new StringBuilder();
            for (String id : batch) {
                if (ids.length() > 0) ids.append(",");
                ids.append("\"").append(id.replace("\"", "")).append("\"");
            }
            String payload = "{\"to\":[" + ids + "],\"messages\":[" + msgs + "]}";
            if (sendPost(MULTICAST_URL, payload) || sendPost(MULTICAST_URL, payload)) ok += batch.size();
        }
        return ok;
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

    /** 聊天室顯示「處理中」動畫（只限一對一聊天；seconds 須為 5 的倍數、5～60），收到回覆時自動消失 */
    public boolean showLoading(String userId, int seconds) {
        if (channel.isMock()) return true;
        return sendPost(LOADING_URL, "{\"chatId\":\"" + userId.replace("\"", "") + "\",\"loadingSeconds\":" + seconds + "}");
    }

    /**
     * 下載使用者傳來的檔案（語音、圖片等）內容；Mock 模式、失敗或超過 maxBytes 時回傳 null。
     * LINE 對影音檔可能先回 202（轉檔中），稍候重試。
     */
    public byte[] getMessageContent(String messageId, int maxBytes) {
        if (channel.isMock() || messageId == null || !messageId.matches("[0-9A-Za-z_-]+")) return null;
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(CONTENT_URL + messageId + "/content"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Bearer " + channel.accessToken())
                    .GET()
                    .build();
            for (int attempt = 0; attempt < 3; attempt++) {
                HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
                if (response.statusCode() == 202) {
                    Thread.sleep(1000);
                    continue;
                }
                if (response.statusCode() != 200) {
                    System.err.println("❌ 下載訊息內容失敗 (" + channel.key + ") [HTTP " + response.statusCode() + "]");
                    return null;
                }
                byte[] body = response.body();
                if (body.length > maxBytes) {
                    System.err.println("⚠️ 訊息內容過大 (" + channel.key + ")：" + body.length + " bytes，略過");
                    return null;
                }
                return body;
            }
            System.err.println("⚠️ 訊息內容仍在轉檔中 (" + channel.key + ")，放棄下載");
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            System.err.println("❌ 下載訊息內容異常 (" + channel.key + "): " + e.getMessage());
            return null;
        }
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
