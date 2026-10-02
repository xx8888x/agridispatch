package com.agridispatch.linebot;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * 智農派工 - 模擬模式的推播收件匣（GET /mock/outbox?since=序號）
 * Mock 模式的帳號不會真的呼叫 LINE，推播內容記在這裡（最近 200 筆），供驗收腳本與模擬器確認「推給誰、哪種語言」。
 * 只記錄、只回應 Mock 模式帳號的推播；兩個帳號都是正式模式時回 403。
 */
public final class MockOutbox implements HttpHandler {

    private record Entry(long seq, String channel, String type, String lang, List<String> to, String altText) {}

    private static final Deque<Entry> entries = new ArrayDeque<>();
    private static long seq = 0;

    public static synchronized void add(Channel channel, String type, String lang, List<String> to, String firstMessage) {
        if (!channel.isMock()) return;
        String alt = "";
        Object parsed = SimpleJson.parse(firstMessage == null ? "" : firstMessage);
        if (parsed instanceof java.util.Map<?, ?> m) {
            Object a = m.get("altText") != null ? m.get("altText") : m.get("text");
            alt = a == null ? "" : String.valueOf(a);
        }
        entries.addLast(new Entry(++seq, channel.key, type, lang, List.copyOf(to), alt));
        while (entries.size() > 200) entries.removeFirst();
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        HttpUtils.applyCors(exchange, "GET, OPTIONS", "Content-Type");
        if (!Channel.FARMER.isMock() && !Channel.WORKER.isMock()) {
            HttpUtils.sendText(exchange, 403, "Forbidden: /mock/outbox 僅於模擬模式開放");
            return;
        }
        long since = 0;
        String q = exchange.getRequestURI().getQuery();
        if (q != null && q.startsWith("since=")) {
            try { since = Long.parseLong(q.substring(6)); } catch (NumberFormatException ignored) { }
        }
        StringBuilder sb = new StringBuilder("{\"last\":").append(seq).append(",\"entries\":[");
        boolean first = true;
        synchronized (MockOutbox.class) {
            for (Entry e : entries) {
                if (e.seq() <= since) continue;
                if (!first) sb.append(",");
                first = false;
                sb.append("{\"seq\":").append(e.seq()).append(",\"channel\":\"").append(e.channel())
                  .append("\",\"type\":\"").append(esc(e.type())).append("\",\"lang\":\"").append(esc(e.lang()))
                  .append("\",\"altText\":\"").append(esc(e.altText())).append("\",\"to\":[");
                for (int i = 0; i < e.to().size(); i++) {
                    if (i > 0) sb.append(",");
                    sb.append("\"").append(esc(e.to().get(i))).append("\"");
                }
                sb.append("]}");
            }
        }
        sb.append("]}");
        HttpUtils.sendJson(exchange, 200, sb.toString());
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "");
    }
}
