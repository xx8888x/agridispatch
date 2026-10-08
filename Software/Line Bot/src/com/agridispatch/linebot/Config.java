package com.agridispatch.linebot;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

/**
 * 智農派工 - LINE Bot 配置載入器
 * 依序優先載入：環境變數 -> config.properties -> 預設值
 *
 * 兩個官方帳號（Channel.FARMER、Channel.WORKER）各自一組 secret／token：
 *   LINE_FARMER_CHANNEL_SECRET / line.farmer.channel.secret
 *   LINE_FARMER_CHANNEL_ACCESS_TOKEN / line.farmer.channel.access.token
 *   LINE_WORKER_CHANNEL_SECRET / line.worker.channel.secret
 *   LINE_WORKER_CHANNEL_ACCESS_TOKEN / line.worker.channel.access.token
 * 過渡相容：只設定舊的 LINE_CHANNEL_SECRET／LINE_CHANNEL_ACCESS_TOKEN（line.channel.*）時，視為農場主帳號。
 */
public class Config {
    public static int PORT = 5000;
    public static String BACKEND_API_BASE = "http://localhost:3000/api";

    /**
     * Flex 卡片按鈕連結的前端網站根網址 (其下為 農業派遣主網站/、linebot/ 等子站)。
     * 實機 LINE 必須改成手機連得到的公開 https 網址。
     * 來源：環境變數 WEB_BASE_URL，或 config.properties 的 web.base.url。
     */
    public static String WEB_BASE_URL = "http://localhost:3000/demo";

    /**
     * /api/config 等管理端點的存取金鑰。未設定 (null) 時，該類 API 應整體停用並回應 403，
     * 不可只靠「僅允許本機」當保護，因為透過 localtunnel/ngrok 對外時本機也會收到來自 127.0.0.1 的請求。
     * 來源：環境變數 LINEBOT_ADMIN_KEY，或 config.properties 的 admin.key。
     */
    public static String ADMIN_KEY = null;

    /**
     * Line Bot 與後端互相呼叫內部端點的共用金鑰（規格 6.3，P2 起使用）。未設定時為 null。
     * 來源：環境變數 AGRI_LINEBOT_INTERNAL_KEY，或 config.properties 的 internal.key。
     */
    public static String INTERNAL_KEY = null;

    /**
     * 帳號綁定頁的 LIFF ID（規格 5.3）。未設定時，綁定按鈕改連到既有的登入頁。
     * 來源：環境變數 LIFF_BIND_ID，或 config.properties 的 liff.bind.id。
     */
    public static String LIFF_BIND_ID = null;

    /**
     * CORS 允許的來源白名單 (取代先前的 Access-Control-Allow-Origin: *)。
     * 來源：環境變數 CORS_ALLOWED_ORIGINS，或 config.properties 的 cors.allowed.origins (以逗號分隔)。
     */
    public static List<String> ALLOWED_ORIGINS = Collections.unmodifiableList(
            List.of("http://localhost:3000", "http://127.0.0.1:3000"));

    /** 合作社服務專線（工作者帳號「聯絡合作社」按鈕）；未設定時只顯示勞動部 1955 專線。來源：COOP_CONTACT_PHONE／coop.contact.phone */
    public static String COOP_CONTACT_PHONE = null;

    /**
     * 功能開關：工班合法化（黑轉白）。預設 false（暫時下架，與前端 app-config.js 的 features.legalize 一致）：
     * 農場主帳號不回黑轉白卡片、服務導覽不列此項。改回 true 即恢復；圖文選單另需以 tools/make_rich_menus.py 重產並上傳。
     * 來源：環境變數 FEATURE_LEGALIZE，或 config.properties 的 feature.legalize（true／false）。
     */
    public static boolean FEATURE_LEGALIZE = false;

    /** 是否以舊的單一帳號設定（LINE_CHANNEL_*／line.channel.*）當作農場主帳號 */
    public static boolean LEGACY_FARMER_CONFIG = false;

    static {
        loadConfig();
    }

    private static File propFile() {
        File f = new File("config.properties");
        return f.exists() ? f : new File("Software/Line Bot/config.properties");
    }

    /** 環境變數優先，其次 properties；皆無回傳 null */
    private static String pick(Properties prop, String envName, String propName) {
        String env = System.getenv(envName);
        if (env != null && !env.isBlank()) return env.trim();
        String v = prop.getProperty(propName);
        return (v == null || v.isBlank()) ? null : v.trim();
    }

    public static void loadConfig() {
        Properties prop = new Properties();
        File propFile = propFile();
        if (propFile.exists()) {
            try (InputStream input = new FileInputStream(propFile)) {
                prop.load(input);
            } catch (Exception e) {
                System.out.println("⚠️ 讀取 config.properties 失敗，使用預設值: " + e.getMessage());
            }
        }

        // 兩個官方帳號
        String legacySecret = pick(prop, "LINE_CHANNEL_SECRET", "line.channel.secret");
        String legacyToken = pick(prop, "LINE_CHANNEL_ACCESS_TOKEN", "line.channel.access.token");
        String farmerSecret = pick(prop, "LINE_FARMER_CHANNEL_SECRET", "line.farmer.channel.secret");
        String farmerToken = pick(prop, "LINE_FARMER_CHANNEL_ACCESS_TOKEN", "line.farmer.channel.access.token");
        LEGACY_FARMER_CONFIG = false;
        if (farmerSecret == null && farmerToken == null && (legacySecret != null || legacyToken != null)) {
            farmerSecret = legacySecret;
            farmerToken = legacyToken;
            LEGACY_FARMER_CONFIG = !Channel.isPlaceholder(legacySecret) && !Channel.isPlaceholder(legacyToken);
        }
        Channel.FARMER.secret = farmerSecret == null ? "" : farmerSecret;
        Channel.FARMER.accessToken = farmerToken == null ? "" : farmerToken;
        Channel.FARMER.basicId = nvl(pick(prop, "LINE_FARMER_BASIC_ID", "line.farmer.basic.id"));

        String workerSecret = pick(prop, "LINE_WORKER_CHANNEL_SECRET", "line.worker.channel.secret");
        String workerToken = pick(prop, "LINE_WORKER_CHANNEL_ACCESS_TOKEN", "line.worker.channel.access.token");
        Channel.WORKER.secret = workerSecret == null ? "" : workerSecret;
        Channel.WORKER.accessToken = workerToken == null ? "" : workerToken;
        Channel.WORKER.basicId = nvl(pick(prop, "LINE_WORKER_BASIC_ID", "line.worker.basic.id"));

        String portStr = pick(prop, "PORT", "server.port");
        try {
            PORT = portStr == null ? 5000 : Integer.parseInt(portStr);
        } catch (Exception e) {
            PORT = 5000;
        }

        String backend = pick(prop, "BACKEND_API_BASE", "backend.api.base");
        if (backend != null) BACKEND_API_BASE = backend;

        String webBase = pick(prop, "WEB_BASE_URL", "web.base.url");
        if (webBase != null) WEB_BASE_URL = webBase.replaceAll("/+$", "");

        ADMIN_KEY = pick(prop, "LINEBOT_ADMIN_KEY", "admin.key");
        if (ADMIN_KEY != null && ADMIN_KEY.startsWith("請填入")) ADMIN_KEY = null;

        INTERNAL_KEY = pick(prop, "AGRI_LINEBOT_INTERNAL_KEY", "internal.key");
        if (INTERNAL_KEY != null && INTERNAL_KEY.startsWith("請填入")) INTERNAL_KEY = null;

        COOP_CONTACT_PHONE = pick(prop, "COOP_CONTACT_PHONE", "coop.contact.phone");

        FEATURE_LEGALIZE = "true".equalsIgnoreCase(pick(prop, "FEATURE_LEGALIZE", "feature.legalize"));

        LIFF_BIND_ID = pick(prop, "LIFF_BIND_ID", "liff.bind.id");
        if (LIFF_BIND_ID != null && LIFF_BIND_ID.startsWith("請填入")) LIFF_BIND_ID = null;

        String corsStr = pick(prop, "CORS_ALLOWED_ORIGINS", "cors.allowed.origins");
        if (corsStr == null) corsStr = "http://localhost:3000,http://127.0.0.1:3000";
        List<String> origins = new ArrayList<>();
        for (String o : corsStr.split(",")) {
            String trimmed = o.trim();
            if (!trimmed.isEmpty()) {
                origins.add(trimmed);
            }
        }
        ALLOWED_ORIGINS = origins.isEmpty()
                ? List.of("http://localhost:3000", "http://127.0.0.1:3000")
                : Collections.unmodifiableList(origins);

        if (LEGACY_FARMER_CONFIG) {
            System.out.println("⚠️ 偵測到舊的單一帳號設定 (LINE_CHANNEL_SECRET／line.channel.*)，已視為【農場主帳號】。");
            System.out.println("   請改用 LINE_FARMER_CHANNEL_* 與 LINE_WORKER_CHANNEL_*（見 config.properties.example）。");
        }
        if (ADMIN_KEY == null) {
            System.out.println("⚠️ 尚未設定管理金鑰，/api/config 設定 API 已停用 (回應 403)。");
            System.out.println("   如需啟用，請設定環境變數 LINEBOT_ADMIN_KEY=<您的管理金鑰>，");
            System.out.println("   或於 config.properties 加入一行：admin.key=<您的管理金鑰>");
        }
    }

    /** 兩個帳號都是 Mock 模式 */
    public static boolean allMock() {
        return Channel.FARMER.isMock() && Channel.WORKER.isMock();
    }

    public static String modeLabel(Channel c) {
        return c.isMock() ? "MOCK" : "PRODUCTION";
    }

    /**
     * /api/config POST：更新指定帳號的金鑰與後端位址並寫回 config.properties
     */
    public static synchronized boolean updateConfig(Channel channel, String secret, String token, String backend) {
        try {
            Properties prop = new Properties();
            File propFile = propFile();
            if (propFile.exists()) {
                try (InputStream input = new FileInputStream(propFile)) {
                    prop.load(input);
                }
            }

            String prefix = "line." + channel.key + ".channel.";
            if (secret != null && !secret.isBlank()) {
                prop.setProperty(prefix + "secret", secret.trim());
                channel.secret = secret.trim();
            }
            if (token != null && !token.isBlank()) {
                prop.setProperty(prefix + "access.token", token.trim());
                channel.accessToken = token.trim();
            }
            if (backend != null && !backend.isBlank()) {
                prop.setProperty("backend.api.base", backend.trim());
                BACKEND_API_BASE = backend.trim();
            }

            try (java.io.FileOutputStream out = new java.io.FileOutputStream(propFile)) {
                prop.store(out, "Updated via LineBot Configuration API");
            }

            System.out.println("🔄 LINE Bot 設定已更新：" + channel.displayName + " → "
                    + (channel.isMock() ? "【本地模擬模式】" : "【正式生產模式】"));
            return true;
        } catch (Exception e) {
            System.err.println("更新設定失敗: " + e.getMessage());
            return false;
        }
    }

    private static String nvl(String s) {
        return s == null ? "" : s;
    }
}
