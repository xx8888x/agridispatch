package com.agridispatch.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * 智農派工 全域設定（集中讀取 application.yml / 環境變數）
 * 詳見 Doc/03_認證與API安全規格.md
 */
@Component
public class AgriSettings {

    /** demo 模式（路演展示）：--spring.profiles.active=demo 或 agri.demo.enabled=true */
    @Value("${agri.demo.enabled:false}")
    private boolean demoEnabled;

    /** Token 簽章密鑰（環境變數 AGRI_TOKEN_SECRET） */
    @Value("${agri.security.token-secret:}")
    private String tokenSecret;

    /** 管理員初始密碼（環境變數 AGRI_ADMIN_PASSWORD） */
    @Value("${agri.security.admin-password:}")
    private String adminPassword;

    /** CORS 白名單（逗號分隔）；https://appassets.androidplatform.net 是 Android App WebViewAssetLoader 的固定來源（規格 06 第陸章） */
    @Value("${agri.cors.allowed-origins:http://localhost:3000,http://localhost:5000,http://127.0.0.1:3000,https://appassets.androidplatform.net}")
    private String corsAllowedOrigins;

    /** 金流供應者：mock | ecpay */
    @Value("${agri.payment.provider:mock}")
    private String paymentProvider;

    /** LINE Login channel ID：驗證 LIFF 帳號綁定的 ID token（環境變數 LINE_LOGIN_CHANNEL_ID，規格 05 第 5.3 節） */
    @Value("${agri.line.login-channel-id:}")
    private String lineLoginChannelId;

    /** 與 LINE Bot 互相呼叫內部端點的共用金鑰（環境變數 AGRI_LINEBOT_INTERNAL_KEY，規格 05 第 6.3 節）；未設定時內部端點停用 */
    @Value("${agri.linebot.internal-key:}")
    private String linebotInternalKey;

    /** LINE Bot 服務位址（環境變數 AGRI_LINEBOT_BASE_URL），用於通知切換圖文選單 */
    @Value("${agri.linebot.base-url:http://localhost:5000}")
    private String linebotBaseUrl;

    /**
     * 啟動時是否寫入示範資料（工班、工人、訂單、出勤、加保、評價、人才、黑轉白清冊、天候狀態與示範帳號）。
     * 環境變數 AGRI_SEED_DEMO_DATA；未設定時跟隨 demo 模式。正式環境不得開啟。
     * 作物費率與工時包 DM（產品定義）屬參考資料，不論此設定都會補齊。
     */
    @Value("${agri.seed.demo-data:}")
    private String seedDemoData;

    /**
     * 測試期開關：登入失敗鎖定只套用在科技後台（admin_tech）帳號，其餘角色不鎖（環境變數 AGRI_LOGIN_LOCK_ADMIN_ONLY）。
     * 僅供 DEV／測試環境，正式環境不得開啟。
     */
    @Value("${agri.security.login-lock-admin-only:false}")
    private boolean loginLockAdminOnly;

    /** 中央氣象署開放資料授權碼（環境變數 CWA_API_KEY）；未設定時 /api/weather/** 回 503 */
    @Value("${agri.weather.cwa-api-key:}")
    private String cwaApiKey;

    /** 氣象資料快取分鐘數（環境變數 AGRI_WEATHER_CACHE_MINUTES） */
    /** 外籍移工功能開關（與前端 features.foreignWorkers、LINE Bot FEATURE_FOREIGN_WORKERS 一致）；預設 false＝暫時下架 */
    @Value("${agri.features.foreign-workers:false}")
    private boolean foreignWorkersEnabled;

    @Value("${agri.weather.cache-minutes:30}")
    private int weatherCacheMinutes;

    public String getCwaApiKey() { return blankToNull(cwaApiKey); }

    public int getWeatherCacheMinutes() { return Math.max(1, weatherCacheMinutes); }

    public boolean isDemoEnabled() { return demoEnabled; }

    /** 外籍移工功能是否開放；關閉時派工媒合不列入、LINE 不推播給外籍工人（資料保留） */
    public boolean isForeignWorkersEnabled() { return foreignWorkersEnabled; }

    /** tier1_foreign 工人在外籍移工功能關閉時不參與派工與推播 */
    public boolean isWorkerAvailable(com.agridispatch.entity.Worker w) {
        return w != null && (foreignWorkersEnabled || !"tier1_foreign".equals(w.getTier()));
    }

    public boolean isLoginLockAdminOnly() { return loginLockAdminOnly; }

    public boolean isSeedDemoData() {
        return seedDemoData == null || seedDemoData.isBlank() ? demoEnabled : Boolean.parseBoolean(seedDemoData.trim());
    }

    public String getTokenSecret() { return tokenSecret; }

    public String getAdminPassword() { return adminPassword; }

    public List<String> getCorsAllowedOrigins() {
        return Arrays.stream(corsAllowedOrigins.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    public String getLineLoginChannelId() { return blankToNull(lineLoginChannelId); }

    public String getLinebotInternalKey() { return blankToNull(linebotInternalKey); }

    public String getLinebotBaseUrl() {
        return linebotBaseUrl == null ? "http://localhost:5000" : linebotBaseUrl.trim().replaceAll("/+$", "");
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    public String getPaymentProvider() {
        return paymentProvider == null || paymentProvider.isBlank() ? "mock" : paymentProvider.trim().toLowerCase();
    }
}
