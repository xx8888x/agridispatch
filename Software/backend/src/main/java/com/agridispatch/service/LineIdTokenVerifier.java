package com.agridispatch.service;

import com.agridispatch.common.ApiException;
import com.agridispatch.config.AgriSettings;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.regex.Pattern;

/**
 * 驗證 LIFF 取得的 LINE ID token，取出 LINE userId（sub）。規格 05 第 5.3 節第 1 點：
 * LINE userId 只從後端驗證過的 ID token 取得，前端或網址傳入的 userId 一律不採信。
 *
 * - 已設定 LINE_LOGIN_CHANNEL_ID：呼叫 LINE 的 POST /oauth2/v2.1/verify（client_id＝channel ID，LINE 會一併檢查 aud 與到期）
 * - 未設定且為 demo 模式：接受 "mock:U+32位十六進位" 形式的假 token，供路演與測試（正式模式一律不接受）
 * - 未設定且非 demo 模式：503
 */
@Component
public class LineIdTokenVerifier {

    private static final Logger log = LoggerFactory.getLogger(LineIdTokenVerifier.class);
    private static final String VERIFY_URL = "https://api.line.me/oauth2/v2.1/verify";
    private static final Pattern LINE_USER_ID = Pattern.compile("U[0-9a-f]{32}");

    /** 驗證結果：LINE userId 與顯示名稱（可能為 null） */
    public record Verified(String lineUserId, String displayName) {}

    private final AgriSettings settings;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    public LineIdTokenVerifier(AgriSettings settings, ObjectMapper objectMapper) {
        this.settings = settings;
        this.objectMapper = objectMapper;
    }

    public Verified verify(String idToken) {
        if (idToken == null || idToken.isBlank() || idToken.length() > 4096) {
            throw ApiException.badRequest("缺少 idToken");
        }
        String channelId = settings.getLineLoginChannelId();
        if (channelId == null) {
            if (settings.isDemoEnabled() && idToken.startsWith("mock:")) {
                String uid = idToken.substring(5).trim();
                if (!LINE_USER_ID.matcher(uid).matches()) {
                    throw ApiException.badRequest("mock idToken 格式須為 mock:U + 32 位小寫十六進位");
                }
                return new Verified(uid, null);
            }
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "LINE Login 尚未設定（LINE_LOGIN_CHANNEL_ID），無法綁定");
        }
        try {
            String form = "id_token=" + URLEncoder.encode(idToken, StandardCharsets.UTF_8)
                    + "&client_id=" + URLEncoder.encode(channelId, StandardCharsets.UTF_8);
            HttpRequest req = HttpRequest.newBuilder(URI.create(VERIFY_URL))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form))
                    .build();
            HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (res.statusCode() != 200) {
                throw new ApiException(HttpStatus.UNAUTHORIZED, "LINE 身分驗證失敗，請重新開啟綁定頁");
            }
            JsonNode body = objectMapper.readTree(res.body());
            String sub = body.path("sub").asText("");
            if (!LINE_USER_ID.matcher(sub).matches() || !channelId.equals(body.path("aud").asText(""))) {
                throw new ApiException(HttpStatus.UNAUTHORIZED, "LINE 身分驗證失敗，請重新開啟綁定頁");
            }
            String name = body.path("name").asText("");
            return new Verified(sub, name.isBlank() ? null : name);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            log.warn("[LINE] ID token 驗證呼叫失敗：{}", e.getMessage());
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "暫時無法向 LINE 驗證身分，請稍後再試");
        }
    }
}
