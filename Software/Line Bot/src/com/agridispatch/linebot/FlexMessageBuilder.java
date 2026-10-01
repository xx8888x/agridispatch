package com.agridispatch.linebot;

import java.io.File;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 智農派工 - Flex Message 構造器
 * 負責產生 8 大情境的 Flex Message JSON 結構，提供給 LINE Messaging API 回覆或推播
 */
public class FlexMessageBuilder {

    /**
     * 讀取範本檔案或產生預設 JSON
     */
    private static String loadTemplateOrFallback(String filename, String fallbackJson) {
        try {
            Path p = Path.of("flex_templates", filename);
            if (!Files.exists(p)) {
                p = Path.of("Software", "Line Bot", "flex_templates", filename);
            }
            if (Files.exists(p)) {
                return Files.readString(p);
            }
        } catch (Exception ignored) {}
        return fallbackJson;
    }

    /**
     * 1. 農場主履約派工單
     */
    public static String getFarmerOrderFlex() {
        return wrapFlex("📋 智農派工 - 枋山愛文芒果派工確認單", 
                loadTemplateOrFallback("01_farmer_order.json", """
                {
                  "type": "bubble",
                  "header": { "type": "box", "layout": "vertical", "backgroundColor": "#047857", "contents": [{ "type": "text", "text": "智農派工・履約派遣單", "color": "#FFFFFF", "weight": "bold" }] }
                }
                """));
    }

    /**
     * 2. 移工每日任務卡 (依語言回傳)
     */
    public static String getWorkerTaskFlex(String lang) {
        if ("vi".equalsIgnoreCase(lang)) {
            return wrapFlex("📋 Thẻ Nhiệm Vụ Hôm Nay - W001 Nguyễn Văn Hùng", 
                    loadTemplateOrFallback("03_worker_task_vi.json", "{}"));
        } else if ("id".equalsIgnoreCase(lang)) {
            return wrapFlex("📋 Kartu Tugas Hari Ini - W006 Agus Santoso", 
                    loadTemplateOrFallback("04_worker_task_id.json", "{}"));
        } else {
            return wrapFlex("📋 今日派工任務卡 - W001 阮文雄", 
                    loadTemplateOrFallback("02_worker_task_zh.json", "{}"));
        }
    }

    /**
     * 3. 突發暴雨天候避險快訊
     */
    public static String getWeatherHedgeFlex() {
        return wrapFlex("🌧️ 突發暴雨避險快訊 - 全體轉移室內冷鏈", 
                loadTemplateOrFallback("05_weather_hedge.json", "{}"));
    }

    /**
     * 4. 產季早鳥保證工時包
     */
    public static String getEarlyBirdFlex() {
        return wrapFlex("⚡ 2026 產季早鳥保證工時包 - 315元/hr・鎖定名額・優先排班", 
                loadTemplateOrFallback("06_early_bird.json", "{}"));
    }

    /**
     * 5. 本國機耕師與無人機代噴工單
     */
    public static String getMachineryTaskFlex() {
        return wrapFlex("🚜 本國專業農機與大疆 T50 代噴任務卡", 
                loadTemplateOrFallback("07_machinery_task.json", "{}"));
    }

    /**
     * 6. 農業部就業獎勵與薪資津貼明細
     */
    public static String getSalaryBonusFlex() {
        return wrapFlex("💰 薪資明細與中央就業獎勵津貼 ($8,800)", 
                loadTemplateOrFallback("08_salary_bonus.json", "{}"));
    }

    /**
     * 7. 帳號綁定引導卡
     */
    public static String getBindingFlex(String userId) {
        String bubble = """
        {
          "type": "bubble",
          "header": {
            "type": "box",
            "layout": "vertical",
            "backgroundColor": "#0F766E",
            "paddingAll": "20px",
            "contents": [
              { "type": "text", "text": "🔗 帳號身分綁定", "color": "#FFFFFF", "size": "lg", "weight": "bold" }
            ]
          },
          "body": {
            "type": "box",
            "layout": "vertical",
            "spacing": "md",
            "paddingAll": "20px",
            "contents": [
              { "type": "text", "text": "綁定您的 LINE 帳號以接收專屬派工與津貼推播！", "size": "sm", "color": "#334155", "wrap": true },
              { "type": "text", "text": "LINE ID: %s", "size": "xs", "color": "#64748B" }
            ]
          },
          "footer": {
            "type": "box",
            "layout": "vertical",
            "spacing": "sm",
            "paddingAll": "16px",
            "contents": [
              { "type": "button", "style": "primary", "color": "#059669", "action": { "type": "uri", "label": "👨‍🌾 農場主身分綁定", "uri": "https://agridispatch.tw/web/app.html#farmer" } },
              { "type": "button", "style": "primary", "color": "#3B82F6", "action": { "type": "uri", "label": "🇻🇳 外籍移工身分綁定", "uri": "https://agridispatch.tw/web/app.html#worker_foreign" } }
            ]
          }
        }
        """.formatted(escapeJson(userId != null ? userId : "U_DEMO"));
        return wrapFlex("🔗 智農派工系統 - LINE 帳號綁定", bubble);
    }

    /**
     * 8. 服務導覽與說明選單
     */
    public static String getHelpMenuFlex() {
        String bubble = """
        {
          "type": "bubble",
          "header": {
            "type": "box",
            "layout": "vertical",
            "backgroundColor": "#047857",
            "paddingAll": "20px",
            "contents": [
              { "type": "text", "text": "智農派工 AgriDispatch", "color": "#A7F3D0", "size": "xs", "weight": "bold" },
              { "type": "text", "text": "🌱 LINE 官方助理服務導覽", "color": "#FFFFFF", "size": "lg", "weight": "bold" }
            ]
          },
          "body": {
            "type": "box",
            "layout": "vertical",
            "spacing": "sm",
            "paddingAll": "20px",
            "contents": [
              { "type": "text", "text": "您可以直接輸入以下關鍵字或點擊下方選單：", "size": "xs", "color": "#475569" },
              { "type": "text", "text": "• 輸入「派工」：查看今日出工或派工確認單", "size": "sm", "weight": "bold", "color": "#1E293B" },
              { "type": "text", "text": "• 輸入「避險」：查看突發暴雨冷鏈避險通報", "size": "sm", "weight": "bold", "color": "#1E293B" },
              { "type": "text", "text": "• 輸入「早鳥」：試算產季早鳥保證工時包", "size": "sm", "weight": "bold", "color": "#1E293B" },
              { "type": "text", "text": "• 輸入「機耕」：查看無人機代噴與農機排程", "size": "sm", "weight": "bold", "color": "#1E293B" },
              { "type": "text", "text": "• 輸入「薪資」：查詢農業部 8,800 元獎勵與薪資", "size": "sm", "weight": "bold", "color": "#1E293B" },
              { "type": "text", "text": "• 輸入「黑轉白」：現有工班依身分轉為合法聘僱", "size": "sm", "weight": "bold", "color": "#1E293B" },
              { "type": "text", "text": "• 輸入「越南語」/「印尼語」：切換多語系介面", "size": "sm", "weight": "bold", "color": "#1E293B" }
            ]
          }
        }
        """;
        return wrapFlex("🌱 智農派工官方助理 - 服務導覽", bubble);
    }

    /**
     * 9. 現有工班「黑轉白」身分分流（規則與前端 農業派遣主網站/legalize.html 一致）
     * 欄位：指令關鍵字, 圖示, 身分, 轉換路徑, 路徑色, 是否改派補位, 說明
     */
    private static final String[][] LEGALIZE_TYPES = {
        {"本國臨時工", "🧑‍🌾", "本國籍臨時工（未投保）", "可直接轉白", "#059669", "N",
            "由合作社聘僱為農務士，簽約後依法加保勞保／就保／職災保險並申報薪資所得。"},
        {"新住民", "🏠", "新住民（外籍／陸籍配偶）", "可直接轉白", "#059669", "N",
            "依親居留之配偶在台工作免申請許可，上傳居留證審核後由合作社聘僱加保。陸籍配偶居留類別與工作資格【待確認】。"},
        {"外籍學生", "🎓", "外籍學生／僑生", "有條件轉白", "#D97706", "N",
            "須先取得工作許可，學期中每週最多 20 小時；許可前不得排班。農業勞務是否適用【待確認】。"},
        {"借用移工", "🔁", "借用他人雇主的合法移工", "改派合法工班補位", "#E11D48", "Y",
            "移工只能為申請許可的原雇主工作，借用即違法。請今日起停止使用並結清工資，空缺改由合作社外展工班補位。"},
        {"失聯移工", "⚠️", "失聯（逃逸）移工", "改派合法工班補位", "#E11D48", "Y",
            "依法無法就地合法化。請停止使用並結清工資，勿扣留證件；本人可洽移民署專勤隊或勞動部 1955 專線了解到案方式【待確認】。本系統不記錄其個資。"}
    };

    private static String webPageUrl(String page) {
        String site = URLEncoder.encode("農業派遣主網站", StandardCharsets.UTF_8);
        return Config.WEB_BASE_URL + "/" + site + "/" + page;
    }

    private static String messageButton(String label, String text) {
        return """
            { "type": "button", "style": "secondary", "height": "sm", "action": { "type": "message", "label": "%s", "text": "%s" } }"""
                .formatted(escapeJson(label), escapeJson(text));
    }

    private static String uriButton(String label, String uri, String color) {
        return """
            { "type": "button", "style": "primary", "height": "sm", "color": "%s", "action": { "type": "uri", "label": "%s", "uri": "%s" } }"""
                .formatted(color, escapeJson(label), escapeJson(uri));
    }

    /**
     * 依訊息內容回傳黑轉白卡片：含身分關鍵字回傳該身分的轉換路徑，否則回傳身分選單
     */
    public static String getLegalizeFlex(String text) {
        String clean = text == null ? "" : text;
        for (String[] t : LEGALIZE_TYPES) {
            if (clean.contains(t[0])) {
                return getLegalizeTypeFlex(t);
            }
        }
        return getLegalizeIntroFlex();
    }

    private static String getLegalizeIntroFlex() {
        StringBuilder buttons = new StringBuilder();
        for (String[] t : LEGALIZE_TYPES) {
            buttons.append(messageButton(t[1] + " " + t[2], "黑轉白：" + t[0])).append(",\n");
        }
        buttons.append(uriButton("📋 開啟轉換中心（整班盤點）", webPageUrl("legalize.html"), "#06C755"));

        String bubble = """
        {
          "type": "bubble",
          "header": {
            "type": "box",
            "layout": "vertical",
            "backgroundColor": "#0E7490",
            "paddingAll": "20px",
            "contents": [
              { "type": "text", "text": "智農派工 AgriDispatch", "color": "#A5F3FC", "size": "xs", "weight": "bold" },
              { "type": "text", "text": "🔄 現有工班黑轉白", "color": "#FFFFFF", "size": "lg", "weight": "bold" }
            ]
          },
          "body": {
            "type": "box",
            "layout": "vertical",
            "spacing": "sm",
            "paddingAll": "20px",
            "contents": [
              { "type": "text", "text": "請選擇這位工人目前的身分，我告訴您怎麼轉：", "size": "sm", "color": "#1E293B", "wrap": true },
              { "type": "text", "text": "※ 示範流程；標示【待確認】處以律師或主管機關意見為準", "size": "xxs", "color": "#64748B", "wrap": true }
            ]
          },
          "footer": {
            "type": "box",
            "layout": "vertical",
            "spacing": "sm",
            "paddingAll": "16px",
            "contents": [
%s
            ]
          }
        }
        """.formatted(buttons);
        return wrapFlex("🔄 現有工班黑轉白 - 請選擇工人身分", bubble);
    }

    private static String getLegalizeTypeFlex(String[] t) {
        boolean replace = "Y".equals(t[5]);
        String action = replace
                ? uriButton("🚐 改派合法工班補位", webPageUrl("farmer.html?workers=1&from=legalize"), "#06C755")
                : uriButton("✅ 到轉換中心開始轉白", webPageUrl("legalize.html"), "#06C755");

        String bubble = """
        {
          "type": "bubble",
          "header": {
            "type": "box",
            "layout": "vertical",
            "backgroundColor": "%s",
            "paddingAll": "20px",
            "contents": [
              { "type": "text", "text": "現有工班黑轉白", "color": "#FFFFFF", "size": "xs", "weight": "bold" },
              { "type": "text", "text": "%s %s", "color": "#FFFFFF", "size": "lg", "weight": "bold", "wrap": true }
            ]
          },
          "body": {
            "type": "box",
            "layout": "vertical",
            "spacing": "md",
            "paddingAll": "20px",
            "contents": [
              { "type": "box", "layout": "baseline", "contents": [
                  { "type": "text", "text": "轉換路徑", "size": "sm", "color": "#64748B", "flex": 2 },
                  { "type": "text", "text": "%s", "size": "sm", "color": "%s", "weight": "bold", "flex": 4 }
              ]},
              { "type": "text", "text": "%s", "size": "sm", "color": "#1E293B", "wrap": true }
            ]
          },
          "footer": {
            "type": "box",
            "layout": "vertical",
            "spacing": "sm",
            "paddingAll": "16px",
            "contents": [
%s,
%s
            ]
          }
        }
        """.formatted(t[4], t[1], escapeJson(t[2]), escapeJson(t[3]), t[4], escapeJson(t[6]),
                action, messageButton("↩ 選擇其他身分", "黑轉白"));
        return wrapFlex("🔄 黑轉白：" + t[2] + "（" + t[3] + "）", bubble);
    }

    /**
     * 包裝為標準 LINE Flex Message 結構
     */
    public static String wrapFlex(String altText, String contentsJson) {
        return """
        {
          "type": "flex",
          "altText": "%s",
          "contents": %s
        }
        """.formatted(escapeJson(altText), contentsJson.trim());
    }

    public static String wrapText(String text) {
        return """
        {
          "type": "text",
          "text": "%s"
        }
        """.formatted(escapeJson(text));
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }
}
