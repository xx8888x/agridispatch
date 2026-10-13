package com.agridispatch.linebot;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 智農派工 - 工作者帳號的本人資料卡片（規格 4.2 W-01 今日任務、W-03 天候與集合、W-04 薪資）
 * 資料來自後端 POST /api/line/internal/query 的 data（BackendClient.workerData），文字依使用者語言（I18n）；
 * 地點、作物等資料內容維持後端原文。
 */
final class WorkerCards {
    private WorkerCards() {}

    private static final String SUBTITLE = "智農派工（工作者）";

    /** W-01 今日任務；組長另附工班打卡摘要 */
    static List<String> task(String lang, Map<String, Object> d, String workerPageUrl) {
        boolean machinery = Boolean.TRUE.equals(d.get("machinery"));
        String title = I18n.t(lang, machinery ? "task.title.machinery" : "task.title");
        List<String> lines = new ArrayList<>();
        lines.add(s(d, "date") + "・" + s(d, "workerName"));
        List<Map<String, Object>> items = list(d.get("items"));
        for (Map<String, Object> it : items) {
            lines.add(heading(it, s(it, "statusLabel")));
            lines.add(label(lang, "task.place", orPending(lang, s(it, "place"))));
            String work = join(s(it, "categoryName"), s(it, "cropName"), area(lang, it.get("area")));
            if (!work.isEmpty()) lines.add(label(lang, "task.work", work));
            String foreman = join(s(it, "foremanName"), s(it, "foremanPhone"));
            if (!foreman.isEmpty()) lines.add(label(lang, "task.foreman", foreman));
            if (!s(it, "foremanNotes").isEmpty()) lines.add(label(lang, "task.notes", s(it, "foremanNotes")));
            if (Boolean.TRUE.equals(it.get("preferredByFarmer"))) lines.add(I18n.t(lang, "task.preferred"));
        }
        if (items.isEmpty()) {
            lines.add(I18n.t(lang, "task.none"));
            Object n = d.get("next");
            if (n instanceof Map) {
                Map<String, Object> next = map(n);
                lines.add(label(lang, "task.next", join(s(next, "startDate"), s(next, "timeSlotLabel"),
                        orPending(lang, s(next, "place")), s(next, "cropName"))));
            } else {
                lines.add(I18n.t(lang, "task.none.later"));
            }
        }
        List<String> out = new ArrayList<>();
        out.add(FlexMessageBuilder.wrapFlex(title, FlexMessageBuilder.simpleBubble("#047857", SUBTITLE, title, lines,
                List.of(FlexMessageBuilder.uriButton(I18n.t(lang, "clock.open"), workerPageUrl, "#06C755")))));
        if (d.get("crew") instanceof Map) out.add(crew(lang, map(d.get("crew"))));
        return out;
    }

    private static String crew(String lang, Map<String, Object> c) {
        String title = I18n.t(lang, "crew.title");
        List<String> lines = new ArrayList<>();
        lines.add(I18n.t(lang, "crew.summary").replace("{team}", s(c, "teamName"))
                .replace("{total}", s(c, "total")).replace("{present}", s(c, "present")));
        List<String> absent = new ArrayList<>();
        if (c.get("absentNames") instanceof List<?> names) names.forEach(x -> absent.add(String.valueOf(x)));
        if (!absent.isEmpty()) lines.add(label(lang, "crew.absent", String.join("、", absent)));
        return FlexMessageBuilder.wrapFlex(title, FlexMessageBuilder.simpleBubble("#1E40AF", SUBTITLE, title, lines,
                List.of(FlexMessageBuilder.uriButton(I18n.t(lang, "crew.eval"),
                        FlexMessageBuilder.webPageUrl("worker-local-evaluation.html"), "#2563EB"))));
    }

    /** W-03 天候與集合：本人今天各班次的集合地點；工班改室內時標示 */
    static String weather(String lang, Map<String, Object> d) {
        String level = s(d, "alertLevel");
        boolean alert = "warning".equals(level) || "danger".equals(level);
        String title = I18n.t(lang, "weather.title");
        List<String> lines = new ArrayList<>();
        lines.add(s(d, "date") + "・" + s(d, "workerName"));
        lines.add(label(lang, "weather.now", s(d, "weather")));
        List<Map<String, Object>> items = list(d.get("items"));
        for (Map<String, Object> it : items) {
            lines.add(heading(it, ""));
            lines.add(I18n.t(lang, Boolean.TRUE.equals(it.get("indoor")) ? "weather.indoor" : "weather.field"));
            lines.add(label(lang, "weather.place.label", orPending(lang, s(it, "place"))));
            if (!s(it, "work").isEmpty()) lines.add(label(lang, "task.work", s(it, "work")));
            String ride = join(s(it, "vehicle"), s(it, "driver").isEmpty() ? "" : I18n.t(lang, "weather.driver") + " " + s(it, "driver"));
            if (!ride.isEmpty()) lines.add(label(lang, "weather.ride", ride));
            String foreman = join(s(it, "foremanName"), s(it, "foremanPhone"));
            if (!foreman.isEmpty()) lines.add(label(lang, "task.foreman", foreman));
        }
        if (items.isEmpty()) lines.add(I18n.t(lang, "weather.none"));
        else if (alert) lines.add(I18n.t(lang, "weather.note"));
        String bg = "danger".equals(level) ? "#B91C1C" : "warning".equals(level) ? "#B45309" : "#1D4ED8";
        return FlexMessageBuilder.wrapFlex(title, FlexMessageBuilder.simpleBubble(bg, SUBTITLE, title, lines, List.of()));
    }

    /** W-04 本月薪資試算（勞健保為概估；8,800 元就業獎勵的適用對象【待確認】，此處只列工人資料上登錄的農業津貼） */
    static String salary(String lang, Map<String, Object> d, String workerPageUrl) {
        String title = I18n.t(lang, "salary.title");
        List<String> lines = new ArrayList<>();
        lines.add(s(d, "month") + "・" + s(d, "workerName"));
        lines.add(I18n.t(lang, "salary.hours").replace("{hours}", s(d, "workedHours")).replace("{overtime}", s(d, "overtimeHours")));
        lines.add(label(lang, "salary.base", money(d.get("baseSalary"))));
        if (num(d.get("agriSubsidy")) > 0) lines.add(label(lang, "salary.subsidy", money(d.get("agriSubsidy"))));
        if (num(d.get("transportHeatAllowance")) > 0) lines.add(label(lang, "salary.transport", money(d.get("transportHeatAllowance"))));
        if (num(d.get("overtimePay")) > 0) lines.add(label(lang, "salary.overtime", money(d.get("overtimePay"))));
        lines.add(label(lang, "salary.deduct", "−" + money(d.get("totalDeductions"))));
        lines.add(label(lang, "salary.net", money(d.get("netPay"))));
        lines.add(label(lang, "salary.paydate", s(d, "payDate")));
        lines.add(I18n.t(lang, "salary.note"));
        return FlexMessageBuilder.wrapFlex(title, FlexMessageBuilder.simpleBubble("#7C3AED", SUBTITLE, title, lines,
                List.of(FlexMessageBuilder.uriButton(I18n.t(lang, "salary.open"), workerPageUrl, "#06C755"))));
    }

    /** 正式模式下後端查不到時：不顯示示範資料，改請使用者開工作者頁面 */
    static List<String> unavailable(String lang, String workerPageUrl) {
        return List.of(FlexMessageBuilder.wrapText(I18n.t(lang, "query.unavailable")),
                FlexMessageBuilder.getWorkerLinkFlex(lang, "page", workerPageUrl));
    }

    // -------------------------------------------------------------------------

    private static String heading(Map<String, Object> it, String status) {
        return "【" + s(it, "orderId") + "】" + join(s(it, "timeSlotLabel"), status);
    }

    private static String label(String lang, String key, String value) {
        return I18n.t(lang, key) + "：" + value;
    }

    private static String orPending(String lang, String v) {
        return v.isEmpty() ? I18n.t(lang, "task.pending") : v;
    }

    private static String area(String lang, Object a) {
        double v = num(a);
        if (v <= 0) return "";
        String n = v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
        return I18n.t(lang, "task.area").replace("{n}", n);
    }

    private static String money(Object v) {
        return String.format("NT$ %,d", Math.round(num(v)));
    }

    private static double num(Object v) {
        return v instanceof Number n ? n.doubleValue() : 0;
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
