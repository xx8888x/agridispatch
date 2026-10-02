package com.agridispatch.linebot;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 智農派工 - 使用者身分與語言
 *
 * 來源優先順序：
 *   1. 示範身分覆寫（僅 /mock/chat 的 "as"、TestSimulator 使用）
 *   2. 後端 line_users（BackendClient；規格 6.3、6.5）—— 綁定與語言重啟不遺失
 *   3. 後端不可用（未設定內部金鑰、離線）時退回本機記憶體（重啟後遺失）
 *
 * Mock 模式下，農場主帳號未綁定的使用者視為已綁定的示範農場主（路演不必先綁定）；
 * 工作者帳號一律依實際綁定狀態，可用 "as" 指定示範身分（見 preset）。
 */
public final class IdentityStore {
    private IdentityStore() {}

    private static final Map<String, Identity> overrides = new ConcurrentHashMap<>();
    private static final Map<String, Identity> local = new ConcurrentHashMap<>();

    private static String key(Channel channel, String userId) {
        return channel.key + ":" + (userId == null ? "" : userId);
    }

    public static Identity get(Channel channel, String userId) {
        Identity o = overrides.get(key(channel, userId));
        if (o != null) return o;
        Identity fromBackend = BackendClient.identity(channel, userId);
        Identity id = fromBackend != null ? fromBackend : local.get(key(channel, userId));
        if ((id == null || !id.bound()) && channel == Channel.FARMER && channel.isMock()) {
            return preset("farmer");
        }
        return id != null ? id : Identity.unbound("zh", false);
    }

    /** 目前的示範身分覆寫；沒有則為 null */
    public static Identity overridden(Channel channel, String userId) {
        return overrides.get(key(channel, userId));
    }

    /** 設定示範身分覆寫（僅 Mock 模式的 /mock/chat、TestSimulator 使用） */
    public static void override(Channel channel, String userId, Identity identity) {
        overrides.put(key(channel, userId), identity);
    }

    /**
     * 加好友：記錄追蹤；工作者帳號未主動選過語言時，以 LINE 介面語言（vi／id）為預設（規格 3.2 第 3 點）
     */
    public static void recordFollow(Channel channel, String userId, String profileLang) {
        String lang = channel == Channel.WORKER && profileLang != null && !"zh".equals(profileLang)
                && I18n.SUPPORTED.contains(profileLang) ? profileLang : null;
        String k = key(channel, userId);
        Identity o = overrides.get(k);
        if (o != null) {
            if (lang != null && !o.bound() && !o.langLocked()) overrides.put(k, o.withLang(lang, false));
            return;
        }
        if (BackendClient.followState(channel, userId, true, lang, false)) return;
        Identity cur = local.get(k);
        if (cur == null) cur = Identity.unbound("zh", false);
        if (lang != null && !cur.bound() && !cur.langLocked()) cur = cur.withLang(lang, false);
        local.put(k, cur);
    }

    /** 封鎖：記錄不再追蹤（推播時排除，規格第柒章第 2 點） */
    public static void recordUnfollow(Channel channel, String userId) {
        if (overrides.containsKey(key(channel, userId))) return;
        BackendClient.followState(channel, userId, false, null, false);
    }

    /** 使用者主動選擇語言（W-06）：固定下來，不再被國籍或 LINE 介面語言覆蓋 */
    public static Identity setLang(Channel channel, String userId, String lang) {
        String k = key(channel, userId);
        Identity o = overrides.get(k);
        if (o != null) {
            Identity updated = o.withLang(lang, true);
            overrides.put(k, updated);
            return updated;
        }
        if (!BackendClient.followState(channel, userId, null, lang, true)) {
            Identity cur = local.get(k);
            local.put(k, (cur != null ? cur : Identity.unbound("zh", false)).withLang(lang, true));
        }
        return get(channel, userId);
    }

    /**
     * 示範身分（僅供 Mock 模式的 /mock/chat、TestSimulator 使用）
     * farmer、local（中高齡本勞）、foreman（帶班師傅）、machinery（機耕師）、machinery_leader（機耕隊組長）、vn、id、th、unbound
     */
    public static Identity preset(String name) {
        if (name == null) return null;
        return switch (name.trim().toLowerCase()) {
            case "farmer" -> new Identity(true, Identity.FARMER, null, null, false, "zh", false);
            case "local" -> new Identity(true, Identity.WORKER_LOCAL, "LABOR", null, false, "zh", false);
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
