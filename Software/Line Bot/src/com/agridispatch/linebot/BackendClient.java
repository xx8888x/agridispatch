package com.agridispatch.linebot;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 智農派工 - 呼叫後端內部端點（規格 05 第 6.3 節，純 Java 零依賴）
 *   POST {BACKEND_API_BASE}/line/internal/identity      身分查詢
 *   POST {BACKEND_API_BASE}/line/internal/follow-state  追蹤狀態與語言
 *   POST {BACKEND_API_BASE}/line/internal/query         需要真實資料的查詢（本人訂單、天候、任務、薪資；費率、產曆）
 * 以 X-Internal-Key（AGRI_LINEBOT_INTERNAL_KEY）驗證，取代先前借用 channel secret 簽章的 /api/line/webhook。
 * 未設定金鑰、後端離線或拒絕時回傳 null，由呼叫端改用本地資料（IdentityStore 記憶體、本地卡片）。
 */
public final class BackendClient {
    private BackendClient() {}

    private static final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(1))
            .build();
    private static final AtomicBoolean warnedNoKey = new AtomicBoolean(false);

    /** 身分查詢；後端不可用時回傳 null */
    public static Identity identity(Channel channel, String userId) {
        String res = post("identity", "{\"channel\":\"" + channel.key + "\",\"lineUserId\":\"" + esc(userId) + "\"}");
        if (res == null) return null;
        Map<String, Object> m = SimpleJson.parseObject(res);
        boolean bound = Boolean.TRUE.equals(m.get("bound"));
        return new Identity(bound, str(m.get("role")), str(m.get("subRole")), str(m.get("nationality")),
                Boolean.TRUE.equals(m.get("isLeader")), I18n.normalize(str(m.get("lang"))), Boolean.TRUE.equals(m.get("langLocked")));
    }

    /**
     * 記錄追蹤狀態與語言；followed、lang 為 null 表示不變更。成功回傳 true。
     */
    public static boolean followState(Channel channel, String userId, Boolean followed, String lang, boolean langLocked) {
        StringBuilder body = new StringBuilder("{\"channel\":\"").append(channel.key)
                .append("\",\"lineUserId\":\"").append(esc(userId)).append("\"");
        if (followed != null) body.append(",\"followed\":").append(followed);
        if (lang != null) body.append(",\"lang\":\"").append(esc(lang)).append("\",\"langLocked\":").append(langLocked);
        body.append("}");
        return post("follow-state", body.toString()) != null;
    }

    /**
     * 查詢；回傳後端產生的 LINE 訊息物件 JSON 清單（可能為空，表示此帳號／身分沒有可回的資料）。後端不可用時回傳 null。
     */
    public static List<String> query(Channel channel, String userId, String action, String text) {
        String res = post("query", "{\"channel\":\"" + channel.key + "\",\"lineUserId\":\"" + esc(userId)
                + "\",\"action\":\"" + esc(action) + "\",\"text\":\"" + esc(text) + "\"}");
        return res == null ? null : arrayElements(res, "\"messages\"");
    }

    /**
     * 本人資料查詢（農場主 orders／weather，工作者 task／weather／salary）：回傳後端 data 物件；
     * 後端可用但沒有資料（未綁定、身分不符、功能關閉）回傳空 Map，後端不可用時回傳 null。
     */
    public static Map<String, Object> data(Channel channel, String userId, String action, String text) {
        String res = post("query", "{\"channel\":\"" + channel.key + "\",\"lineUserId\":\"" + esc(userId)
                + "\",\"action\":\"" + esc(action) + "\",\"text\":\"" + esc(text) + "\"}");
        if (res == null) return null;
        Object data = SimpleJson.parseObject(res).get("data");
        @SuppressWarnings("unchecked")
        Map<String, Object> m = data instanceof Map ? (Map<String, Object>) data : Map.of();
        return m;
    }

    private static String post(String path, String body) {
        if (Config.INTERNAL_KEY == null) {
            if (warnedNoKey.compareAndSet(false, true)) {
                System.out.println("⚠️ 未設定 AGRI_LINEBOT_INTERNAL_KEY（internal.key），不連後端：身分與語言暫存記憶體、查詢使用本地卡片。");
            }
            return null;
        }
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(Config.BACKEND_API_BASE + "/line/internal/" + path))
                    .timeout(Duration.ofSeconds(3))
                    .header("Content-Type", "application/json; charset=UTF-8")
                    .header("X-Internal-Key", Config.INTERNAL_KEY)
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                System.err.println("⚠️ 後端內部端點 " + path + " 失敗 [HTTP " + response.statusCode() + "]，改用本地資料");
                return null;
            }
            return response.body();
        } catch (Exception e) {
            System.err.println("⚠️ 後端無法連線 (" + Config.BACKEND_API_BASE + ")：" + e.getMessage() + "，改用本地資料");
            return null;
        }
    }

    /** 取出 JSON 中指定陣列欄位的每個物件元素原文；找不到欄位回傳空清單 */
    static List<String> arrayElements(String json, String key) {
        List<String> out = new ArrayList<>();
        int k = json.indexOf(key);
        if (k < 0) return out;
        int i = json.indexOf('[', k);
        if (i < 0) return out;
        int depth = 0, start = -1;
        boolean inString = false;
        for (int j = i + 1; j < json.length(); j++) {
            char c = json.charAt(j);
            if (inString) {
                if (c == '\\') j++;
                else if (c == '"') inString = false;
            } else if (c == '"') {
                inString = true;
            } else if (c == '{') {
                if (depth++ == 0) start = j;
            } else if (c == '}') {
                if (--depth == 0 && start >= 0) out.add(json.substring(start, j + 1));
            } else if (c == ']' && depth == 0) {
                break;
            }
        }
        return out;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static String esc(String s) {
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
