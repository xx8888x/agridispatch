package com.agridispatch.linebot;

import java.util.List;
import java.util.Scanner;

/**
 * 智農派工 - LINE Bot 本地離線模擬器與自動檢核（兩個官方帳號）
 * 無需 LINE Developers 帳號、無需 ngrok，即可測試農場主帳號與工作者帳號的對話與 Flex Message。
 *
 * 用法：
 *   java -cp bin com.agridispatch.linebot.TestSimulator           → 自動檢核後進入互動模式
 *   java -cp bin com.agridispatch.linebot.TestSimulator --check   → 只跑自動檢核，失敗時結束碼為 1
 */
public class TestSimulator {

    private static int seq = 0;

    public static void main(String[] args) {
        boolean checkOnly = args.length > 0 && "--check".equals(args[0]);
        System.out.println("==================================================================");
        System.out.println("🤖 智農派工 (AgriDispatch) - LINE Bot 本地模擬測試工具（農場主／工作者雙帳號）");
        System.out.println("==================================================================");

        int failed = runAutomatedCheck();
        if (checkOnly) {
            System.exit(failed == 0 ? 0 : 1);
            return;
        }

        System.out.println("\n--- 進入互動式測試模式 ---");
        System.out.println("  直接輸入文字即送出訊息；指令：");
        System.out.println("  /farmer、/worker        切換帳號（預設農場主）");
        System.out.println("  /as <身分>              farmer、local、foreman、machinery、machinery_leader、vn、id、th、unbound");
        System.out.println("  /follow [語言]          模擬加好友（可帶 LINE 介面語言，例 /follow vi）");
        System.out.println("  /pb <data>              模擬選單 postback，例 /pb a=lang&v=vi");
        System.out.println("  exit                    結束");
        Scanner scanner = new Scanner(System.in);
        String userId = "U_TEST_USER_001";
        Channel channel = Channel.FARMER;

        while (true) {
            System.out.print("\n💬 [" + channel.key + "] > ");
            if (!scanner.hasNextLine()) break;
            String line = scanner.nextLine().trim();
            if (line.isEmpty()) continue;
            if ("exit".equalsIgnoreCase(line) || "quit".equalsIgnoreCase(line)) {
                System.out.println("👋 測試結束，感謝使用！");
                break;
            }
            BotEvent ev;
            if ("/farmer".equals(line) || "/worker".equals(line)) {
                channel = Channel.fromKey(line.substring(1));
                System.out.println("已切換到 " + channel.displayName);
                continue;
            } else if (line.startsWith("/as ")) {
                Identity preset = IdentityStore.preset(line.substring(4));
                if (preset == null) {
                    System.out.println("未知身分：" + line.substring(4));
                } else {
                    IdentityStore.put(channel, userId, preset);
                    System.out.println("已設定身分：" + preset);
                }
                continue;
            } else if (line.startsWith("/follow")) {
                String lang = line.length() > 8 ? line.substring(8).trim() : null;
                ev = BotEvent.follow(channel, userId, lang);
            } else if (line.startsWith("/pb ")) {
                ev = BotEvent.postback(channel, userId, line.substring(4));
            } else {
                ev = BotEvent.text(channel, userId, line);
            }
            for (String reply : LineBotDispatcher.handle(ev)) {
                printPrettyReply(reply);
            }
        }
    }

    /** 自動檢核：每一案例用新的 userId，回傳失敗數 */
    private static int runAutomatedCheck() {
        if (!Config.allMock()) {
            System.out.println("⚠️ 已設定正式金鑰，自動檢核只在兩個帳號都是 Mock 模式時執行。");
            return 0;
        }
        System.out.println("⚡ 正在執行自動檢核...");
        int failed = 0;
        Channel F = Channel.FARMER, W = Channel.WORKER;

        // 農場主帳號（Mock 模式未指定身分時視為示範農場主）
        failed += check("F-01 派工確認單", F, null, text("派工"), 0, "派工確認單");
        failed += check("F-02 黑轉白身分選單", F, null, text("黑轉白"), 0, "請選擇工人身分");
        failed += check("F-02 黑轉白（文字）", F, null, text("黑轉白：失聯移工"), 0, "失聯");
        failed += check("F-02 黑轉白（postback）", F, null, pb("a=legalize&t=borrowed"), 0, "借用");
        failed += check("F-03 早鳥工時包", F, null, text("早鳥工時包"), 0, "早鳥");
        failed += check("F-04 天候避險", F, null, text("天候避險"), 0, "暴雨");
        failed += check("F-05 機耕代噴預約", F, null, text("機耕代噴"), 0, "機耕代噴預約");
        failed += check("F-08 服務導覽", F, null, text("服務導覽"), 0, "服務導覽");
        failed += check("F 薪資 → 引導工作者帳號", F, null, text("薪資"), 0, "此帳號提供農場主使用");
        failed += check("F 越南語 → 引導工作者帳號", F, null, text("越南語"), 0, "此帳號提供農場主使用");
        failed += check("F 未綁定查派工 → 綁定", F, "unbound", text("派工"), 0, "需要先綁定農場主帳號");
        failed += check("F 未綁定查早鳥（公開）", F, "unbound", text("早鳥"), 0, "早鳥");
        failed += check("F 未綁定加好友 → 綁定卡", F, "unbound", follow(null), 1, "農場主帳號綁定");

        // 工作者帳號
        failed += check("W 加好友（越語介面）→ 三語歡迎", W, null, follow("vi"), 0, "Chào mừng");
        failed += check("W 加好友（越語介面）→ 越語綁定卡", W, null, follow("vi"), 2, "Liên kết tài khoản");
        failed += check("W 未綁定查任務 → 綁定", W, null, text("任務"), 0, "需要先綁定帳號");
        failed += check("W-01 越南籍任務卡", W, "vn", text("nhiệm vụ"), 0, "Thẻ Nhiệm Vụ");
        failed += check("W-01 印尼籍任務卡", W, "id", text("tugas"), 0, "Kartu Tugas");
        failed += check("W-01 本勞任務卡（不是農場主派工單）", W, "local", text("今日任務"), 0, "今日派工任務卡");
        failed += check("W-01 泰國籍暫用中文卡", W, "th", text("任務"), 0, "今日派工任務卡");
        failed += check("W-01 機耕師工單", W, "machinery", text("任務"), 0, "代噴任務卡");
        failed += check("W-01 機耕師問代噴 → 工單", W, "machinery", text("代噴"), 0, "代噴任務卡");
        failed += check("W-01 帶班師傅附工班摘要", W, "foreman", text("任務"), 1, "工班出勤摘要");
        failed += check("W-01 機耕隊組長：工單＋工班摘要", W, "machinery_leader", text("機耕航跡"), 1, "工班出勤摘要");
        failed += check("W-02 打卡（外勞頁）", W, "vn", text("chấm công"), 0, "Chấm công");
        failed += check("W-03 天候集合（印尼語）", W, "id", text("cuaca"), 0, "Cuaca");
        failed += check("W-04 薪資（越語先提示暫用中文）", W, "vn", text("lương"), 0, "chưa có bản tiếng Việt");
        failed += check("W-04 薪資卡", W, "vn", text("lương"), 1, "薪資明細");
        failed += check("W-05 出勤紀錄", W, "local", text("出勤紀錄"), 0, "出勤紀錄");
        failed += check("W-06 語言選擇卡", W, "vn", text("切換語言"), 0, "Chọn ngôn ngữ");
        failed += check("W-06 postback 切換印尼語", W, "vn", pb("a=lang&v=id"), 0, "Bahasa Indonesia");
        failed += check("W-07 綁定卡（本勞入口）", W, null, text("帳號綁定"), 0, "帳號綁定");
        failed += check("W 黑轉白 → 引導農場主帳號", W, "vn", text("黑轉白"), 0, "Tài khoản này dành cho người lao động");
        failed += check("W 早鳥 → 引導農場主帳號", W, "local", text("早鳥"), 0, "此帳號提供工作人員使用");
        failed += check("W 費率 → 引導農場主帳號", W, "local", text("費率多少錢"), 0, "此帳號提供工作人員使用");
        failed += check("W 未知 postback → 說明", W, "local", pb("a=nope"), 0, "使用說明");

        // 主動選擇的語言不被國籍覆蓋（規格 3.2 第 1 點）
        String uid = "U_CHECK_LOCK";
        IdentityStore.put(W, uid, IdentityStore.preset("vn"));
        LineBotDispatcher.handle(BotEvent.text(W, uid, "中文"));
        failed += expect("W-06 越南籍改中文後任務卡為中文", LineBotDispatcher.handle(BotEvent.text(W, uid, "任務")), 0, "今日派工任務卡");

        // 回覆不得帶出 LINE userId（規格第拾章第 2 點）
        String secretUid = "U0123456789abcdef0123456789abcdef";
        List<String> bind = LineBotDispatcher.handle(BotEvent.text(F, secretUid, "帳號綁定"));
        boolean leaked = String.join("", bind).contains(secretUid);
        System.out.printf("  %s %-36s%n", leaked ? "❌" : "✅", "綁定卡不顯示 LINE userId");
        if (leaked) failed++;

        System.out.println(failed == 0 ? "✅ 自動檢核全部通過！" : "❌ 自動檢核失敗 " + failed + " 項");
        return failed;
    }

    private interface Ev { BotEvent make(Channel c, String userId); }

    private static Ev text(String t) { return (c, u) -> BotEvent.text(c, u, t); }
    private static Ev pb(String d) { return (c, u) -> BotEvent.postback(c, u, d); }
    private static Ev follow(String lang) { return (c, u) -> BotEvent.follow(c, u, lang); }

    private static int check(String name, Channel channel, String as, Ev ev, int index, String expected) {
        String userId = "U_CHECK_" + (++seq);
        if (as != null) IdentityStore.put(channel, userId, IdentityStore.preset(as));
        return expect(name, LineBotDispatcher.handle(ev.make(channel, userId)), index, expected);
    }

    private static int expect(String name, List<String> replies, int index, String expected) {
        String got = index < replies.size() ? replies.get(index) : "";
        boolean ok = got.contains(expected);
        System.out.printf("  %s %-36s → %s%n", ok ? "✅" : "❌", name, ok ? summary(got) : "預期第 " + (index + 1)
                + " 則含「" + expected + "」，實際：" + (got.isEmpty() ? "（無）" : summary(got)));
        return ok ? 0 : 1;
    }

    private static void printPrettyReply(String json) {
        System.out.println("------------------------------------------------------------------");
        System.out.println("📥 " + summary(json));
        System.out.println(json.length() > 600 ? json.substring(0, 600) + "\n... [已折疊其餘 " + (json.length() - 600) + " 字元]" : json);
    }

    /** 取 altText，沒有則取文字訊息內容 */
    private static String summary(String json) {
        Object parsed = SimpleJson.parse(json);
        if (parsed instanceof java.util.Map<?, ?> m) {
            Object alt = m.get("altText");
            if (alt != null) return String.valueOf(alt);
            Object t = m.get("text");
            if (t != null) {
                String s = String.valueOf(t).replace("\n", " ");
                return s.length() > 60 ? s.substring(0, 60) + "…" : s;
            }
        }
        return "Flex Message 卡片";
    }
}
