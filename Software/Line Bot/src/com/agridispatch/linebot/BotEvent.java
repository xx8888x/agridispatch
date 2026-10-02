package com.agridispatch.linebot;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 智農派工 - 送進分派器的一筆使用者事件（規格 5.1）
 *
 * @param channel     事件來自哪個官方帳號
 * @param userId      LINE userId
 * @param type        事件種類
 * @param text        文字訊息內容（TEXT 才有）
 * @param postback    postback data 解析結果（POSTBACK 才有，格式 a=動作&參數=值，規格 5.2）
 * @param profileLang LINE 使用者介面語言（FOLLOW 時由 Get Profile 取得，可能為 null，規格 3.2 第 3 點）
 */
public record BotEvent(Channel channel, String userId, Type type, String text,
                       Map<String, String> postback, String profileLang) {

    public enum Type { FOLLOW, UNFOLLOW, TEXT, POSTBACK, OTHER_MESSAGE }

    public static BotEvent text(Channel channel, String userId, String text) {
        return new BotEvent(channel, userId, Type.TEXT, text == null ? "" : text, Map.of(), null);
    }

    public static BotEvent postback(Channel channel, String userId, String data) {
        return new BotEvent(channel, userId, Type.POSTBACK, "", parsePostback(data), null);
    }

    public static BotEvent follow(Channel channel, String userId, String profileLang) {
        return new BotEvent(channel, userId, Type.FOLLOW, "", Map.of(), profileLang);
    }

    public static BotEvent of(Channel channel, String userId, Type type) {
        return new BotEvent(channel, userId, type, "", Map.of(), null);
    }

    /** postback 的動作代碼（參數 a）；非 postback 或缺少時為空字串 */
    public String action() {
        return postback.getOrDefault("a", "");
    }

    /** 解析 a=task&v=vi 形式的 postback data；最長 300 字元（LINE 規定），超過的部分不處理 */
    static Map<String, String> parsePostback(String data) {
        if (data == null || data.isBlank()) return Map.of();
        String d = data.length() > 300 ? data.substring(0, 300) : data;
        Map<String, String> m = new LinkedHashMap<>();
        for (String pair : d.split("&")) {
            int eq = pair.indexOf('=');
            String k = eq < 0 ? pair : pair.substring(0, eq);
            String v = eq < 0 ? "" : pair.substring(eq + 1);
            try {
                m.put(URLDecoder.decode(k, StandardCharsets.UTF_8), URLDecoder.decode(v, StandardCharsets.UTF_8));
            } catch (IllegalArgumentException e) {
                // 格式錯誤的參數略過
            }
        }
        return Collections.unmodifiableMap(m);
    }
}
