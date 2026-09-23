package com.agridispatch.linebot;

import java.io.File;
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
        return wrapFlex("⚡ 2026 產季早鳥保證工時包 - 預付20%定金享9折", 
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
              { "type": "text", "text": "• 輸入「越南語」/「印尼語」：切換多語系介面", "size": "sm", "weight": "bold", "color": "#1E293B" }
            ]
          }
        }
        """;
        return wrapFlex("🌱 智農派工官方助理 - 服務導覽", bubble);
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
