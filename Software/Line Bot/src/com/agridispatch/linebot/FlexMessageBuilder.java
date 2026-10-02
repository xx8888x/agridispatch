package com.agridispatch.linebot;

import java.io.File;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 智農派工 - Flex Message 構造器
 * 負責產生兩個官方帳號（農場主／工作者）各情境的 Flex Message JSON 結構，提供給 LINE Messaging API 回覆或推播
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

    // =========================================================================
    // 帳號綁定、服務導覽、跨帳號引導（規格 4.1、4.2、4.3、5.3）
    // =========================================================================

    /** 綁定頁網址：設定 LIFF_BIND_ID 時走 LIFF（規格 5.3），否則連到既有登入頁；網址不帶任何個資 */
    static String bindUrl(Channel channel, String role) {
        if (Config.LIFF_BIND_ID != null) {
            return "https://liff.line.me/" + Config.LIFF_BIND_ID + "?channel=" + channel.key + "&role=" + role;
        }
        return switch (role) {
            case Identity.WORKER_LOCAL -> webPageUrl("worker-local-login.html");
            case Identity.WORKER_FOREIGN -> webPageUrl("worker-foreign-login.html");
            default -> webPageUrl("farmer-login.html");
        };
    }

    /** 工人頁（打卡、出勤）：本勞與外勞各自一頁 */
    static String workerPageUrl(Identity identity) {
        return webPageUrl(identity.isForeign() ? "worker-foreign.html" : "worker-local.html");
    }

    /** F-07 農場主帳號綁定卡（不顯示 LINE userId） */
    public static String getFarmerBindingFlex() {
        return wrapFlex("🔗 智農派工 - 農場主帳號綁定", simpleBubble("#0F766E", "智農派工（農場主）", "🔗 帳號綁定",
                List.of("綁定後可查看您的訂單與派工確認單、天候對訂單的影響，並使用黑轉白服務。"),
                List.of(uriButton("👨‍🌾 農場主帳號綁定", bindUrl(Channel.FARMER, Identity.FARMER), "#059669"))));
    }

    /** W-07 工作者帳號綁定卡：本勞、外勞兩種入口 */
    public static String getWorkerBindingFlex(String lang) {
        return wrapFlex(I18n.t(lang, "bind.title"), simpleBubble("#0F766E", "智農派工（工作者）", I18n.t(lang, "bind.title"),
                List.of(I18n.t(lang, "bind.desc")),
                List.of(uriButton(I18n.t(lang, "bind.local"), bindUrl(Channel.WORKER, Identity.WORKER_LOCAL), "#059669"),
                        uriButton(I18n.t(lang, "bind.foreign"), bindUrl(Channel.WORKER, Identity.WORKER_FOREIGN), "#3B82F6"))));
    }

    /** F-08 農場主服務導覽（只列農場主功能） */
    public static String getFarmerHelpFlex() {
        return wrapFlex("🌱 智農派工（農場主）- 服務導覽", simpleBubble("#047857", "智農派工（農場主）", "🌱 服務導覽",
                List.of("請點下方選單，或輸入下列文字：",
                        "• 「派工」「訂單」：我的訂單與派工確認單",
                        "• 「黑轉白」：現有工班依身分轉為合法聘僱",
                        "• 「早鳥」：產季早鳥保證工時包",
                        "• 「避險」：天候對訂單的影響",
                        "• 「機耕」：無人機代噴與農機預約",
                        "• 「費率」「產曆」：服務費率與全台產曆",
                        "• 「綁定」：帳號綁定"),
                List.of()));
    }

    /** W-08 工作者使用說明（依語言） */
    public static String getWorkerHelpFlex(String lang) {
        return wrapFlex(I18n.t(lang, "help.title"), simpleBubble("#047857", "智農派工（工作者）", I18n.t(lang, "help.title"),
                List.of(I18n.t(lang, "help.intro"), I18n.t(lang, "help.task"), I18n.t(lang, "help.clock"),
                        I18n.t(lang, "help.weather"), I18n.t(lang, "help.salary"), I18n.t(lang, "help.attendance"),
                        I18n.t(lang, "help.lang")),
                List.of()));
    }

    /**
     * 4.3 跨帳號引導：使用者在 from 帳號問了另一個帳號的功能，只回引導、不回功能內容
     */
    public static String getCrossChannelFlex(Channel from, String lang) {
        Channel to = from == Channel.FARMER ? Channel.WORKER : Channel.FARMER;
        String title, body, label;
        if (from == Channel.FARMER) {
            title = "此帳號提供農場主使用";
            body = "工作人員（本勞、外勞）的任務、打卡、薪資與多語介面，請加入「" + to.displayName + "」帳號。";
            label = "加入工作者帳號";
        } else {
            title = I18n.t(lang, "cross.title");
            body = I18n.t(lang, "cross.body");
            label = I18n.t(lang, "cross.add");
        }
        String url = to.addFriendUrl();
        List<String> buttons = url == null ? List.of() : List.of(uriButton(label, url, "#06C755"));
        return wrapFlex(title, simpleBubble("#475569", from.displayName, "ℹ️ " + title, List.of(body), buttons));
    }

    /** W-06 語言選擇（三語並列；按鈕送出語言名稱，與選單 postback a=lang 效果相同） */
    public static String getLanguageSelectFlex() {
        return wrapFlex("🌐 請選擇語言 / Chọn ngôn ngữ / Pilih bahasa", simpleBubble("#4338CA", "智農派工（工作者）",
                "🌐 請選擇語言 / Chọn ngôn ngữ / Pilih bahasa", List.of(),
                List.of(messageButton("繁體中文", "繁體中文"),
                        messageButton("Tiếng Việt", "Tiếng Việt"),
                        messageButton("Bahasa Indonesia", "Bahasa Indonesia"))));
    }

    /** F-05 機耕代噴預約（農場主視角；機耕師的工單見 getMachineryTaskFlex） */
    public static String getMachineryBookingFlex() {
        return wrapFlex("🚜 機耕代噴預約", simpleBubble("#334155", "智農派工（農場主）", "🚜 機耕代噴預約",
                List.of("• 大疆 T50 植保機代噴（雙證照機耕師）",
                        "• 曳引機整地、深耕",
                        "線上填寫田區、作物與希望日期，由合作社排程後回覆。"),
                List.of(uriButton("📅 前往預約", webPageUrl("farmer.html"), "#06C755"))));
    }

    /** W-03 天候與集合通知（工人視角，依語言；P1 為示範資料，P2 起取本人當日班次） */
    public static String getWorkerWeatherFlex(String lang) {
        String bubble = """
        {
          "type": "bubble",
          "header": { "type": "box", "layout": "vertical", "backgroundColor": "#1D4ED8", "paddingAll": "20px", "contents": [
            { "type": "text", "text": "%s", "color": "#FFFFFF", "size": "lg", "weight": "bold", "wrap": true },
            { "type": "text", "text": "%s", "color": "#DBEAFE", "size": "sm", "wrap": true }
          ]},
          "body": { "type": "box", "layout": "vertical", "spacing": "md", "paddingAll": "20px", "contents": [
            { "type": "box", "layout": "baseline", "contents": [
              { "type": "text", "text": "%s", "size": "sm", "color": "#64748B", "flex": 2, "wrap": true },
              { "type": "text", "text": "%s", "size": "sm", "color": "#1E293B", "weight": "bold", "flex": 4, "wrap": true }
            ]},
            { "type": "box", "layout": "baseline", "contents": [
              { "type": "text", "text": "%s", "size": "sm", "color": "#64748B", "flex": 2, "wrap": true },
              { "type": "text", "text": "%s", "size": "sm", "color": "#1E293B", "weight": "bold", "flex": 4, "wrap": true }
            ]},
            { "type": "text", "text": "%s", "size": "sm", "color": "#1E293B", "wrap": true },
            { "type": "text", "text": "%s", "size": "xxs", "color": "#94A3B8" }
          ]}
        }
        """.formatted(escapeJson(I18n.t(lang, "weather.title")), escapeJson(I18n.t(lang, "weather.status")),
                escapeJson(I18n.t(lang, "weather.place.label")), escapeJson(I18n.t(lang, "weather.place")),
                escapeJson(I18n.t(lang, "weather.time.label")), escapeJson(I18n.t(lang, "weather.time")),
                escapeJson(I18n.t(lang, "weather.note")), escapeJson(I18n.t(lang, "weather.demo")));
        return wrapFlex(I18n.t(lang, "weather.title"), bubble);
    }

    /** W-01 附加：帶班師傅與工班組長的工班出勤摘要（P1 為示範資料） */
    public static String getCrewSummaryFlex(String lang) {
        return wrapFlex(I18n.t(lang, "crew.title"), simpleBubble("#1E40AF", "智農派工（工作者）", I18n.t(lang, "crew.title"),
                List.of(I18n.t(lang, "crew.body")),
                List.of(uriButton(I18n.t(lang, "crew.eval"), webPageUrl("worker-local-evaluation.html"), "#2563EB"))));
    }

    /** W-02 打卡、W-05 出勤紀錄：開啟既有工人頁 */
    public static String getWorkerLinkFlex(String lang, String keyPrefix, String url) {
        return wrapFlex(I18n.t(lang, keyPrefix + ".title"), simpleBubble("#047857", "智農派工（工作者）",
                I18n.t(lang, keyPrefix + ".title"), List.of(I18n.t(lang, keyPrefix + ".desc")),
                List.of(uriButton(I18n.t(lang, keyPrefix + ".open"), url, "#06C755"))));
    }

    /** 標題列＋若干段文字＋若干按鈕的通用卡片 */
    private static String simpleBubble(String headerBg, String subtitle, String title, List<String> lines, List<String> buttons) {
        StringBuilder body = new StringBuilder();
        for (String line : lines) {
            if (body.length() > 0) body.append(",\n");
            body.append("""
                { "type": "text", "text": "%s", "size": "sm", "color": "#1E293B", "wrap": true }""".formatted(escapeJson(line)));
        }
        String bodyBlock = lines.isEmpty() ? "" : """
          ,
          "body": { "type": "box", "layout": "vertical", "spacing": "sm", "paddingAll": "20px", "contents": [
        %s
          ]}""".formatted(body);
        String footer = buttons.isEmpty() ? "" : """
          ,
          "footer": { "type": "box", "layout": "vertical", "spacing": "sm", "paddingAll": "16px", "contents": [
        %s
          ]}""".formatted(String.join(",\n", buttons));
        return """
        {
          "type": "bubble",
          "header": { "type": "box", "layout": "vertical", "backgroundColor": "%s", "paddingAll": "20px", "contents": [
            { "type": "text", "text": "%s", "color": "#D1FAE5", "size": "xs", "weight": "bold" },
            { "type": "text", "text": "%s", "color": "#FFFFFF", "size": "lg", "weight": "bold", "wrap": true }
          ]}%s%s
        }
        """.formatted(headerBg, escapeJson(subtitle), escapeJson(title), bodyBlock, footer);
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
