package com.agridispatch.linebot;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.concurrent.Executors;

/**
 * 智農派工 (AgriDispatch) - Java LINE Bot 主伺服器
 * 純 Java 零第三方依賴實現；同一個程式服務農場主、工作者兩個官方帳號（各自一條 webhook 路徑）
 */
public class LineBotServer {

    public static void main(String[] args) {
        try {
            int port = Config.PORT;
            HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);

            // LINE Webhook：每個官方帳號一條路徑（規格 6.2）
            server.createContext("/callback/farmer", new WebhookHandler(Channel.FARMER, false));
            server.createContext("/callback/worker", new WebhookHandler(Channel.WORKER, false));
            // 舊路徑過渡期視同農場主帳號（兩個帳號都改設新路徑後移除）
            WebhookHandler legacyHandler = new WebhookHandler(Channel.FARMER, true);
            server.createContext("/callback", legacyHandler);
            server.createContext("/webhook", legacyHandler);

            // 本地模擬發言與除錯 API（僅 Mock 模式）
            server.createContext("/mock/chat", new MockChatHandler());

            // 圖文選單 JSON 與底圖（網頁模擬器顯示用；只開放 6 份選單檔）
            server.createContext("/rich_menu/", exchange -> {
                String name = exchange.getRequestURI().getPath().substring("/rich_menu/".length());
                int dot = name.lastIndexOf('.');
                String key = dot > 0 ? name.substring(0, dot) : "";
                String ext = dot > 0 ? name.substring(dot + 1) : "";
                boolean known = RichMenus.FARMER_MENUS.contains(key) || RichMenus.WORKER_MENUS.contains(key);
                java.nio.file.Path file = RichMenus.dir().resolve(name);
                if (!known || !(ext.equals("jpg") || ext.equals("json")) || !java.nio.file.Files.exists(file)) {
                    HttpUtils.sendText(exchange, 404, "Not Found");
                    return;
                }
                byte[] bytes = java.nio.file.Files.readAllBytes(file);
                exchange.getResponseHeaders().set("Content-Type", ext.equals("jpg") ? "image/jpeg" : "application/json; charset=UTF-8");
                exchange.sendResponseHeaders(200, bytes.length);
                try (java.io.OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                }
            });

            // 後端呼叫的內部端點（X-Internal-Key，規格 6.3）
            server.createContext("/internal", new InternalHandler());

            // 網頁版 LINE 互動模擬器 (開啟 http://localhost:5000/ 即直接呈現手機聊天室介面)
            WebSimulatorHandler webSimulatorHandler = new WebSimulatorHandler();
            server.createContext("/", webSimulatorHandler);
            server.createContext("/simulator", webSimulatorHandler);

            // 健康檢查路徑
            server.createContext("/health", exchange -> {
                HttpUtils.applyCors(exchange, "GET, OPTIONS", "Content-Type");
                String resp = "{\"status\":\"UP\",\"mode\":\"" + overallMode() + "\",\"channels\":{"
                        + "\"farmer\":\"" + Config.modeLabel(Channel.FARMER) + "\","
                        + "\"worker\":\"" + Config.modeLabel(Channel.WORKER) + "\"}}";
                HttpUtils.sendJson(exchange, 200, resp);
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
                    Channel channel = fields.get("channel") == null ? Channel.FARMER : Channel.fromKey(String.valueOf(fields.get("channel")));
                    if (channel == null) {
                        HttpUtils.sendJson(exchange, 400, "{\"success\":false,\"message\":\"channel 必須是 farmer 或 worker\"}");
                        return;
                    }
                    String secret = fields.get("channelSecret") != null ? String.valueOf(fields.get("channelSecret")) : null;
                    String token = fields.get("channelAccessToken") != null ? String.valueOf(fields.get("channelAccessToken")) : null;
                    String backend = fields.get("backendApiBase") != null ? String.valueOf(fields.get("backendApiBase")) : null;

                    boolean ok = Config.updateConfig(channel, secret, token, backend);
                    String resp = "{\"success\":" + ok + ",\"channel\":\"" + channel.key + "\",\"mode\":\"" + Config.modeLabel(channel)
                            + "\",\"message\":\"" + channel.displayName + " 設定已更新\"}";
                    HttpUtils.sendJson(exchange, 200, resp);
                } else if ("GET".equalsIgnoreCase(method)) {
                    // GET 一律僅回傳遮罩後的值，不外洩完整金鑰；mode 為兩帳號總覽（MOCK／PRODUCTION／MIXED）
                    StringBuilder ch = new StringBuilder();
                    for (Channel c : Channel.values()) {
                        if (ch.length() > 0) ch.append(",");
                        ch.append("\"").append(c.key).append("\":{\"mode\":\"").append(Config.modeLabel(c))
                          .append("\",\"channelSecretMasked\":\"").append(mask(c.secret(), 3))
                          .append("\",\"channelAccessTokenMasked\":\"").append(mask(c.accessToken(), 5))
                          .append("\",\"basicIdConfigured\":").append(c.addFriendUrl() != null).append("}");
                    }
                    String resp = "{\"status\":\"UP\",\"mode\":\"" + overallMode() + "\",\"port\":" + Config.PORT
                            + ",\"channels\":{" + ch + "},\"backendApiBase\":\"" + Config.BACKEND_API_BASE + "\"}";
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
            System.out.println("🔗 農場主帳號 Webhook: http://localhost:" + port + "/callback/farmer  【" + Config.modeLabel(Channel.FARMER) + "】");
            System.out.println("🔗 工作者帳號 Webhook: http://localhost:" + port + "/callback/worker  【" + Config.modeLabel(Channel.WORKER) + "】");
            System.out.println("🛠️ 本地測試除錯路徑: http://localhost:" + port + "/mock/chat");
            System.out.println("🔍 健康檢查路徑:     http://localhost:" + port + "/health");
            System.out.println("🗄️ 身分與語言來源:   " + (Config.INTERNAL_KEY == null
                    ? "本機記憶體（未設定 AGRI_LINEBOT_INTERNAL_KEY，重啟後遺失）"
                    : "後端 " + Config.BACKEND_API_BASE + "/line/internal/*"));
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

    /** 兩個帳號的總體模式：都 Mock → MOCK、都正式 → PRODUCTION、其餘 → MIXED */
    static String overallMode() {
        boolean f = Channel.FARMER.isMock(), w = Channel.WORKER.isMock();
        return f && w ? "MOCK" : (!f && !w ? "PRODUCTION" : "MIXED");
    }

    private static String mask(String v, int keep) {
        if (v == null || v.length() <= keep * 2) return "******";
        return v.substring(0, keep) + "..." + v.substring(v.length() - keep);
    }
}
