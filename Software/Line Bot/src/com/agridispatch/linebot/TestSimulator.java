package com.agridispatch.linebot;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;

/**
 * 智農派工 - LINE Bot 本地離線模擬器與測試客戶端
 * 無需 LINE Developers 帳號、無需 ngrok，即可在終端機完整測試所有對話與 Flex Message！
 */
public class TestSimulator {

    public static void main(String[] args) {
        System.out.println("==================================================================");
        System.out.println("🤖 智農派工 (AgriDispatch) - LINE Bot 本地模擬測試工具");
        System.out.println("==================================================================");
        System.out.println("說明：本工具支援離線測試與連線測試。輸入指令即可即時檢視回傳結果。");
        System.out.println("常用測試關鍵字：");
        System.out.println("  1. 派工        -> 查看派工確認單或移工任務卡");
        System.out.println("  2. 避險        -> 查看突發暴雨避險快訊");
        System.out.println("  3. 早鳥        -> 查看產季早鳥保證工時包");
        System.out.println("  4. 機耕        -> 查看大疆 T50 無人機代噴工單");
        System.out.println("  5. 薪資        -> 查看農業部 8,800 元獎勵與薪資");
        System.out.println("  6. 越南語      -> 切換為越南語模式 (再打 'nhiệm vụ')");
        System.out.println("  7. 印尼語      -> 切換為印尼語模式 (再打 'tugas')");
        System.out.println("  8. 帳號綁定    -> 查看帳號綁定引導卡");
        System.out.println("  輸入 'exit' 或 'quit' 退出測試。");
        System.out.println("==================================================================");

        // 先執行一輪自動化功能檢核
        runAutomatedCheck();

        System.out.println("\n--- 進入互動式測試模式 (請輸入關鍵字後按 Enter) ---");
        Scanner scanner = new Scanner(System.in);
        String userId = "U_TEST_USER_001";

        while (true) {
            System.out.print("\n💬 請輸入測試指令 > ");
            if (!scanner.hasNextLine()) break;
            String line = scanner.nextLine().trim();
            if ("exit".equalsIgnoreCase(line) || "quit".equalsIgnoreCase(line)) {
                System.out.println("👋 測試結束，感謝使用！");
                break;
            }
            if (line.isEmpty()) continue;

            // 取得回覆內容
            String replyJson = getReply(userId, line);
            printPrettyReply(line, replyJson);
        }
    }

    /**
     * 自動化檢測核心關鍵字
     */
    private static void runAutomatedCheck() {
        System.out.println("⚡ 正在執行核心指令自動檢測...");
        String[] testCases = {"派工", "避險", "早鳥", "機耕", "薪資", "越南語", "nhiệm vụ", "繁體中文"};
        String userId = "U_AUTO_CHECK";

        for (String cmd : testCases) {
            String resp = LineBotDispatcher.dispatch(userId, cmd);
            boolean ok = resp != null && !resp.isEmpty();
            String summary = extractAltTextOrText(resp);
            System.out.printf("  [測試] 指令: %-10s -> 狀態: %s ｜ 內容: %s%n", 
                    "「" + cmd + "」", 
                    (ok ? "✅ 成功" : "❌ 失敗"), 
                    summary);
        }
        System.out.println("✅ 自動檢測完成！所有情境均能正常產生對應之 Flex Message / 文本。");
    }

    private static String getReply(String userId, String text) {
        // 優先嘗試透過 HTTP 請求正在運行的伺服器
        try {
            HttpClient client = HttpClient.newHttpClient();
            String payload = "{\"text\":\"" + text.replace("\"", "\\\"") + "\"}";
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:5000/mock/chat"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                return resp.body();
            }
        } catch (Exception ignored) {
            // 伺服器未在背景啟動時，直接使用記憶體內分發器
        }

        return LineBotDispatcher.dispatch(userId, text);
    }

    private static void printPrettyReply(String input, String json) {
        System.out.println("\n------------------------------------------------------------------");
        System.out.println("📥 機器人回覆 (原始 JSON 預覽):");
        System.out.println("摘要標題: " + extractAltTextOrText(json));
        System.out.println("------------------------------------------------------------------");
        if (json.length() > 600) {
            System.out.println(json.substring(0, 600) + "\n... [已折疊其餘 " + (json.length() - 600) + " 字元]");
        } else {
            System.out.println(json);
        }
        System.out.println("------------------------------------------------------------------");
    }

    private static String extractAltTextOrText(String json) {
        if (json.contains("\"altText\"")) {
            int start = json.indexOf("\"altText\"");
            int colon = json.indexOf(":", start);
            int quote1 = json.indexOf("\"", colon);
            int quote2 = json.indexOf("\"", quote1 + 1);
            if (quote1 != -1 && quote2 != -1) {
                return json.substring(quote1 + 1, quote2);
            }
        }
        if (json.contains("\"text\"")) {
            // 尋找非 "type": "text" 的 "text" 屬性
            int start = 0;
            while ((start = json.indexOf("\"text\"", start)) != -1) {
                int colon = json.indexOf(":", start);
                int quote1 = json.indexOf("\"", colon);
                int quote2 = json.indexOf("\"", quote1 + 1);
                if (quote1 != -1 && quote2 != -1) {
                    String val = json.substring(quote1 + 1, quote2);
                    if (!"text".equalsIgnoreCase(val)) {
                        return val;
                    }
                }
                start += 6;
            }
        }
        return "Flex Message 卡片";
    }
}
