package com.agridispatch.linebot;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.agridispatch.linebot.FarmerDispatcher.has;

/**
 * 智農派工 - 工作者帳號分派器（規格 4.2；本勞與外勞共用，多語）
 * W-01 今日任務、W-02 打卡、W-03 天候與集合、W-04 薪資、W-05 出勤紀錄、W-06 切換語言、W-07 帳號綁定、W-08 使用說明
 */
final class WorkerDispatcher {
    private WorkerDispatcher() {}

    private static final Channel CH = Channel.WORKER;

    static List<String> handle(BotEvent ev) {
        if (ev.type() == BotEvent.Type.UNFOLLOW) return List.of();
        if (ev.type() == BotEvent.Type.FOLLOW) return follow(ev);

        Identity me = IdentityStore.get(CH, ev.userId());
        String lang = me.lang();
        return switch (ev.type()) {
            case OTHER_MESSAGE -> List.of(FlexMessageBuilder.wrapText(I18n.t(lang, "unsupported")),
                    FlexMessageBuilder.getWorkerHelpFlex(lang));
            case POSTBACK -> run(ev.action(), ev.postback(), me, ev.userId());
            default -> {
                String t = ev.text() == null ? "" : ev.text().trim().toLowerCase();
                String chosen = langOf(t);
                yield chosen != null
                        ? run("lang", Map.of("v", chosen), me, ev.userId())
                        : run(actionOf(t, me), Map.of(), me, ev.userId());
            }
        };
    }

    /**
     * 加好友：語言依規格 3.2（主動選擇 > 國籍 > LINE 介面語言 > 中文）
     */
    private static List<String> follow(BotEvent ev) {
        // 追蹤狀態與 LINE 介面語言已由 LineBotDispatcher 記錄（IdentityStore.recordFollow）
        Identity me = IdentityStore.get(CH, ev.userId());
        String lang = me.lang();
        if (me.isWorker()) {
            return List.of(FlexMessageBuilder.wrapText(I18n.t(lang, "welcome.bound")),
                    FlexMessageBuilder.getWorkerHelpFlex(lang));
        }
        // 未綁定：三語歡迎＋語言選擇＋綁定卡（綁定卡用目前語言）
        String welcome = I18n.t("zh", "welcome.guest") + "\n\n" + I18n.t("vi", "welcome.guest")
                + "\n\n" + I18n.t("id", "welcome.guest");
        return List.of(FlexMessageBuilder.wrapText(welcome),
                FlexMessageBuilder.getLanguageSelectFlex(),
                FlexMessageBuilder.getWorkerBindingFlex(lang));
    }

    /** 文字中指定的語言；沒有指定回傳 null */
    static String langOf(String t) {
        if (has(t, "繁體中文", "中文", "繁體", "chinese", "tiếng trung")) return "zh";
        if (has(t, "越南", "tiếng việt", "viet")) return "vi";
        if (has(t, "印尼", "bahasa indonesia", "indonesia", "indo")) return "id";
        return null;
    }

    /** 文字訊息 → 動作代碼（與圖文選單 postback 的 a= 相同，規格 5.2） */
    static String actionOf(String t, Identity me) {
        if (has(t, "切換語言", "切換母語", "語言", "language", "ngôn ngữ", "ganti bahasa", "bahasa")) return "lang_select";
        // 機耕師問自己的工單（「機耕」「代噴」對其他人是農場主功能）
        if (me.isMachinery() && has(t, "機耕", "代噴", "無人機", "工單", "航跡")) return "task";
        // 農場主帳號的功能：只回引導（規格 4.3）
        if (has(t, "早鳥", "預購", "優惠", "工時包", "黑轉白", "黑工", "費率", "價格", "多少錢", "計費", "報價",
                "訂單", "單號", "ord-", "預約", "代噴", "機耕", "無人機", "產曆", "產季")) return "cross";
        if (has(t, "天氣", "天候", "避險", "暴雨", "颱風", "雨", "集合", "接駁", "專車", "接送",
                "thời tiết", "mưa", "cuaca", "hujan", "weather")) return "weather";
        if (has(t, "任務", "派工", "出工", "工單", "nhiệm vụ", "tugas", "task")) return "task";
        if (has(t, "打卡", "chấm công", "absen", "clock")) return "clock";
        if (has(t, "薪資", "津貼", "8800", "獎勵", "lương", "gaji", "salary")) return "salary";
        if (has(t, "出勤", "紀錄", "ngày công", "kehadiran", "attendance")) return "attendance";
        if (has(t, "綁定", "帳號", "bind", "liên kết", "tài khoản", "hubungkan", "akun")) return "account";
        return "help";
    }

    private static List<String> run(String action, Map<String, String> params, Identity me, String userId) {
        String lang = me.lang();
        switch (action) {
            case "task": {
                if (!me.isWorker()) return bindingRequired(lang);
                List<String> out = new ArrayList<>();
                if (me.isMachinery()) {
                    if (!"zh".equals(lang)) out.add(FlexMessageBuilder.wrapText(I18n.t(lang, "fallback.zh")));
                    out.add(FlexMessageBuilder.getMachineryTaskFlex());
                } else {
                    out.add(FlexMessageBuilder.getWorkerTaskFlex(lang));
                }
                if (me.isCrewLead()) out.add(FlexMessageBuilder.getCrewSummaryFlex(lang));
                return out;
            }
            case "clock":
                if (!me.isWorker()) return bindingRequired(lang);
                return List.of(FlexMessageBuilder.getWorkerLinkFlex(lang, "clock", FlexMessageBuilder.workerPageUrl(me)));
            case "weather":
                if (!me.isWorker()) return bindingRequired(lang);
                return List.of(FlexMessageBuilder.getWorkerWeatherFlex(lang));
            case "salary": {
                if (!me.isWorker()) return bindingRequired(lang);
                // 8,800 元就業獎勵的適用對象【待確認】；確認前維持現行卡片內容
                List<String> out = new ArrayList<>();
                if (!"zh".equals(lang)) out.add(FlexMessageBuilder.wrapText(I18n.t(lang, "fallback.zh")));
                out.add(FlexMessageBuilder.getSalaryBonusFlex());
                return out;
            }
            case "attendance":
                if (!me.isWorker()) return bindingRequired(lang);
                return List.of(FlexMessageBuilder.getWorkerLinkFlex(lang, "attendance", FlexMessageBuilder.workerPageUrl(me)));
            case "lang": {
                String v = I18n.normalize(params.get("v"));
                Identity updated = IdentityStore.setLang(CH, userId, v);
                return List.of(FlexMessageBuilder.wrapText(I18n.t(v, "lang.switched")),
                        updated.isWorker() ? FlexMessageBuilder.getWorkerHelpFlex(v) : FlexMessageBuilder.getWorkerBindingFlex(v));
            }
            case "lang_select":
                return List.of(FlexMessageBuilder.getLanguageSelectFlex());
            case "account":
                return List.of(FlexMessageBuilder.getWorkerBindingFlex(lang));
            case "cross":
                return List.of(FlexMessageBuilder.getCrossChannelFlex(CH, lang));
            case "help":
                return List.of(FlexMessageBuilder.getWorkerHelpFlex(lang));
            default:
                System.err.println("⚠️ [worker] 未知的 postback 動作: " + action);
                return List.of(FlexMessageBuilder.getWorkerHelpFlex(lang));
        }
    }

    private static List<String> bindingRequired(String lang) {
        return List.of(FlexMessageBuilder.wrapText(I18n.t(lang, "bind.required")),
                FlexMessageBuilder.getWorkerBindingFlex(lang));
    }
}
