package com.agridispatch.linebot;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 智農派工 - 農場主帳號的本人資料卡片（規格 4.1 F-01 我的訂單、F-04 天候避險；農場主帳號只用中文）
 * 資料來自後端 POST /api/line/internal/query 的 data（BackendClient.data）。
 */
final class FarmerCards {
    private FarmerCards() {}

    private static final String SUBTITLE = "智農派工（農場主）";

    /** F-01 我的訂單：一筆訂單明細，附其餘訂單摘要 */
    static String orders(Map<String, Object> d) {
        String ordersUrl = FlexMessageBuilder.webPageUrl("farmer.html");
        if (!(d.get("order") instanceof Map)) {
            return FlexMessageBuilder.wrapFlex("我的訂單", FlexMessageBuilder.simpleBubble("#047857", SUBTITLE, "我的訂單",
                    List.of("目前查不到您名下的訂單。可到網站「我要派工」下單，或輸入「服務導覽」查看功能。"),
                    List.of(FlexMessageBuilder.uriButton("前往我要派工", ordersUrl, "#06C755"))));
        }
        Map<String, Object> o = map(d.get("order"));
        String title = s(o, "id") + "｜" + s(o, "statusLabel");
        List<String> lines = new ArrayList<>();
        lines.add("作物：" + join(s(o, "cropName"), s(o, "categoryName"), area(o.get("area"))));
        String farm = s(o, "farmName").isEmpty() ? s(o, "farmLocation") : s(o, "farmName");
        if (!farm.isEmpty()) lines.add("農場：" + farm);
        lines.add("工期：" + period(o));
        lines.add("指派工班：" + or(s(o, "teamName"), "待安排") + (num(o.get("workersPerDay")) > 0 ? "（每日 " + s(o, "workersPerDay") + " 人）" : ""));
        lines.add("帶隊對口：" + or(join(s(o, "foremanName"), s(o, "foremanPhone")), "待通知"));
        if (!s(o, "foremanNotes").isEmpty()) lines.add("現場交辦：" + s(o, "foremanNotes"));
        if (!s(o, "preferredWorkerName").isEmpty()) lines.add("指名工人：" + s(o, "preferredWorkerName"));
        lines.add("金額：" + money(o.get("finalAmount")) + "（定金 " + money(o.get("depositAmount"))
                + (Boolean.TRUE.equals(o.get("depositPaid")) ? " 已付）" : " 未付）"));
        List<Map<String, Object>> others = list(d.get("others"));
        if (!others.isEmpty()) {
            List<String> brief = new ArrayList<>();
            for (Map<String, Object> x : others) {
                brief.add(s(x, "id") + "（" + join(s(x, "statusLabel"), s(x, "startDate"), s(x, "cropName")) + "）");
            }
            lines.add("其他訂單（共 " + s(d, "total") + " 筆）：" + String.join("、", brief) + "。輸入單號可查看該筆。");
        }
        List<String> buttons = new ArrayList<>();
        buttons.add(FlexMessageBuilder.uriButton("查看我的訂單", ordersUrl, "#06C755"));
        String phone = s(o, "foremanPhone").replaceAll("[^0-9+]", "");
        if (!phone.isEmpty()) buttons.add(FlexMessageBuilder.uriButton("撥打帶隊對口", "tel:" + phone, "#2563EB"));
        return FlexMessageBuilder.wrapFlex("我的訂單 " + title, FlexMessageBuilder.simpleBubble("#047857", SUBTITLE, title, lines, buttons));
    }

    /** F-04 天候避險：目前天候＋本人施工中或近期開工的訂單 × 產區天氣、工班是否改室內 */
    static String weather(Map<String, Object> d) {
        String level = s(d, "alertLevel");
        boolean alert = "warning".equals(level) || "danger".equals(level);
        List<String> lines = new ArrayList<>();
        lines.add(s(d, "date") + "・目前天候：" + or(s(d, "weather"), "—"));
        List<Map<String, Object>> orders = list(d.get("orders"));
        for (Map<String, Object> o : orders) {
            lines.add("【" + s(o, "id") + "】" + join(s(o, "cropName"), s(o, "place")));
            lines.add("工期：" + period(o));
            if (o.get("region") instanceof Map) {
                Map<String, Object> r = map(o.get("region"));
                lines.add("當地：" + join(s(r, "condition"), s(r, "temp"), s(r, "rainChance").isEmpty() ? "" : "降雨 " + s(r, "rainChance")));
                if (!s(r, "advice").isEmpty()) lines.add("建議：" + s(r, "advice"));
            }
            boolean today = Boolean.TRUE.equals(o.get("workingToday"));
            if (Boolean.TRUE.equals(o.get("teamIndoor"))) lines.add("⚠️ 今日工班改到室內避險作業，田間作業暫停；順延安排請洽合作社。");
            else if (today && alert) lines.add("⚠️ 天候警戒中，今日作業可能調整，合作社會再通知您。");
            else if (today) lines.add("今日照常作業。");
            else lines.add(s(o, "startDate") + " 開工，出發前會依天候再確認。");
        }
        if (orders.isEmpty()) lines.add("目前沒有施工中或 3 天內開工的訂單，不受天候影響。");
        String bg = "danger".equals(level) ? "#B91C1C" : "warning".equals(level) ? "#B45309" : "#1D4ED8";
        String alt = "天候避險：" + or(s(d, "weather"), "目前天候") + (orders.isEmpty() ? "" : "（" + orders.size() + " 筆訂單）");
        return FlexMessageBuilder.wrapFlex(alt, FlexMessageBuilder.simpleBubble(bg, SUBTITLE, "天候避險", lines,
                List.of(FlexMessageBuilder.uriButton("查看我的訂單", FlexMessageBuilder.webPageUrl("farmer.html"), "#06C755"))));
    }

    /** 正式模式下後端查不到時：不顯示示範資料，改請農場主到網站查看 */
    static List<String> unavailable() {
        return List.of(FlexMessageBuilder.wrapFlex("暫時查不到您的資料", FlexMessageBuilder.simpleBubble("#475569", SUBTITLE,
                "暫時查不到您的資料", List.of("請稍後再試，或到網站「我的訂單」查看。"),
                List.of(FlexMessageBuilder.uriButton("查看我的訂單", FlexMessageBuilder.webPageUrl("farmer.html"), "#06C755")))));
    }

    // -------------------------------------------------------------------------

    private static String period(Map<String, Object> o) {
        if (s(o, "startDate").isEmpty()) return "待排定";
        return s(o, "startDate") + " ～ " + s(o, "endDate") + (s(o, "timeSlotLabel").isEmpty() ? "" : "・" + s(o, "timeSlotLabel"));
    }

    private static String area(Object a) {
        double v = num(a);
        if (v <= 0) return "";
        return (v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v)) + " 公頃";
    }

    private static String money(Object v) {
        return String.format("NT$ %,d", Math.round(num(v)));
    }

    private static double num(Object v) {
        return v instanceof Number n ? n.doubleValue() : 0;
    }

    private static String or(String v, String fallback) {
        return v.isEmpty() ? fallback : v;
    }

    private static String join(String... parts) {
        List<String> out = new ArrayList<>();
        for (String p : parts) if (p != null && !p.isBlank()) out.add(p.trim());
        return String.join("・", out);
    }

    private static String s(Map<String, Object> m, String k) {
        Object v = m.get(k);
        if (v instanceof Double dv && dv == Math.rint(dv)) return String.valueOf(dv.longValue());
        return v == null ? "" : String.valueOf(v);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o) {
        return (Map<String, Object>) o;
    }

    private static List<Map<String, Object>> list(Object o) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (o instanceof List<?> l) for (Object x : l) if (x instanceof Map) out.add(map(x));
        return out;
    }
}
