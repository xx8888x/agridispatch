package com.agridispatch.service;

import com.agridispatch.config.AgriSettings;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 呼叫 LINE Bot（port 5000）的內部端點（規格 05 第 6.3 節）。
 * channel access token 只放在 LINE Bot；後端要切換使用者的圖文選單時請 LINE Bot 代為呼叫 LINE API。
 * 盡力而為：失敗只寫 log，不影響綁定結果（使用者下次互動時 LINE Bot 仍會依後端身分回覆）。
 */
@Component
public class LineBotClient {

    private static final Logger log = LoggerFactory.getLogger(LineBotClient.class);

    private final AgriSettings settings;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    public LineBotClient(AgriSettings settings, ObjectMapper objectMapper) {
        this.settings = settings;
        this.objectMapper = objectMapper;
    }

    /**
     * 讀取 LINE Bot 公開的 /health（兩帳號模式、選單是否已上傳、是否已設內部金鑰）；連不到時回傳 null
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> health() {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(settings.getLinebotBaseUrl() + "/health"))
                    .timeout(Duration.ofSeconds(2)).GET().build();
            HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return res.statusCode() == 200 ? objectMapper.readValue(res.body(), Map.class) : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 請 LINE Bot 依收件人語言產生通知並 multicast（POST /internal/notify）；連不到或失敗回傳 null
     */
    public Map<String, Object> notify(String channel, String type, Map<String, Object> params, List<Map<String, String>> recipients) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("channel", channel);
        body.put("type", type);
        body.put("params", params);
        body.put("recipients", recipients);
        return postInternal("/internal/notify", body, 15);
    }

    /** 農場主帳號方案行銷 broadcast（POST /internal/push，broadcast 只允許農場主帳號） */
    public Map<String, Object> broadcastFarmer(String text) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("channel", "farmer");
        body.put("broadcast", true);
        body.put("messages", List.of(Map.of("type", "text", "text", text)));
        return postInternal("/internal/push", body, 15);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> postInternal(String path, Map<String, Object> body, int timeoutSec) {
        String key = settings.getLinebotInternalKey();
        if (key == null) {
            log.info("[LINE] 未設定 AGRI_LINEBOT_INTERNAL_KEY，略過 {}", path);
            return null;
        }
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(settings.getLinebotBaseUrl() + path))
                    .timeout(Duration.ofSeconds(timeoutSec))
                    .header("Content-Type", "application/json; charset=UTF-8")
                    .header("X-Internal-Key", key)
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (res.statusCode() != 200) {
                log.warn("[LINE] LINE Bot {} 失敗 [HTTP {}]", path, res.statusCode());
                return null;
            }
            return objectMapper.readValue(res.body(), Map.class);
        } catch (Exception e) {
            log.warn("[LINE] 無法連線 LINE Bot（{}）{}：{}", settings.getLinebotBaseUrl(), path, e.getMessage());
            return null;
        }
    }

    /** 請 LINE Bot 為使用者連結圖文選單；menuKey 見規格第捌章，guest 選單代表回到帳號預設 */
    public void linkRichMenu(String channel, String lineUserId, String menuKey) {
        String key = settings.getLinebotInternalKey();
        if (key == null) {
            log.info("[LINE] 未設定 AGRI_LINEBOT_INTERNAL_KEY，略過圖文選單切換（{} → {}）", channel, menuKey);
            return;
        }
        try {
            String body = objectMapper.writeValueAsString(Map.of("channel", channel, "lineUserId", lineUserId, "menuKey", menuKey));
            HttpRequest req = HttpRequest.newBuilder(URI.create(settings.getLinebotBaseUrl() + "/internal/richmenu/link"))
                    .timeout(Duration.ofSeconds(3))
                    .header("Content-Type", "application/json; charset=UTF-8")
                    .header("X-Internal-Key", key)
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (res.statusCode() != 200) {
                log.warn("[LINE] 通知 LINE Bot 切換圖文選單失敗 [HTTP {}]", res.statusCode());
            }
        } catch (Exception e) {
            log.warn("[LINE] 無法連線 LINE Bot（{}）切換圖文選單：{}", settings.getLinebotBaseUrl(), e.getMessage());
        }
    }
}
