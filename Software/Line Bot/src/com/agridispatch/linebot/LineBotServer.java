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

            // 設定讀取與動態更新 API (管理端點，須以 X-Admin-Key 驗證身分)
            server.createContext("/api/config", exchange -> {
                HttpUtils.applyCors(exchange, "GET, POST, OPTIONS", "Content-Type, X-Admin-Key");
                String method = exchange.getRequestMethod();

                if ("OPTIONS".equalsIgnoreCase(method)) {
                    exchange.sendResponseHeaders(204, -1);
                    exchange.close();
                    return;
                }

                // 未設定管理金鑰時，整個設定 API 停用 (啟動時的 log 已提示如何設定 LINEBOT_ADMIN_KEY / admin.key)
                if (Config.ADMIN_KEY == null) {
                    HttpUtils.sendJson(exchange, 403,
                            "{\"success\":false,\"message\":\"設定 API 尚未啟用：請設定環境變數 LINEBOT_ADMIN_KEY 或 config.properties 的 admin.key\"}");
                    return;
                }

                // 驗證管理金鑰；注意透過 localtunnel/ngrok 對外時來源 IP 一律為 127.0.0.1，不可用來源位址當保護機制
                if (!HttpUtils.isAdminAuthorized(exchange)) {
                    HttpUtils.sendJson(exchange, 403,
                            "{\"success\":false,\"message\":\"Forbidden: 缺少或錯誤的 X-Admin-Key 標頭\"}");
                    return;
                }

                if ("POST".equalsIgnoreCase(method)) {
                    byte[] bytes = exchange.getRequestBody().readAllBytes();
                    String body = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);

                    java.util.Map<String, Object> fields = SimpleJson.parseObject(body);
                    String secret = fields.get("channelSecret") != null ? String.valueOf(fields.get("channelSecret")) : null;
                    String token = fields.get("channelAccessToken") != null ? String.valueOf(fields.get("channelAccessToken")) : null;
                    String backend = fields.get("backendApiBase") != null ? String.valueOf(fields.get("backendApiBase")) : null;

                    boolean ok = Config.updateConfig(secret, token, backend);
                    String resp = "{\"success\":" + ok + ",\"mode\":\"" + (Config.IS_MOCK_MODE ? "MOCK" : "PRODUCTION") + "\",\"message\":\"LINE 設定已更新\"}";
                    HttpUtils.sendJson(exchange, 200, resp);
                } else if ("GET".equalsIgnoreCase(method)) {
                    // GET 一律僅回傳遮罩後的值，不外洩完整金鑰
                    String maskedSecret = Config.CHANNEL_SECRET.length() > 6 ? Config.CHANNEL_SECRET.substring(0, 3) + "..." + Config.CHANNEL_SECRET.substring(Config.CHANNEL_SECRET.length() - 3) : "******";
                    String maskedToken = Config.CHANNEL_ACCESS_TOKEN.length() > 10 ? Config.CHANNEL_ACCESS_TOKEN.substring(0, 5) + "..." + Config.CHANNEL_ACCESS_TOKEN.substring(Config.CHANNEL_ACCESS_TOKEN.length() - 5) : "******";
                    String resp = "{\"status\":\"UP\",\"mode\":\"" + (Config.IS_MOCK_MODE ? "MOCK" : "PRODUCTION") + "\",\"port\":" + Config.PORT + ",\"channelSecretMasked\":\"" + maskedSecret + "\",\"channelAccessTokenMasked\":\"" + maskedToken + "\",\"backendApiBase\":\"" + Config.BACKEND_API_BASE + "\"}";
                    HttpUtils.sendJson(exchange, 200, resp);
                } else {
                    HttpUtils.sendText(exchange, 405, "Method Not Allowed");
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
            System.out.println("🌐 CORS 允許來源:    " + Config.ALLOWED_ORIGINS);
            System.out.println("🔑 /api/config 管理金鑰: " + (Config.ADMIN_KEY == null ? "未設定 (API 已停用)" : "已設定"));
            System.out.println("☕ Java 執行環境:    " + System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + ")");
            System.out.println("==================================================================");
            System.out.println("💡 提示：您可以使用 test_local.bat 進行本地模擬測試，免連外網即可查看！");
            System.out.println("==================================================================");

        } catch (Exception e) {
            System.err.println("❌ LINE Bot 伺服器啟動失敗: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
