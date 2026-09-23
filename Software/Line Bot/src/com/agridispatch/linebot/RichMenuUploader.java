package com.agridispatch.linebot;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 智農派工 - LINE Rich Menu 上傳與發布工具
 * 負責透過 LINE Messaging API 建立六宮格選單並設為預設
 */
public class RichMenuUploader {
    private static final String RICH_MENU_ENDPOINT = "https://api.line.me/v2/bot/richmenu";
    private static final String RICH_MENU_DATA_ENDPOINT = "https://api-data.line.me/v2/bot/richmenu";

    public static void main(String[] args) {
        String token = Config.CHANNEL_ACCESS_TOKEN;
        if (Config.IS_MOCK_MODE) {
            System.out.println("====================================================");
            System.out.println("⚠️ 目前處於本地模擬模式 (未填寫真實 LINE Channel Access Token)");
            System.out.println("如需發布至真實 LINE 官方帳號：");
            System.out.println("1. 請於 config.properties 設定真實 LINE_CHANNEL_ACCESS_TOKEN");
            System.out.println("2. 將 rich_menu.svg 轉為 2500x1686 的 rich_menu.png");
            System.out.println("3. 重新執行此程式進行上傳綁定");
            System.out.println("====================================================");
            return;
        }

        try {
            HttpClient client = HttpClient.newHttpClient();
            Path jsonPath = Path.of("rich_menu", "rich_menu.json");
            if (!Files.exists(jsonPath)) {
                jsonPath = Path.of("rich_menu.json");
            }
            String menuJson = Files.readString(jsonPath);

            System.out.println("1. 正在向 LINE API 建立 Rich Menu 結構...");
            HttpRequest createReq = HttpRequest.newBuilder()
                    .uri(URI.create(RICH_MENU_ENDPOINT))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json; charset=UTF-8")
                    .POST(HttpRequest.BodyPublishers.ofString(menuJson))
                    .build();

            HttpResponse<String> createRes = client.send(createReq, HttpResponse.BodyHandlers.ofString());
            System.out.println("回應代碼: " + createRes.statusCode() + ", 內容: " + createRes.body());

            if (createRes.statusCode() == 200) {
                // 取得 richMenuId
                String body = createRes.body();
                String richMenuId = body.replaceAll(".*\"richMenuId\"\\s*:\\s*\"([^\"]+)\".*", "$1");
                System.out.println("✅ Rich Menu 建立成功！ID = " + richMenuId);

                // 上傳圖片 (若存在 png 檔)
                Path imgPath = Path.of("rich_menu", "rich_menu.png");
                if (Files.exists(imgPath)) {
                    System.out.println("2. 正在上傳選單底圖...");
                    byte[] imageBytes = Files.readAllBytes(imgPath);
                    HttpRequest uploadImgReq = HttpRequest.newBuilder()
                            .uri(URI.create(RICH_MENU_DATA_ENDPOINT + "/" + richMenuId + "/content"))
                            .header("Authorization", "Bearer " + token)
                            .header("Content-Type", "image/png")
                            .POST(HttpRequest.BodyPublishers.ofByteArray(imageBytes))
                            .build();
                    HttpResponse<String> uploadImgRes = client.send(uploadImgReq, HttpResponse.BodyHandlers.ofString());
                    System.out.println("底圖上傳結果: " + uploadImgRes.statusCode());
                }

                // 設定為預設選單
                System.out.println("3. 正在將該 Rich Menu 設為所有使用者的預設選單...");
                HttpRequest defaultReq = HttpRequest.newBuilder()
                        .uri(URI.create("https://api.line.me/v2/bot/user/all/richmenu/" + richMenuId))
                        .header("Authorization", "Bearer " + token)
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build();
                HttpResponse<String> defaultRes = client.send(defaultReq, HttpResponse.BodyHandlers.ofString());
                System.out.println("設定預設選單結果: " + defaultRes.statusCode());
                System.out.println("🎉 六宮格圖文選單已成功上線！");
            }
        } catch (Exception e) {
            System.err.println("❌ 上傳 Rich Menu 失敗: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
