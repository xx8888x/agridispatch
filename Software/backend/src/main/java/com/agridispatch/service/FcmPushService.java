package com.agridispatch.service;

import com.agridispatch.config.AgriSettings;
import com.agridispatch.entity.PushToken;
import com.agridispatch.repository.PushTokenRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * App 推播（Firebase Cloud Messaging HTTP v1，純 JDK 實作，不加 Firebase Admin SDK 相依）
 *
 * 設定：agri.fcm.service-account-file（環境變數 AGRI_FCM_SERVICE_ACCOUNT_FILE，或 GOOGLE_APPLICATION_CREDENTIALS）
 * 指向 Firebase 主控台「專案設定 → 服務帳戶 → 產生新的私密金鑰」下載的 JSON；該檔放 repo 外，不得提交。
 * 未設定時不送出（只寫 log），通知中心紀錄照常。
 *
 * 流程：以服務帳戶私鑰簽 JWT（RS256）→ 換 OAuth access token（快取到過期前 1 分鐘）→
 * 逐一 POST https://fcm.googleapis.com/v1/projects/{projectId}/messages:send。
 * FCM 回 404 / UNREGISTERED 的 token 視為已失效並刪除（App 解除安裝、重裝等）。
 * 送出在背景執行緒進行，不拖慢呼叫端的 API 回應。
 */
@Service
public class FcmPushService {

    private static final Logger log = LoggerFactory.getLogger(FcmPushService.class);
    static final String SCOPE = "https://www.googleapis.com/auth/firebase.messaging";

    /** 推播對象中代表一群人的關鍵字；其他值視為個人（ref_id 或 username） */
    static final Set<String> ROLE_TARGETS = Set.of("farmer", "worker_local", "worker_foreign", "admin_tech");

    private final PushTokenRepository repository;
    private final AgriSettings settings;
    private final ObjectMapper mapper;
    private final String serviceAccountFile;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "fcm-push");
        t.setDaemon(true);
        return t;
    });

    private volatile Credentials credentials;
    private volatile boolean credentialsLoaded;
    private volatile String accessToken;
    private volatile Instant accessTokenExpiry = Instant.EPOCH;

    record Credentials(String projectId, String clientEmail, PrivateKey privateKey, String tokenUri) {}

    public FcmPushService(PushTokenRepository repository, AgriSettings settings, ObjectMapper mapper,
                          @Value("${agri.fcm.service-account-file:}") String serviceAccountFile) {
        this.repository = repository;
        this.settings = settings;
        this.mapper = mapper;
        this.serviceAccountFile = serviceAccountFile == null ? "" : serviceAccountFile.trim();
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    /** 是否已設定且讀得到服務帳戶金鑰 */
    public boolean isConfigured() {
        return credentials() != null;
    }

    // =========================================================================
    // 權杖登記
    // =========================================================================

    /** 登記（或改綁）裝置 token 給目前登入者 */
    public PushToken register(String token, String username, String refId, String role, String platform) {
        String id = sha256(token);
        PushToken t = repository.findById(id).orElseGet(() -> new PushToken(id, token));
        t.setToken(token);
        t.setUsername(username);
        t.setRefId(refId);
        t.setRole(role);
        t.setPlatform(platform == null || platform.isBlank() ? "android" : platform.trim().toLowerCase());
        return repository.save(t);
    }

    /** 登出時移除：只刪屬於該登入者的 token；回傳是否刪除 */
    public boolean unregister(String token, String username) {
        return repository.findById(sha256(token))
                .filter(t -> Objects.equals(t.getUsername(), username))
                .map(t -> { repository.delete(t); return true; })
                .orElse(false);
    }

    public long tokenCount() {
        return repository.count();
    }

    // =========================================================================
    // 推播
    // =========================================================================

    /**
     * 推播給 target：all（全部）、farmer／worker_local／worker_foreign／admin_tech（角色）、workers（本勞＋外勞），
     * 其他值視為個人 ref_id（W001、F001）或 username。外籍移工功能關閉時略過 worker_foreign。
     * 回傳 {configured, recipients}；實際送出在背景執行。
     */
    public Map<String, Object> send(String target, String title, String body, String channel) {
        List<PushToken> recipients = resolveTargets(target);
        Map<String, Object> res = new LinkedHashMap<>();
        boolean configured = isConfigured();
        res.put("configured", configured);
        res.put("recipients", recipients.size());
        if (recipients.isEmpty()) return res;
        if (!configured) {
            log.info("[FCM] 未設定 agri.fcm.service-account-file，略過 App 推播（對象 {}，{} 台裝置）", target, recipients.size());
            return res;
        }
        executor.submit(() -> {
            for (PushToken t : recipients) deliver(t, title, body, channel);
        });
        return res;
    }

    List<PushToken> resolveTargets(String target) {
        String tgt = target == null || target.isBlank() ? "all" : target.trim();
        List<PushToken> list;
        if ("all".equalsIgnoreCase(tgt)) {
            list = repository.findAll();
        } else if ("workers".equalsIgnoreCase(tgt)) {
            list = new ArrayList<>(repository.findByRole("worker_local"));
            list.addAll(repository.findByRole("worker_foreign"));
        } else if (ROLE_TARGETS.contains(tgt)) {
            list = repository.findByRole(tgt);
        } else {
            Map<String, PushToken> byId = new LinkedHashMap<>();
            repository.findByRefIdIgnoreCase(tgt).forEach(t -> byId.put(t.getId(), t));
            repository.findByUsername(tgt).forEach(t -> byId.put(t.getId(), t));
            list = new ArrayList<>(byId.values());
        }
        boolean foreign = settings.isForeignWorkersEnabled();
        return list.stream().filter(t -> foreign || !"worker_foreign".equals(t.getRole())).toList();
    }

    private void deliver(PushToken t, String title, String body, String channel) {
        try {
            Credentials c = credentials();
            String access = accessToken(c);
            String payload = mapper.writeValueAsString(message(t.getToken(), title, body, channel));
            HttpRequest req = HttpRequest.newBuilder(URI.create(
                            "https://fcm.googleapis.com/v1/projects/" + c.projectId() + "/messages:send"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Bearer " + access)
                    .header("Content-Type", "application/json; charset=UTF-8")
                    .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (res.statusCode() == 200) return;
            if (isUnregistered(res.statusCode(), res.body())) {
                repository.deleteById(t.getId());
                log.info("[FCM] token 已失效，已刪除（{}）", t.getUsername());
            } else {
                log.warn("[FCM] 推播失敗 HTTP {}：{}", res.statusCode(), truncate(res.body()));
            }
        } catch (Exception e) {
            log.warn("[FCM] 推播失敗：{}", e.getMessage());
        }
    }

    static boolean isUnregistered(int status, String body) {
        return status == 404 || (body != null && body.contains("UNREGISTERED"));
    }

    /** FCM v1 訊息：notification（App 在背景時由系統顯示）＋ data（App 在前景時由 AgriFirebaseMessagingService 顯示） */
    static Map<String, Object> message(String token, String title, String body, String channel) {
        String ch = channel == null || channel.isBlank() ? "dispatch" : channel;
        Map<String, Object> notification = new LinkedHashMap<>();
        notification.put("title", title);
        notification.put("body", body);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("title", title);
        data.put("body", body);
        data.put("channel", ch);
        Map<String, Object> android = new LinkedHashMap<>();
        android.put("priority", "weather".equals(ch) ? "HIGH" : "NORMAL");
        android.put("notification", Map.of("channel_id", androidChannelId(ch)));
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("token", token);
        msg.put("notification", notification);
        msg.put("data", data);
        msg.put("android", android);
        return Map.of("message", msg);
    }

    /** 與 App 的 NotificationHelper.postNotification 對應 */
    static String androidChannelId(String channel) {
        return switch (channel.toLowerCase()) {
            case "weather", "alert" -> "channel_weather";
            case "attendance", "clockin" -> "channel_attendance";
            case "system", "payment" -> "channel_system";
            default -> "channel_dispatch";
        };
    }

    // =========================================================================
    // 服務帳戶 → OAuth access token
    // =========================================================================

    private synchronized Credentials credentials() {
        if (credentialsLoaded) return credentials;
        credentialsLoaded = true;
        if (serviceAccountFile.isEmpty()) return null;
        try {
            credentials = parseServiceAccount(mapper, Files.readString(Path.of(serviceAccountFile), StandardCharsets.UTF_8));
            log.info("[FCM] 已載入服務帳戶（專案 {}）", credentials.projectId());
        } catch (Exception e) {
            log.warn("[FCM] 無法讀取服務帳戶金鑰 {}：{}，App 推播停用", serviceAccountFile, e.getMessage());
        }
        return credentials;
    }

    static Credentials parseServiceAccount(ObjectMapper mapper, String json) throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, Object> m = mapper.readValue(json, Map.class);
        String projectId = Objects.toString(m.get("project_id"), "");
        String email = Objects.toString(m.get("client_email"), "");
        String pem = Objects.toString(m.get("private_key"), "");
        if (projectId.isEmpty() || email.isEmpty() || pem.isEmpty()) {
            throw new IllegalArgumentException("缺少 project_id、client_email 或 private_key");
        }
        String b64 = pem.replace("-----BEGIN PRIVATE KEY-----", "").replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        PrivateKey key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(b64)));
        String tokenUri = Objects.toString(m.get("token_uri"), "https://oauth2.googleapis.com/token");
        return new Credentials(projectId, email, key, tokenUri);
    }

    private synchronized String accessToken(Credentials c) throws Exception {
        if (accessToken != null && Instant.now().isBefore(accessTokenExpiry)) return accessToken;
        String jwt = signedJwt(mapper, c, Instant.now());
        String form = "grant_type=" + URLEncoder.encode("urn:ietf:params:oauth:grant-type:jwt-bearer", StandardCharsets.UTF_8)
                + "&assertion=" + URLEncoder.encode(jwt, StandardCharsets.UTF_8);
        HttpRequest req = HttpRequest.newBuilder(URI.create(c.tokenUri()))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (res.statusCode() != 200) throw new IllegalStateException("換 access token 失敗 HTTP " + res.statusCode() + "：" + truncate(res.body()));
        @SuppressWarnings("unchecked")
        Map<String, Object> m = mapper.readValue(res.body(), Map.class);
        accessToken = Objects.toString(m.get("access_token"), null);
        long expiresIn = m.get("expires_in") instanceof Number n ? n.longValue() : 3600;
        accessTokenExpiry = Instant.now().plusSeconds(Math.max(60, expiresIn - 60));
        return accessToken;
    }

    /** Google OAuth 2.0 服務帳戶 JWT（RS256，效期 1 小時） */
    static String signedJwt(ObjectMapper mapper, Credentials c, Instant now) throws Exception {
        Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", c.clientEmail());
        claims.put("scope", SCOPE);
        claims.put("aud", c.tokenUri());
        claims.put("iat", now.getEpochSecond());
        claims.put("exp", now.getEpochSecond() + 3600);
        String unsigned = enc.encodeToString("{\"alg\":\"RS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8))
                + "." + enc.encodeToString(mapper.writeValueAsBytes(claims));
        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initSign(c.privateKey());
        sig.update(unsigned.getBytes(StandardCharsets.US_ASCII));
        return unsigned + "." + enc.encodeToString(sig.sign());
    }

    static String sha256(String s) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(h);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String truncate(String s) {
        return s == null ? "" : s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }
}
