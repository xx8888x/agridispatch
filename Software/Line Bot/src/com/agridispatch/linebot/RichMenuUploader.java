package com.agridispatch.linebot;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/**
 * 智農派工 - LINE Rich Menu 上傳與發布工具
 *
 * 用法：
 *   java -cp bin com.agridispatch.linebot.RichMenuUploader
 *       → 上傳共用六宮格選單 (rich_menu.json) 並設為所有使用者的預設選單
 *   java -cp bin com.agridispatch.linebot.RichMenuUploader farmer U1234... U5678...
 *       → 上傳農場主八宮格選單 (rich_menu_farmer.json，含「黑轉白」)，只綁定到指定的 LINE userId，
 *         其他使用者 (含外籍移工) 仍看到共用選單
 */
public class RichMenuUploader {
    private static final String RICH_MENU_ENDPOINT = "https://api.line.me/v2/bot/richmenu";
    private static final String RICH_MENU_DATA_ENDPOINT = "https://api-data.line.me/v2/bot/richmenu";
    private static final String USER_ENDPOINT = "https://api.line.me/v2/bot/user/";

    public static void main(String[] args) {
        Config.loadConfig();

        boolean farmer = args.length > 0 && "farmer".equalsIgnoreCase(args[0]);
        String baseName = farmer ? "rich_menu_farmer" : "rich_menu";
        List<String> userIds = farmer ? Arrays.asList(args).subList(1, args.length) : List.of();

        // P1：現有兩份選單都屬農場主帳號；P3 改為一次上傳兩帳號共 6 份選單（規格第捌章）
        String token = Channel.FARMER.accessToken();
        if (Channel.FARMER.isMock()) {
            System.out.println("====================================================");
            System.out.println("⚠️ 農場主帳號目前處於本地模擬模式 (未填寫真實 LINE Channel Access Token)");
            System.out.println("如需發布至真實 LINE 官方帳號：");
            System.out.println("1. 請於 config.properties 設定 line.farmer.channel.access.token (或環境變數 LINE_FARMER_CHANNEL_ACCESS_TOKEN)");
            System.out.println("2. 準備 2500x1686 的 " + baseName + ".png 或 .jpg 底圖 (1MB 以內)");
            System.out.println("3. 重新執行此程式進行上傳綁定");
            System.out.println("====================================================");
            return;
        }
        if (farmer && userIds.isEmpty()) {
            System.out.println("⚠️ 請在 farmer 後面列出要套用農場主選單的 LINE userId (以空白分隔)。");
            return;
        }

        try {
            HttpClient client = HttpClient.newHttpClient();
            Path jsonPath = Path.of("rich_menu", baseName + ".json");
            if (!Files.exists(jsonPath)) {
                jsonPath = Path.of(baseName + ".json");
            }
            String menuJson = Files.readString(jsonPath);

            System.out.println("1. 正在向 LINE API 建立 Rich Menu 結構 (" + jsonPath + ")...");
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

                // 上傳底圖 (png 或 jpg 擇一)
                Path pngPath = Path.of("rich_menu", baseName + ".png");
                Path jpgPath = Path.of("rich_menu", baseName + ".jpg");
                Path imgPath = Files.exists(pngPath) ? pngPath : (Files.exists(jpgPath) ? jpgPath : null);
                if (imgPath != null) {
                    System.out.println("2. 正在上傳選單底圖 (" + imgPath + ")...");
                    byte[] imageBytes = Files.readAllBytes(imgPath);
                    String mime = imgPath.toString().endsWith(".png") ? "image/png" : "image/jpeg";
                    HttpRequest uploadImgReq = HttpRequest.newBuilder()
                            .uri(URI.create(RICH_MENU_DATA_ENDPOINT + "/" + richMenuId + "/content"))
                            .header("Authorization", "Bearer " + token)
                            .header("Content-Type", mime)
                            .POST(HttpRequest.BodyPublishers.ofByteArray(imageBytes))
                            .build();
                    HttpResponse<String> uploadImgRes = client.send(uploadImgReq, HttpResponse.BodyHandlers.ofString());
                    System.out.println("底圖上傳結果: " + uploadImgRes.statusCode());
                } else {
                    System.out.println("⚠️ 找不到 " + baseName + ".png / .jpg，選單沒有底圖，LINE 不會顯示此選單。");
                }

                if (farmer) {
                    // 只綁定到指定的農場主帳號
                    System.out.println("3. 正在將農場主選單綁定到 " + userIds.size() + " 位使用者...");
                    for (String userId : userIds) {
                        if (!userId.matches("U[0-9a-f]{32}")) {
                            System.out.println("  " + userId + " → 略過：不是有效的 LINE userId (U + 32 位小寫十六進位)");
                            continue;
                        }
                        HttpRequest linkReq = HttpRequest.newBuilder()
                                .uri(URI.create(USER_ENDPOINT + userId + "/richmenu/" + richMenuId))
                                .header("Authorization", "Bearer " + token)
                                .POST(HttpRequest.BodyPublishers.noBody())
                                .build();
                        HttpResponse<String> linkRes = client.send(linkReq, HttpResponse.BodyHandlers.ofString());
                        System.out.println("  " + userId + " → " + linkRes.statusCode());
                    }
                    System.out.println("🎉 農場主八宮格選單已套用！");
                } else {
                    // 設定為預設選單
                    System.out.println("3. 正在將該 Rich Menu 設為所有使用者的預設選單...");
                    HttpRequest defaultReq = HttpRequest.newBuilder()
                            .uri(URI.create(USER_ENDPOINT + "all/richmenu/" + richMenuId))
                            .header("Authorization", "Bearer " + token)
                            .POST(HttpRequest.BodyPublishers.noBody())
                            .build();
                    HttpResponse<String> defaultRes = client.send(defaultReq, HttpResponse.BodyHandlers.ofString());
                    System.out.println("設定預設選單結果: " + defaultRes.statusCode());
                    System.out.println("🎉 六宮格圖文選單已成功上線！");
                }
            }
        } catch (Exception e) {
            System.err.println("❌ 上傳 Rich Menu 失敗: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
