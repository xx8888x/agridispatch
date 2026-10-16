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

    public static final Set<String> FARMER_TYPES = Set.of("order_status", "deposit_paid", "weather_farmer", "leave_farmer");
    public static final Set<String> WORKER_TYPES = Set.of("tomorrow_task", "weather_worker", "salary_paid", "leave_decided", "leave_team");

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
                return List.of(FlexMessageBuilder.notifyBubble("訂單 " + s(p, "orderId") + " 狀態更新：" + s(p, "statusLabel"),
                        "#047857", "智農派工（農場主）", "訂單狀態更新", lines,
                        FlexMessageBuilder.notifyUriButton("查看訂單", orderUrl)));
            }
            case "deposit_paid":
                return List.of(FlexMessageBuilder.notifyBubble("已收到訂單 " + s(p, "orderId") + " 定金",
                        "#047857", "智農派工（農場主）", "已收到定金",
                        List.of("訂單 " + s(p, "orderId") + (s(p, "crop").isEmpty() ? "" : "（" + s(p, "crop") + "）"),
                                "定金 NT$ " + money(p.get("amount")) + " 已入帳",
                                "目前狀態：" + s(p, "statusLabel")),
                        FlexMessageBuilder.notifyUriButton("查看訂單", orderUrl)));
            case "weather_farmer":
                return List.of(FlexMessageBuilder.notifyBubble("天候警戒：" + s(p, "weather"),
                        "#1D4ED8", "智農派工（農場主）", "天候警戒通知",
                        List.of(s(p, "weather"),
                                "您施工中的訂單 " + list(p.get("orders")) + " 可能受影響。",
                                "合作社正依天候調度工班；若需改期或改到室內作業，會再個別通知您。"),
                        FlexMessageBuilder.notifyUriButton("查看訂單", orderUrl)));
            case "leave_farmer": {
                // 工人請假核准，或已核准的假被取消：只推給工人已排入的訂單的農場主
                boolean cancelled = "cancelled".equals(s(p, "status"));
                String title = cancelled ? "工人請假已取消" : "您的訂單有工人請假";
                List<String> lines = new ArrayList<>();
                lines.add("工人：" + s(p, "workerName"));
                lines.add("假別：" + s(p, "leaveType"));
                lines.add("請假期間：" + s(p, "period"));
                lines.add((cancelled ? "原受影響訂單：" : "受影響訂單：") + list(p.get("orders")));
                lines.add(cancelled ? "請假已取消，該工人的出工名單回復。" : "合作社會安排改派，工班人力若有異動會再通知您。");
                return List.of(FlexMessageBuilder.notifyBubble(title + " " + s(p, "period"), cancelled ? "#047857" : "#B45309",
                        "智農派工（農場主）", title, lines, FlexMessageBuilder.notifyUriButton("查看訂單", orderUrl)));
            }
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
            case "salary_paid": {
                // 後台在合作社實際撥款後手動發送，金額為實發數字（不是試算）
                String title = I18n.t(lang, "notify.salary.title");
                List<String> lines = new ArrayList<>();
                lines.add(I18n.t(lang, "notify.salary.month") + "：" + s(p, "month"));
                lines.add(I18n.t(lang, "notify.salary.amount") + "：NT$ " + money(p.get("netPay")));
                if (!s(p, "payDate").isEmpty()) lines.add(I18n.t(lang, "notify.salary.paydate") + "：" + s(p, "payDate"));
                lines.add(I18n.t(lang, "notify.salary.note"));
                return List.of(FlexMessageBuilder.notifyBubble(title + " " + s(p, "month"), "#6D28D9", "智農派工（工作者）", title, lines,
                        FlexMessageBuilder.notifyPostbackButton(I18n.t(lang, "notify.salary.button"), "a=salary")));
            }
            case "leave_decided": {
                // 合作社核准／駁回請假，或取消已核准的假；假別依代碼翻譯，沒有翻譯時用後端送來的中文名稱
                String status = s(p, "status");
                String title = I18n.t(lang, "notify.leave.title." + status);
                if (title.startsWith("notify.leave.")) title = I18n.t(lang, "notify.leave.title.approved");
                String typeKey = "notify.leave.type." + s(p, "leaveTypeCode");
                String typeName = I18n.t(lang, typeKey);
                if (typeName.equals(typeKey)) typeName = s(p, "leaveType");
                List<String> lines = new ArrayList<>();
                lines.add(I18n.t(lang, "notify.leave.type") + "：" + typeName);
                lines.add(I18n.t(lang, "notify.leave.period") + "：" + s(p, "period"));
                if (!s(p, "note").isEmpty()) {
                    lines.add(I18n.t(lang, "rejected".equals(status) ? "notify.leave.reason" : "notify.leave.note") + "：" + s(p, "note"));
                }
                String hint = I18n.t(lang, "notify.leave.hint." + status);
                if (!hint.startsWith("notify.leave.")) lines.add(hint);
                String color = "approved".equals(status) ? "#047857" : "#B45309";
                return List.of(FlexMessageBuilder.notifyBubble(title + " " + s(p, "period"), color, "智農派工（工作者）", title, lines,
                        FlexMessageBuilder.notifyPostbackButton(I18n.t(lang, "notify.leave.button"), "a=attendance")));
            }
            case "leave_team": {
                // 領班：組員申請、核准、駁回或取消請假（領班為本國工人，訊息一律中文）
                String status = s(p, "status");
                String title = switch (status) {
                    case "approved" -> "組員請假已核准";
                    case "rejected" -> "組員請假已駁回";
                    case "cancelled" -> "組員請假已取消";
                    default -> "組員申請請假";
                };
                List<String> lines = new ArrayList<>();
                lines.add("組員：" + s(p, "workerName"));
                lines.add("假別：" + s(p, "leaveType"));
                lines.add("請假期間：" + s(p, "period"));
                if (!s(p, "note").isEmpty()) lines.add(("rejected".equals(status) ? "原因：" : "備註：") + s(p, "note"));
                lines.add(switch (status) {
                    case "approved" -> "請假期間不會排入出工名單，請留意工班人力。";
                    case "cancelled" -> "請假已取消，該組員恢復可排班。";
                    case "rejected" -> "該組員照常出工。";
                    default -> "合作社審核中，結果會再通知您。";
                });
                String color = "approved".equals(status) || "cancelled".equals(status) ? "#B45309" : "#047857";
                return List.of(FlexMessageBuilder.notifyBubble(title + " " + s(p, "period"), color, "智農派工（工作者）", title, lines,
                        FlexMessageBuilder.notifyPostbackButton("查看今日任務", "a=task")));
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
