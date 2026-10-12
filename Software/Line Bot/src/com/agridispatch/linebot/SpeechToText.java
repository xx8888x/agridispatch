package com.agridispatch.linebot;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 智農派工 - 工作者帳號語音訊息（規格 5.5）
 * 下載 LINE 語音檔（m4a）→ OpenAI Whisper（/audio/transcriptions）轉文字 → 以 VOICE 事件交給分派器，
 * 分派器先請使用者確認辨識結果，不直接執行。
 *
 * 辨識要數秒，webhook 收到語音後先回 200，在背景執行緒辨識並以 reply token 回覆（逾時改推播）。
 * 音檔只在記憶體中轉送，不落地、不保存。
 */
final class SpeechToText {
    private SpeechToText() {}

    /** 語音長度上限（秒）：超過不送辨識，控制費用 */
    static final int MAX_SECONDS = 60;
    /** 音檔大小上限：60 秒 m4a 約 0.5～1 MB，留餘裕 */
    static final int MAX_BYTES = 5 * 1024 * 1024;

    private static final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private static final ExecutorService POOL = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "voice-stt");
        t.setDaemon(true);
        return t;
    });

    /** 功能開關打開且有 API 金鑰才啟用 */
    static boolean enabled() {
        return Config.FEATURE_WORKER_VOICE && Config.STT_API_KEY != null;
    }

    /** webhook 收到語音訊息：背景辨識後回覆 */
    static void handleAsync(Channel channel, String userId, String messageId, long durationMs, String replyToken) {
        POOL.execute(() -> {
            try {
                BotEvent ev = toEvent(channel, userId, messageId, durationMs);
                System.out.println("📩 [" + channel.key + "] VOICE 使用者: " + WebhookHandler.maskUserId(userId)
                        + (ev.voiceError() != null ? " 未辨識: " + ev.voiceError() : " 內容: " + ev.text()));
                List<String> replies = LineBotDispatcher.handle(ev);
                if (replies.isEmpty()) return;
                LineApiClient api = LineApiClient.of(channel);
                if (replyToken == null || !api.replyMessages(replyToken, replies)) {
                    // reply token 逾時（辨識太久）時改推播，避免使用者以為沒收到
                    api.pushMessage(userId, String.join(",", replies));
                }
            } catch (Exception e) {
                System.err.println("處理語音訊息時發生例外 (" + channel.key + "): " + e.getMessage());
            }
        });
    }

    /** 語音訊息 → VOICE 事件（含下載與辨識） */
    static BotEvent toEvent(Channel channel, String userId, String messageId, long durationMs) {
        Identity me = IdentityStore.get(channel, userId);
        // 外籍移工功能下架：不送辨識，分派器直接回暫停說明
        if (!Config.FEATURE_FOREIGN_WORKERS && me.isForeign()) return BotEvent.voice(channel, userId, "", null);
        if (durationMs > MAX_SECONDS * 1000L) return BotEvent.voice(channel, userId, "", BotEvent.VOICE_TOO_LONG);

        LineApiClient api = LineApiClient.of(channel);
        api.showLoading(userId, 20);
        byte[] audio = api.getMessageContent(messageId, MAX_BYTES);
        String text = audio == null ? null : transcribe(audio, Config.FEATURE_FOREIGN_WORKERS ? me.lang() : "zh");
        return text == null || text.isBlank()
                ? BotEvent.voice(channel, userId, "", BotEvent.VOICE_FAILED)
                : BotEvent.voice(channel, userId, text, null);
    }

    /**
     * 呼叫 Whisper 轉文字；lang 為 zh／vi／id（同時當作 Whisper 的 language 參數）。失敗回傳 null。
     * prompt 帶入工作者常用詞（i18n voice.prompt），提高關鍵字辨識率，並讓中文輸出偏向繁體。
     */
    static String transcribe(byte[] audio, String lang) {
        if (Config.STT_API_KEY == null) return null;
        String l = I18n.normalize(lang);
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("model", Config.STT_MODEL);
        fields.put("language", l);
        fields.put("prompt", I18n.t(l, "voice.prompt"));
        fields.put("response_format", "json");
        String boundary = "----AgriVoice" + UUID.randomUUID().toString().replace("-", "");
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(Config.STT_API_URL))
                    .timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Bearer " + Config.STT_API_KEY)
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(multipart(boundary, fields, "voice.m4a", "audio/mp4", audio)))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                String body = response.body() == null ? "" : response.body();
                System.err.println("❌ 語音辨識失敗 [HTTP " + response.statusCode() + "]: "
                        + (body.length() > 200 ? body.substring(0, 200) : body));
                return null;
            }
            Object t = SimpleJson.parseObject(response.body()).get("text");
            String text = t == null ? "" : String.valueOf(t).trim();
            if ("zh".equals(l)) text = toTraditional(text);
            if (isPromptEcho(text, l)) {
                System.err.println("⚠️ 語音辨識結果與提示詞相同（多半是靜音或雜音），視為未辨識");
                return null;
            }
            return text;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            System.err.println("❌ 語音辨識異常: " + e.getMessage());
            return null;
        }
    }

    /**
     * Whisper 遇到靜音或雜音時，常把提示詞原樣吐回來：結果含 4 個以上提示詞裡的詞就視為未辨識
     * （正常人一句話不會同時講到 4 項功能）。
     */
    static boolean isPromptEcho(String text, String lang) {
        String prompt = I18n.t(lang, "voice.prompt");
        int colon = Math.max(prompt.indexOf('：'), prompt.indexOf(':'));
        String lower = text.toLowerCase();
        int hits = 0;
        for (String word : prompt.substring(colon + 1).split("[、,，。.]")) {
            String w = word.trim().toLowerCase();
            if (!w.isEmpty() && lower.contains(w)) hits++;
        }
        return hits >= 4;
    }

    /** multipart/form-data：文字欄位＋一個名為 file 的檔案欄位 */
    static byte[] multipart(String boundary, Map<String, String> fields, String fileName, String contentType, byte[] file) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(file.length + 1024);
        StringBuilder head = new StringBuilder();
        for (Map.Entry<String, String> f : fields.entrySet()) {
            head.append("--").append(boundary).append("\r\n")
                .append("Content-Disposition: form-data; name=\"").append(f.getKey()).append("\"\r\n\r\n")
                .append(f.getValue()).append("\r\n");
        }
        head.append("--").append(boundary).append("\r\n")
            .append("Content-Disposition: form-data; name=\"file\"; filename=\"").append(fileName).append("\"\r\n")
            .append("Content-Type: ").append(contentType).append("\r\n\r\n");
        out.writeBytes(head.toString().getBytes(StandardCharsets.UTF_8));
        out.writeBytes(file);
        out.writeBytes(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }

    // Whisper 的中文輸出有時是簡體：只轉關鍵字（WorkerDispatcher.actionOf）與常用字，讓關鍵字比對得到；不是完整的繁簡轉換
    private static final String SIMP = "换语机无单迹鸟预购优时转费价钱计报订约喷产气险风驳专车务资贴奖励纪录绑账帐号联络体说请这个们吗还让给点几发领师么";
    private static final String TRAD = "換語機無單跡鳥預購優時轉費價錢計報訂約噴產氣險風駁專車務資貼獎勵紀錄綁帳帳號聯絡體說請這個們嗎還讓給點幾發領師麼";

    static String toTraditional(String s) {
        if (s == null || s.isEmpty()) return s;
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            int k = SIMP.indexOf(c);
            sb.append(k < 0 ? c : TRAD.charAt(k));
        }
        return sb.toString().replace("台風", "颱風");
    }
}
