package com.agridispatch.linebot;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 智農派工 - 農場主帳號分派器（規格 4.1）
 * F-01 我的訂單、F-02 黑轉白、F-03 早鳥、F-04 天候避險、F-05 機耕代噴預約、F-06 費率與產曆、F-07 帳號綁定、F-08 服務導覽
 */
final class FarmerDispatcher {
    private FarmerDispatcher() {}

    private static final Channel CH = Channel.FARMER;

    /** 支援的 postback 動作（圖文選單 data 的 a=，TestSimulator 據此檢核選單） */
    static final Set<String> ACTIONS = Set.of("orders", "legalize", "weather", "early_bird", "machinery_book",
            "rates", "calendar", "rates_calendar", "account", "help", "cross");

    /** 黑轉白身分代碼（postback t=）→ FlexMessageBuilder.LEGALIZE_TYPES 的指令關鍵字 */
    private static final Map<String, String> LEGALIZE_KEYS = Map.of(
            "local", "本國臨時工", "spouse", "新住民", "student", "外籍學生",
            "borrowed", "借用移工", "runaway", "失聯移工");

    static List<String> handle(BotEvent ev) {
        Identity me = IdentityStore.get(CH, ev.userId());
        return switch (ev.type()) {
            case UNFOLLOW -> List.of();
            case FOLLOW -> me.isFarmer()
                    ? List.of(FlexMessageBuilder.wrapText("歡迎回來！請點下方選單查看訂單與派工確認單。"),
                              FlexMessageBuilder.getFarmerHelpFlex())
                    : List.of(FlexMessageBuilder.wrapText("歡迎加入「" + CH.displayName + "」！完成帳號綁定後即可查看您的訂單與派工確認單。"),
                              FlexMessageBuilder.getFarmerBindingFlex(ev.userId()));
            case OTHER_MESSAGE -> List.of(FlexMessageBuilder.wrapText("目前只看得懂文字訊息，請點下方選單或輸入文字。"),
                    FlexMessageBuilder.getFarmerHelpFlex());
            case POSTBACK -> run(ev.action(), ev.postback(), "", me, ev.userId());
            case TEXT -> run(actionOf(ev.text()), Map.of(), ev.text(), me, ev.userId());
        };
    }

    /** 文字訊息 → 動作代碼（與圖文選單 postback 的 a= 相同，規格 5.2） */
    static String actionOf(String raw) {
        String t = raw == null ? "" : raw.trim().toLowerCase();
        // 黑轉白須在「派工」之前判斷，避免「改派」等字被派工規則吃掉；功能下架時不辨識（落到服務導覽）
        if (Config.FEATURE_LEGALIZE && has(t, "黑轉白", "黑工")) return "legalize";
        if (has(t, "派工", "訂單", "單號", "ord-", "工單", "進度")) return "orders";
        if (has(t, "避險", "天氣", "天候", "暴雨", "颱風", "雨")) return "weather";
        if (has(t, "早鳥", "預購", "優惠", "工時包", "early")) return "early_bird";
        if (has(t, "機耕", "代噴", "無人機", "曳引機", "代耕", "農機")) return "machinery_book";
        if (has(t, "費率", "價格", "多少錢", "計費", "報價")) return "rates";
        if (has(t, "產曆", "產季", "收穫", "採收")) return "calendar";
        if (has(t, "綁定", "帳號", "bind")) return "account";
        if (has(t, "服務導覽", "說明", "help", "選單")) return "help";
        // 工作者帳號的功能：只回引導（規格 4.3）
        if (has(t, "薪資", "津貼", "8800", "打卡", "出勤", "任務", "lương", "gaji", "nhiệm vụ", "tugas",
                "越南", "印尼", "tiếng việt", "bahasa", "語言", "language")) return "cross";
        return "help";
    }

    private static List<String> run(String action, Map<String, String> params, String text, Identity me, String userId) {
        switch (action) {
            case "orders": {
                if (!me.isFarmer()) return bindingRequired(userId);
                // 後端只回本人訂單（規格 4.1）；後端離線或沒有綁定資料（Mock 示範農場主）時改用派工確認單範本
                List<String> reply = BackendClient.query(CH, userId, "orders", text);
                return reply != null && !reply.isEmpty() ? reply : List.of(FlexMessageBuilder.getFarmerOrderFlex());
            }
            case "legalize": {
                // 功能下架（Config.FEATURE_LEGALIZE=false）：手機上尚未更新的舊圖文選單仍可能送來 a=legalize
                if (!Config.FEATURE_LEGALIZE) {
                    return List.of(FlexMessageBuilder.wrapText("工班合法化（黑轉白）服務暫停提供，請點下方選單使用其他功能。"),
                            FlexMessageBuilder.getFarmerHelpFlex());
                }
                if (!me.isFarmer()) return bindingRequired(userId);
                String key = params.get("t");
                String typeText = key != null && LEGALIZE_KEYS.containsKey(key) ? LEGALIZE_KEYS.get(key) : text;
                return List.of(FlexMessageBuilder.getLegalizeFlex(typeText));
            }
            case "weather":
                if (!me.isFarmer()) return bindingRequired(userId);
                return List.of(FlexMessageBuilder.getWeatherHedgeFlex());
            case "early_bird":
                return List.of(FlexMessageBuilder.getEarlyBirdFlex());
            case "machinery_book":
                return List.of(FlexMessageBuilder.getMachineryBookingFlex());
            case "rates":
            case "calendar":
                return backendOrHelp(action, userId, text);
            case "rates_calendar": {
                // 圖文選單「費率與產曆」：兩張卡一起回
                List<String> out = new ArrayList<>();
                for (String a : List.of("rates", "calendar")) {
                    List<String> reply = BackendClient.query(CH, userId, a, "");
                    if (reply != null) out.addAll(reply);
                }
                return out.isEmpty() ? List.of(FlexMessageBuilder.getFarmerHelpFlex()) : out;
            }
            case "account":
                return List.of(FlexMessageBuilder.getFarmerBindingFlex(userId));
            case "cross":
                return List.of(FlexMessageBuilder.getCrossChannelFlex(CH, "zh"));
            case "help":
                return List.of(FlexMessageBuilder.getFarmerHelpFlex());
            default:
                RichMenus.UNKNOWN_ACTIONS.incrementAndGet();
                System.err.println("[farmer] 未知的 postback 動作: " + action);
                return List.of(FlexMessageBuilder.getFarmerHelpFlex());
        }
    }

    private static List<String> backendOrHelp(String action, String userId, String text) {
        List<String> reply = BackendClient.query(CH, userId, action, text);
        return reply != null && !reply.isEmpty() ? reply : List.of(FlexMessageBuilder.getFarmerHelpFlex());
    }

    private static List<String> bindingRequired(String userId) {
        return List.of(FlexMessageBuilder.wrapText("這項功能需要先綁定農場主帳號。"),
                FlexMessageBuilder.getFarmerBindingFlex(userId));
    }

    static boolean has(String text, String... keywords) {
        for (String k : keywords) {
            if (text.contains(k)) return true;
        }
        return false;
    }
}
