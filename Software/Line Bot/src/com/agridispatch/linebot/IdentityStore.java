package com.agridispatch.linebot;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 智農派工 - 使用者身分與語言的暫存區（P1）
 *
 * ⚠️ P1 只存在記憶體，重啟後遺失；P2 改為呼叫後端 /api/line/internal/identity、follow-state，
 *    身分與語言改存 line_users（規格 6.3、6.5）。呼叫端只透過本類別存取，P2 只需替換實作。
 *
 * Mock 模式下，農場主帳號未設定身分的使用者視為已綁定的示範農場主（路演不必先綁定）；
 * 工作者帳號一律從未綁定開始，可用 /mock/chat 的 "as" 欄位指定示範身分（見 presets）。
 */
public final class IdentityStore {
    private IdentityStore() {}

    private static final Map<String, Identity> store = new ConcurrentHashMap<>();

    private static String key(Channel channel, String userId) {
        return channel.key + ":" + (userId == null ? "" : userId);
    }

    public static Identity get(Channel channel, String userId) {
        Identity id = store.get(key(channel, userId));
        if (id != null) return id;
        if (channel == Channel.FARMER && channel.isMock()) {
            return preset("farmer");
        }
        return Identity.unbound("zh", false);
    }

    /** 是否已有紀錄（follow 時用來判斷要不要依 LINE 介面語言預設） */
    public static boolean has(Channel channel, String userId) {
        return store.containsKey(key(channel, userId));
    }

    public static void put(Channel channel, String userId, Identity identity) {
        store.put(key(channel, userId), identity);
    }

    /** 使用者主動選擇語言（W-06）：固定下來，不再被國籍或 LINE 介面語言覆蓋 */
    public static Identity setLang(Channel channel, String userId, String lang) {
        Identity updated = get(channel, userId).withLang(lang, true);
        put(channel, userId, updated);
        return updated;
    }

    /**
     * 示範身分（僅供 Mock 模式的 /mock/chat、TestSimulator 使用）
     * farmer、local（中高齡本勞）、foreman（帶班師傅）、machinery（機耕師）、machinery_leader（機耕隊組長）、vn、id、th、unbound
     */
    public static Identity preset(String name) {
        if (name == null) return null;
        return switch (name.trim().toLowerCase()) {
            case "farmer" -> new Identity(true, Identity.FARMER, null, null, false, "zh", false);
            case "local" -> new Identity(true, Identity.WORKER_LOCAL, "SENIOR", null, false, "zh", false);
            case "foreman" -> new Identity(true, Identity.WORKER_LOCAL, "FOREMAN", null, true, "zh", false);
            case "machinery" -> new Identity(true, Identity.WORKER_LOCAL, "MACHINERY", null, false, "zh", false);
            case "machinery_leader" -> new Identity(true, Identity.WORKER_LOCAL, "MACHINERY", null, true, "zh", false);
            case "vn" -> new Identity(true, Identity.WORKER_FOREIGN, null, "VN", false, "vi", false);
            case "id" -> new Identity(true, Identity.WORKER_FOREIGN, null, "ID", false, "id", false);
            case "th" -> new Identity(true, Identity.WORKER_FOREIGN, null, "TH", false, "zh", false);
            case "unbound" -> Identity.unbound("zh", false);
            default -> null;
        };
    }
}
