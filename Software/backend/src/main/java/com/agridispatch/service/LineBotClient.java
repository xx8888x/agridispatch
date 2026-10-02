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
