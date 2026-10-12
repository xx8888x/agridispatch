package com.agridispatch.linebot;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 智農派工 - 圖文選單（規格第捌章）
 *
 * 6 份選單：farmer_guest、farmer_main、worker_guest、worker_main_zh／vi／id（rich_menu/<代碼>.json／.jpg，
 * 由 tools/make_rich_menus.py 產生）。RichMenuUploader 上傳後把 LINE 回傳的 richMenuId 記在
 * rich_menu/richmenu_ids.properties（各環境不同，不進版控）。
 *
 * guest 選單設為帳號預設；已綁定使用者以 per-user 連結到 main 選單。每次互動時 ensure() 會確認使用者
 * 連結的是該身分與語言的選單（記憶體快取，重啟或重新上傳後第一次互動自動補連結）。
 */
public final class RichMenus {
    private RichMenus() {}

    /** 分派器收到未支援的 postback 動作次數（TestSimulator 檢核選單按鈕用） */
    public static final java.util.concurrent.atomic.AtomicInteger UNKNOWN_ACTIONS = new java.util.concurrent.atomic.AtomicInteger();

    public static final List<String> FARMER_MENUS = List.of("farmer_guest", "farmer_main");
    public static final List<String> WORKER_MENUS = List.of("worker_guest", "worker_main_zh", "worker_main_vi", "worker_main_id");

    public static List<String> menusOf(Channel channel) {
        return channel == Channel.FARMER ? FARMER_MENUS : WORKER_MENUS;
    }

    /** 規格第捌章：依身分與語言決定選單代碼（與後端 LineBindingService.menuKey 相同規則） */
    public static String menuKeyFor(Channel channel, Identity id) {
        if (channel == Channel.FARMER) return id.isFarmer() ? "farmer_main" : "farmer_guest";
        // 外籍移工功能下架：外籍使用者回帳號預設選單、本勞一律中文選單
        if (!Config.FEATURE_FOREIGN_WORKERS) return id.isWorker() && !id.isForeign() ? "worker_main_zh" : "worker_guest";
        return id.isWorker() ? "worker_main_" + I18n.normalize(id.lang()) : "worker_guest";
    }

    public static boolean isGuest(String menuKey) {
        return menuKey.endsWith("_guest");
    }

    /** rich_menu 目錄（與 flex_templates 相同的兩種相對位置） */
    public static Path dir() {
        Path p = Path.of("rich_menu");
        return Files.isDirectory(p) ? p : Path.of("Software", "Line Bot", "rich_menu");
    }

    public static Path idsFile() {
        return dir().resolve("richmenu_ids.properties");
    }

    // ------------------------------------------------------------------ richMenuId 對照（檔案異動時重新讀取）

    private static volatile Properties ids = new Properties();
    private static volatile long idsMtime = -1;

    public static synchronized String richMenuId(String menuKey) {
        try {
            Path f = idsFile();
            long mtime = Files.exists(f) ? Files.getLastModifiedTime(f).toMillis() : 0;
            if (mtime != idsMtime) {
                Properties p = new Properties();
                if (mtime > 0) {
                    try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
                        p.load(r);
                    }
                }
                ids = p;
                idsMtime = mtime;
                linked.clear(); // 選單重新上傳後，下一次互動重新連結
            }
        } catch (Exception e) {
            System.err.println("⚠️ 讀取 richmenu_ids.properties 失敗：" + e.getMessage());
        }
        String v = ids.getProperty(menuKey);
        return v == null || v.isBlank() ? null : v.trim();
    }

    // ------------------------------------------------------------------ per-user 連結

    /** 已連結的選單代碼快取：channel:userId → menuKey */
    private static final Map<String, String> linked = new ConcurrentHashMap<>();

    /**
     * 連結使用者的圖文選單；guest 代表解除個人連結、回到帳號預設選單。
     * 回傳 "linked"、"unlinked"、"mock"（Mock 模式不呼叫 LINE）、"missing"（尚未上傳該選單）或 "failed"。
     */
    public static String link(Channel channel, String userId, String menuKey) {
        String k = channel.key + ":" + userId;
        if (channel.isMock()) {
            String prev = linked.put(k, menuKey);
            if (prev != null && !prev.equals(menuKey)) {
                System.out.println("🎛️ [模擬模式・" + channel.key + "] " + WebhookHandler.maskUserId(userId) + " 圖文選單 " + prev + " → " + menuKey);
            }
            return "mock";
        }
        LineApiClient api = LineApiClient.of(channel);
        if (isGuest(menuKey)) {
            // 沒有個人連結時 LINE 也可能回錯誤；不論結果都記入快取，避免每則訊息重打
            boolean ok = api.unlinkRichMenu(userId);
            linked.put(k, menuKey);
            return ok ? "unlinked" : "failed";
        }
        String id = richMenuId(menuKey);
        if (id == null) {
            System.err.println("⚠️ 尚未上傳圖文選單 " + menuKey + "（請執行 RichMenuUploader），略過連結");
            return "missing";
        }
        boolean ok = api.linkRichMenu(userId, id);
        if (ok) linked.put(k, menuKey);
        return ok ? "linked" : "failed";
    }

    /** 確認使用者連結的是目前身分的選單；與快取相同時不呼叫 LINE */
    public static void ensure(Channel channel, String userId, Identity identity) {
        if (userId == null || userId.isBlank()) return;
        String want = menuKeyFor(channel, identity);
        if (want.equals(linked.get(channel.key + ":" + userId))) return;
        link(channel, userId, want);
    }

    /** 封鎖後快取作廢（重新加好友時再連結一次） */
    public static void forget(Channel channel, String userId) {
        linked.remove(channel.key + ":" + userId);
    }
}
