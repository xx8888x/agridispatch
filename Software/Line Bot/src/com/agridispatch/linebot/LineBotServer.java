package com.agridispatch.linebot;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.concurrent.Executors;

/**
 * 智農派工 (AgriDispatch) - Java LINE Bot 主伺服器
 * 純 Java 零第三方依賴實現，支援高效能執行緒池與 Webhook 處理
 */
public class LineBotServer {

    public static void main(String[] args) {
        try {
            int port = Config.PORT;
            HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);

            WebhookHandler webhookHandler = new WebhookHandler();

            // LINE Webhook 官方回調路徑 (同時支援 /callback 與 /webhook)
            server.createContext("/callback", webhookHandler);
            server.createContext("/webhook", webhookHandler);

            // 本地模擬發言與除錯 API
            server.createContext("/mock/chat", webhookHandler);

            // 網頁版 LINE 互動模擬器 (開啟 http://localhost:5000/ 即直接呈現手機聊天室介面)
            WebSimulatorHandler webSimulatorHandler = new WebSimulatorHandler();
            server.createContext("/", webSimulatorHandler);
            server.createContext("/simulator", webSimulatorHandler);

            // 健康檢查路徑
            server.createContext("/health", exchange -> {
                String resp = "{\"status\":\"UP\",\"mode\":\"" + (Config.IS_MOCK_MODE ? "MOCK" : "PRODUCTION") + "\"}";
                byte[] b = resp.getBytes();
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, b.length);
                exchange.getResponseBody().write(b);
                exchange.close();
            });

            // 設定讀取與動態更新 API
            server.createContext("/api/config", exchange -> {
                exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
                exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
                exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
                if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                    exchange.sendResponseHeaders(204, -1);
                    return;
                }

                if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                    byte[] bytes = exchange.getRequestBody().readAllBytes();
                    String body = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);

                    String secret = extractJsonField(body, "channelSecret");
                    String token = extractJsonField(body, "channelAccessToken");
                    String backend = extractJsonField(body, "backendApiBase");

                    boolean ok = Config.updateConfig(secret, token, backend);
                    String resp = "{\"success\":" + ok + ",\"mode\":\"" + (Config.IS_MOCK_MODE ? "MOCK" : "PRODUCTION") + "\",\"message\":\"LINE 設定已更新\"}";
                    byte[] rb = resp.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
                    exchange.sendResponseHeaders(200, rb.length);
                    exchange.getResponseBody().write(rb);
                    exchange.close();
                } else {
                    String maskedSecret = Config.CHANNEL_SECRET.length() > 6 ? Config.CHANNEL_SECRET.substring(0, 3) + "..." + Config.CHANNEL_SECRET.substring(Config.CHANNEL_SECRET.length() - 3) : "******";
                    String maskedToken = Config.CHANNEL_ACCESS_TOKEN.length() > 10 ? Config.CHANNEL_ACCESS_TOKEN.substring(0, 5) + "..." + Config.CHANNEL_ACCESS_TOKEN.substring(Config.CHANNEL_ACCESS_TOKEN.length() - 5) : "******";
                    String resp = "{\"status\":\"UP\",\"mode\":\"" + (Config.IS_MOCK_MODE ? "MOCK" : "PRODUCTION") + "\",\"port\":" + Config.PORT + ",\"channelSecretMasked\":\"" + maskedSecret + "\",\"channelAccessTokenMasked\":\"" + maskedToken + "\",\"backendApiBase\":\"" + Config.BACKEND_API_BASE + "\"}";
                    byte[] rb = resp.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
                    exchange.sendResponseHeaders(200, rb.length);
                    exchange.getResponseBody().write(rb);
                    exchange.close();
                }
            });

            // 設定執行緒池
            server.setExecutor(Executors.newFixedThreadPool(8));
            server.start();

            System.out.println("==================================================================");
            System.out.println("🌱 智農派工 (AgriDispatch) - LINE Bot Java 伺服器已成功啟動！");
            System.out.println("📡 監聽埠號: http://localhost:" + port);
            System.out.println("🔗 Webhook 接收路徑: http://localhost:" + port + "/callback");
            System.out.println("🛠️ 本地測試除錯路徑: http://localhost:" + port + "/mock/chat");
            System.out.println("🔍 健康檢查路徑:     http://localhost:" + port + "/health");
            System.out.println("⚙️ 目前運行模式:     " + (Config.IS_MOCK_MODE ? "【本地模擬模式 (Mock Mode)】" : "【正式生產模式 (Production)】"));
            System.out.println("☕ Java 執行環境:    " + System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + ")");
            System.out.println("==================================================================");
            System.out.println("💡 提示：您可以使用 test_local.bat 進行本地模擬測試，免連外網即可查看！");
            System.out.println("==================================================================");

        } catch (Exception e) {
            System.err.println("❌ LINE Bot 伺服器啟動失敗: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static String extractJsonField(String json, String field) {
        if (json == null) return "";
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"" + field + "\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
        return m.find() ? m.group(1) : "";
    }
}
