package com.agridispatch.linebot;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 智農派工 - 本地模擬發言 API（POST /mock/chat，規格 6.2）
 * 只有指定帳號為 Mock 模式時才回應；正式金鑰模式一律 403，避免對外曝露對話邏輯。
 *
 * Body（JSON；也接受純文字，視為農場主帳號的 text）：
 *   channel      farmer（預設）／worker
 *   userId       模擬的 LINE userId（預設 U_MOCK_USER）
 *   text         文字訊息
 *   postback     postback data，例 a=lang&v=vi
 *   voice        模擬語音訊息的辨識結果（不呼叫 Whisper，規格 5.5）；voiceError 可帶 too_long／failed 模擬未辨識
 *   event        follow／unfollow（不帶時依 text／postback 判斷）
 *   as           示範身分：farmer、local、foreman、machinery、machinery_leader、vn、id、th、unbound（見 IdentityStore.preset）
 *   lineLanguage 模擬 follow 時的 LINE 介面語言（例 vi）
 *   multi        true 時回傳 {"channel":..,"messages":[..],"menuKey":..}；否則只回第一則訊息物件（相容舊版模擬器）
 *   event=menu   不送訊息，只回目前身分的圖文選單代碼（網頁模擬器載入選單用）
 */
public class MockChatHandler implements HttpHandler {

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        HttpUtils.applyCors(exchange, "POST, OPTIONS", "Content-Type");
        String method = exchange.getRequestMethod().toUpperCase();
        if ("OPTIONS".equals(method)) {
            exchange.sendResponseHeaders(204, -1);
            return;
        }
        if (!"POST".equals(method)) {
            HttpUtils.sendText(exchange, 405, "Method Not Allowed");
            return;
        }

        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8).trim();
        Map<String, Object> f = body.startsWith("{") ? SimpleJson.parseObject(body) : Map.of("text", body);

        Channel channel = f.get("channel") == null ? Channel.FARMER : Channel.fromKey(String.valueOf(f.get("channel")));
        if (channel == null) {
            HttpUtils.sendJson(exchange, 400, "{\"success\":false,\"message\":\"channel 必須是 farmer 或 worker\"}");
            return;
        }
        if (!channel.isMock()) {
            HttpUtils.sendText(exchange, 403, "Forbidden: /mock/chat 僅於該帳號為本地模擬模式 (Mock Mode) 時開放");
            return;
        }

        String userId = str(f.get("userId"), "U_MOCK_USER");
        if (f.get("as") != null) {
            Identity preset = IdentityStore.preset(String.valueOf(f.get("as")));
            if (preset == null) {
                HttpUtils.sendJson(exchange, 400,
                        "{\"success\":false,\"message\":\"as 必須是 farmer、local、foreman、machinery、machinery_leader、vn、id、th、unbound 之一\"}");
                return;
            }
            // 使用者在同一 userId 主動選過的語言保留，不被示範身分覆蓋
            Identity before = IdentityStore.overridden(channel, userId);
            IdentityStore.override(channel, userId, before != null && before.langLocked()
                    ? preset.withLang(before.lang(), true) : preset);
        }

        String eventName = str(f.get("event"), "");
        if ("menu".equalsIgnoreCase(eventName)) {
            HttpUtils.sendJson(exchange, 200, "{\"channel\":\"" + channel.key + "\",\"messages\":[],\"menuKey\":\""
                    + RichMenus.menuKeyFor(channel, IdentityStore.get(channel, userId)) + "\"}");
            return;
        }
        BotEvent ev;
        if ("follow".equalsIgnoreCase(eventName)) {
            ev = BotEvent.follow(channel, userId, str(f.get("lineLanguage"), null));
        } else if ("unfollow".equalsIgnoreCase(eventName)) {
            ev = BotEvent.of(channel, userId, BotEvent.Type.UNFOLLOW);
        } else if (f.get("voice") != null || f.get("voiceError") != null) {
            ev = BotEvent.voice(channel, userId, str(f.get("voice"), ""), str(f.get("voiceError"), null));
        } else if (f.get("postback") != null) {
            ev = BotEvent.postback(channel, userId, String.valueOf(f.get("postback")));
        } else {
            ev = BotEvent.text(channel, userId, str(f.get("text"), ""));
        }

        List<String> replies = LineBotDispatcher.handle(ev);
        boolean multi = Boolean.TRUE.equals(f.get("multi")) || "true".equals(String.valueOf(f.get("multi")));
        if (multi || replies.isEmpty()) {
            HttpUtils.sendJson(exchange, 200, "{\"channel\":\"" + channel.key + "\",\"messages\":[" + String.join(",", replies)
                    + "],\"menuKey\":\"" + RichMenus.menuKeyFor(channel, IdentityStore.get(channel, userId)) + "\"}");
        } else {
            HttpUtils.sendJson(exchange, 200, replies.get(0));
        }
    }

    private static String str(Object o, String def) {
        if (o == null) return def;
        String s = String.valueOf(o);
        return s.isBlank() ? def : s;
    }
}
