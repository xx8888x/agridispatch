package com.agridispatch.server;

import com.sun.net.httpserver.HttpServer;
import java.io.File;
import java.net.InetSocketAddress;
import java.util.concurrent.Executors;

/**
 * 智農派工 (AgriDispatch) - Java 後台主伺服器
 */
public class AgriDispatchServer {
    private static final int PORT = 3000;

    public static void main(String[] args) {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);

            // API 路由
            server.createContext("/api", new ApiHandler());
            server.createContext("/health", new ApiHandler());

            // 靜態檔案路由 (指向 Software 目錄下的 web 與 demo)
            File softwareDir = new File(System.getProperty("user.dir")).getParentFile();
            if (softwareDir == null || !new File(softwareDir, "web").exists()) {
                softwareDir = new File(System.getProperty("user.dir"));
            }
            StaticFileHandler staticHandler = new StaticFileHandler(softwareDir.getAbsolutePath());
            server.createContext("/web", staticHandler);
            server.createContext("/demo", staticHandler);
            server.createContext("/", staticHandler);

            // 執行緒池
            server.setExecutor(Executors.newFixedThreadPool(12));
            server.start();

            System.out.println("====================================================");
            System.out.println("🌱 智農派工 (AgriDispatch) Java 後台服務已啟動！");
            System.out.println("📡 服務監聽埠號: http://localhost:" + PORT);
            System.out.println("🔗 REST API 基礎路徑: http://localhost:" + PORT + "/api");
            System.out.println("🌐 響應式 Web 系統: http://localhost:" + PORT + "/web/app.html");
            System.out.println("💻 純 HTML Demo 原型: http://localhost:" + PORT + "/demo/app.html");
            System.out.println("☕ 執行環境: Java " + System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + ")");
            System.out.println("====================================================");

        } catch (Exception e) {
            System.err.println("伺服器啟動失敗: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
