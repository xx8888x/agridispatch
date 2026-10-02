package com.agridispatch.linebot;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 智農派工 - 主動推播的訊息內容（規格第柒章）
 * 後端只送「通知種類＋參數＋收件人」，訊息由這裡依收件人語言產生（工作者帳號三語，農場主帳號中文）。
 */
public final class NotifyMessages {
    private NotifyMessages() {}

    public static final Set<String> FARMER_TYPES = Set.of("order_status", "deposit_paid", "weather_farmer");
    public static final Set<String> WORKER_TYPES = Set.of("tomorrow_task", "weather_worker");

    public static boolean supports(Channel channel, String type) {
        return (channel == Channel.FARMER ? FARMER_TYPES : WORKER_TYPES).contains(type);
    }

    /** 產生一組訊息（LINE 訊息物件 JSON） */
    public static List<String> render(Channel channel, String type, String lang, Map<String, Object> p) {
        return channel == Channel.FARMER ? farmer(type, p) : worker(type, I18n.normalize(lang), p);
    }

    private static List<String> farmer(String type, Map<String, Object> p) {
        String orderUrl = FlexMessageBuilder.webPageUrl("farmer.html");
        switch (type) {
            case "order_status": {
                List<String> lines = new ArrayList<>();
                lines.add("訂單 " + s(p, "orderId") + "：" + s(p, "statusLabel"));
                if (!s(p, "crop").isEmpty()) lines.add("作物：" + s(p, "crop"));
                if (!s(p, "period").isEmpty()) lines.add("工期：" + s(p, "period"));
                String hint = switch (s(p, "status")) {
                    case "confirmed" -> "已排定工班，開工前一天會通知工班集合資訊。";
                    case "in_progress" -> "工班已開始作業。";
                    case "completed" -> "已完工，歡迎到網站為這次的工班評價。";
                    case "cancelled" -> "這筆訂單已取消。";
                    default -> "";
                };
                if (!hint.isEmpty()) lines.add(hint);
                return List.of(FlexMessageBuilder.notifyBubble("📋 訂單 " + s(p, "orderId") + " 狀態更新：" + s(p, "statusLabel"),
                        "#047857", "智農派工（農場主）", "📋 訂單狀態更新", lines,
                        FlexMessageBuilder.notifyUriButton("查看訂單", orderUrl)));
            }
            case "deposit_paid":
                return List.of(FlexMessageBuilder.notifyBubble("✅ 已收到訂單 " + s(p, "orderId") + " 定金",
                        "#047857", "智農派工（農場主）", "✅ 已收到定金",
                        List.of("訂單 " + s(p, "orderId") + (s(p, "crop").isEmpty() ? "" : "（" + s(p, "crop") + "）"),
                                "定金 NT$ " + money(p.get("amount")) + " 已入帳",
                                "目前狀態：" + s(p, "statusLabel")),
                        FlexMessageBuilder.notifyUriButton("查看訂單", orderUrl)));
            case "weather_farmer":
                return List.of(FlexMessageBuilder.notifyBubble("🌧️ 天候警戒：" + s(p, "weather"),
                        "#1D4ED8", "智農派工（農場主）", "🌧️ 天候警戒通知",
                        List.of(s(p, "weather"),
                                "您施工中的訂單 " + list(p.get("orders")) + " 可能受影響。",
                                "合作社正依天候調度工班；若需改期或改到室內作業，會再個別通知您。"),
                        FlexMessageBuilder.notifyUriButton("查看訂單", orderUrl)));
            default:
                return List.of();
        }
    }

    private static List<String> worker(String type, String lang, Map<String, Object> p) {
        switch (type) {
            case "tomorrow_task": {
                List<String> lines = new ArrayList<>();
                lines.add(I18n.t(lang, "notify.date") + "：" + s(p, "date"));
                if (!s(p, "crop").isEmpty()) lines.add(I18n.t(lang, "notify.crop") + "：" + s(p, "crop"));
                if (!s(p, "place").isEmpty()) lines.add(I18n.t(lang, "notify.place") + "：" + s(p, "place"));
                if (!s(p, "leader").isEmpty()) lines.add(I18n.t(lang, "notify.leader") + "：" + s(p, "leader"));
                lines.add(I18n.t(lang, "notify.tomorrow.note"));
                String title = I18n.t(lang, "notify.tomorrow.title");
                return List.of(FlexMessageBuilder.notifyBubble(title + " " + s(p, "date"), "#047857", "智農派工（工作者）", title, lines,
                        FlexMessageBuilder.notifyPostbackButton(I18n.t(lang, "notify.tomorrow.button"), "a=task")));
            }
            case "weather_worker": {
                boolean indoor = Boolean.TRUE.equals(p.get("indoor")) || "true".equals(String.valueOf(p.get("indoor")));
                String title = I18n.t(lang, "notify.weather.title");
                List<String> lines = List.of(I18n.t(lang, indoor ? "notify.weather.indoor" : "notify.weather.field"),
                        I18n.t(lang, "notify.weather.label") + "：" + s(p, "weather"));
                return List.of(FlexMessageBuilder.notifyBubble(title, "#1D4ED8", "智農派工（工作者）", title, lines,
                        FlexMessageBuilder.notifyPostbackButton(I18n.t(lang, "notify.weather.button"), "a=weather")));
            }
            default:
                return List.of();
        }
    }

    private static String s(Map<String, Object> p, String k) {
        Object v = p.get(k);
        return v == null ? "" : String.valueOf(v);
    }

    private static String money(Object v) {
        try {
            return String.format("%,d", Math.round(Double.parseDouble(String.valueOf(v))));
        } catch (Exception e) {
            return String.valueOf(v);
        }
    }

    private static String list(Object v) {
        if (v instanceof List<?> l) {
            List<String> out = new ArrayList<>();
            for (Object o : l) out.add(String.valueOf(o));
            return String.join("、", out);
        }
        return v == null ? "" : String.valueOf(v);
    }
}
