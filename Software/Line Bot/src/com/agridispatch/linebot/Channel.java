package com.agridispatch.linebot;

/**
 * 智農派工 - LINE 官方帳號（Messaging API channel）
 * 依 07_系統分析及文件/05_LINE官方帳號拆分規格.md 第貳章：農場主帳號、工作者帳號，各自持有 secret／token，各自判斷 Mock 模式。
 */
public enum Channel {
    FARMER("farmer", "智農派工（農場主）"),
    WORKER("worker", "智農派工（工作者）");

    public final String key;
    public final String displayName;

    /** 由 Config.loadConfig() 填入；未設定時為空字串 */
    volatile String secret = "";
    volatile String accessToken = "";
    /** 官方帳號 Basic ID（例 @123abcde），用於「加入另一個帳號」按鈕；未設定時為空字串 */
    volatile String basicId = "";

    Channel(String key, String displayName) {
        this.key = key;
        this.displayName = displayName;
    }

    public String secret() { return secret; }
    public String accessToken() { return accessToken; }
    public String basicId() { return basicId; }

    /** 未設定 secret 或 token、或仍為範例值時，此帳號為本地模擬模式 */
    public boolean isMock() {
        return isPlaceholder(secret) || isPlaceholder(accessToken);
    }

    /** 加好友網址；未設定 Basic ID 時回傳 null */
    public String addFriendUrl() {
        if (basicId == null || basicId.isBlank()) return null;
        String id = basicId.trim();
        return "https://line.me/R/ti/p/" + (id.startsWith("@") ? "%40" + id.substring(1) : id);
    }

    public static Channel fromKey(String key) {
        if (key == null) return null;
        for (Channel c : values()) {
            if (c.key.equalsIgnoreCase(key.trim())) return c;
        }
        return null;
    }

    static boolean isPlaceholder(String v) {
        return v == null || v.isBlank() || v.startsWith("mock_") || v.startsWith("請填入") || v.startsWith("your_");
    }
}
