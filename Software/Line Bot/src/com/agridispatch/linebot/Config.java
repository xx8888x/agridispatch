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
 */
public class Config {
    public static String CHANNEL_ACCESS_TOKEN = "mock_token_agridispatch_line_bot";
    public static String CHANNEL_SECRET = "mock_secret_88888888";
    public static int PORT = 5000;
    public static String BACKEND_API_BASE = "http://localhost:3000/api";
    public static boolean IS_MOCK_MODE = true;

    /**
     * /api/config 等管理端點的存取金鑰。未設定 (null) 時，該類 API 應整體停用並回應 403，
     * 不可只靠「僅允許本機」當保護，因為透過 localtunnel/ngrok 對外時本機也會收到來自 127.0.0.1 的請求。
     * 來源：環境變數 LINEBOT_ADMIN_KEY，或 config.properties 的 admin.key。
     */
    public static String ADMIN_KEY = null;

    /**
     * CORS 允許的來源白名單 (取代先前的 Access-Control-Allow-Origin: *)。
     * 來源：環境變數 CORS_ALLOWED_ORIGINS，或 config.properties 的 cors.allowed.origins (以逗號分隔)。
     */
    public static List<String> ALLOWED_ORIGINS = Collections.unmodifiableList(
            List.of("http://localhost:3000", "http://127.0.0.1:3000"));

    static {
        loadConfig();
    }

    public static void loadConfig() {
        Properties prop = new Properties();
        File propFile = new File("config.properties");
        if (!propFile.exists()) {
            propFile = new File("Software/Line Bot/config.properties");
        }

        if (propFile.exists()) {
            try (InputStream input = new FileInputStream(propFile)) {
                prop.load(input);
            } catch (Exception e) {
                System.out.println("⚠️ 讀取 config.properties 失敗，使用預設值: " + e.getMessage());
            }
        }

        // 優先取用環境變數，若無則讀取 properties，最後回退至預設值
        String envToken = System.getenv("LINE_CHANNEL_ACCESS_TOKEN");
        CHANNEL_ACCESS_TOKEN = envToken != null ? envToken : prop.getProperty("line.channel.access.token", CHANNEL_ACCESS_TOKEN);

        String envSecret = System.getenv("LINE_CHANNEL_SECRET");
        CHANNEL_SECRET = envSecret != null ? envSecret : prop.getProperty("line.channel.secret", CHANNEL_SECRET);

        String envPort = System.getenv("PORT");
        String portStr = envPort != null ? envPort : prop.getProperty("server.port", "5000");
        try {
            PORT = Integer.parseInt(portStr);
        } catch (Exception e) {
            PORT = 5000;
        }

        String envBackend = System.getenv("BACKEND_API_BASE");
        BACKEND_API_BASE = envBackend != null ? envBackend : prop.getProperty("backend.api.base", BACKEND_API_BASE);

        String envAdminKey = System.getenv("LINEBOT_ADMIN_KEY");
        String adminKey = (envAdminKey != null && !envAdminKey.isBlank())
                ? envAdminKey
                : prop.getProperty("admin.key", "");
        ADMIN_KEY = (adminKey == null || adminKey.isBlank()) ? null : adminKey;

        String envCors = System.getenv("CORS_ALLOWED_ORIGINS");
        String corsStr = (envCors != null && !envCors.isBlank())
                ? envCors
                : prop.getProperty("cors.allowed.origins", "http://localhost:3000,http://127.0.0.1:3000");
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

        IS_MOCK_MODE = "mock_secret_88888888".equals(CHANNEL_SECRET) || "mock_token_agridispatch_line_bot".equals(CHANNEL_ACCESS_TOKEN);

        if (ADMIN_KEY == null) {
            System.out.println("⚠️ 尚未設定管理金鑰，/api/config 設定 API 已停用 (回應 403)。");
            System.out.println("   如需啟用，請設定環境變數 LINEBOT_ADMIN_KEY=<您的管理金鑰>，");
            System.out.println("   或於 config.properties 加入一行：admin.key=<您的管理金鑰>");
        }
    }

    public static synchronized boolean updateConfig(String secret, String token, String backend) {
        try {
            Properties prop = new Properties();
            File propFile = new File("config.properties");
            if (!propFile.exists()) {
                propFile = new File("Software/Line Bot/config.properties");
            }

            if (propFile.exists()) {
                try (InputStream input = new FileInputStream(propFile)) {
                    prop.load(input);
                }
            }

            if (secret != null && !secret.isBlank()) {
                prop.setProperty("line.channel.secret", secret);
                CHANNEL_SECRET = secret;
            }
            if (token != null && !token.isBlank()) {
                prop.setProperty("line.channel.access.token", token);
                CHANNEL_ACCESS_TOKEN = token;
            }
            if (backend != null && !backend.isBlank()) {
                prop.setProperty("backend.api.base", backend);
                BACKEND_API_BASE = backend;
            }

            try (java.io.FileOutputStream out = new java.io.FileOutputStream(propFile)) {
                prop.store(out, "Updated via LineBot Configuration API");
            }

            IS_MOCK_MODE = "mock_secret_88888888".equals(CHANNEL_SECRET) || "mock_token_agridispatch_line_bot".equals(CHANNEL_ACCESS_TOKEN);
            System.out.println("🔄 LINE Bot 設定已更新，目前運行模式: " + (IS_MOCK_MODE ? "【本地模擬模式】" : "【正式生產模式】"));
            return true;
        } catch (Exception e) {
            System.err.println("更新設定失敗: " + e.getMessage());
            return false;
        }
    }
}
