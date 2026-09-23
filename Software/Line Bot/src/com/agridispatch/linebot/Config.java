package com.agridispatch.linebot;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
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

        IS_MOCK_MODE = "mock_secret_88888888".equals(CHANNEL_SECRET) || "mock_token_agridispatch_line_bot".equals(CHANNEL_ACCESS_TOKEN);
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
