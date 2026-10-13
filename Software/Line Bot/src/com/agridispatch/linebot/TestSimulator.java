package com.agridispatch.linebot;

import java.util.List;
import java.util.Map;
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
        System.out.println("  /voice <文字>           模擬語音訊息（直接給辨識結果，不呼叫 Whisper）");
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
                    IdentityStore.override(channel, userId, preset);
                    System.out.println("已設定身分：" + preset);
                }
                continue;
            } else if (line.startsWith("/follow")) {
                String lang = line.length() > 8 ? line.substring(8).trim() : null;
                ev = BotEvent.follow(channel, userId, lang);
            } else if (line.startsWith("/pb ")) {
                ev = BotEvent.postback(channel, userId, line.substring(4));
            } else if (line.startsWith("/voice ")) {
                ev = BotEvent.voice(channel, userId, line.substring(7), null);
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
        // F-02 黑轉白：預設下架（Config.FEATURE_LEGALIZE=false），兩種狀態都檢核，最後還原設定
        boolean legalizeSetting = Config.FEATURE_LEGALIZE;
        Config.FEATURE_LEGALIZE = false;
        failed += check("F-02 黑轉白下架：文字回服務導覽", F, null, text("黑轉白"), 0, "服務導覽");
        failed += check("F-02 黑轉白下架：導覽不列黑轉白", F, null, text("服務導覽"), 0, "!黑轉白");
        failed += check("F-02 黑轉白下架：舊選單 postback", F, null, pb("a=legalize&t=borrowed"), 0, "暫停提供");
        Config.FEATURE_LEGALIZE = true;
        failed += check("F-02 黑轉白身分選單", F, null, text("黑轉白"), 0, "請選擇工人身分");
        failed += check("F-02 黑轉白（文字）", F, null, text("黑轉白：失聯移工"), 0, "失聯");
        failed += check("F-02 黑轉白（postback）", F, null, pb("a=legalize&t=borrowed"), 0, "借用");
        Config.FEATURE_LEGALIZE = legalizeSetting;
        failed += check("F-03 早鳥工時包", F, null, text("早鳥工時包"), 0, "早鳥");
        failed += check("F-04 天候避險", F, null, text("天候避險"), 0, "暴雨");
        failed += check("F-05 機耕代噴預約", F, null, text("機耕代噴"), 0, "機耕代噴預約");
        failed += check("F-08 服務導覽", F, null, text("服務導覽"), 0, "服務導覽");
        failed += check("F 薪資 → 引導工作者帳號", F, null, text("薪資"), 0, "此帳號提供農場主使用");
        failed += check("F 越南語 → 引導工作者帳號", F, null, text("越南語"), 0, "此帳號提供農場主使用");
        failed += check("F 未綁定查派工 → 綁定", F, "unbound", text("派工"), 0, "需要先綁定農場主帳號");
        failed += check("F 未綁定查早鳥（公開）", F, "unbound", text("早鳥"), 0, "早鳥");
        failed += check("F 未綁定加好友 → 綁定卡", F, "unbound", follow(null), 1, "農場主帳號綁定");

        // 外籍移工功能：預設下架（Config.FEATURE_FOREIGN_WORKERS=false），先檢核下架狀態，
        // 之後的工作者案例（多語、外籍身分）暫時打開開關檢核恢復狀態，最後還原設定
        boolean foreignSetting = Config.FEATURE_FOREIGN_WORKERS;
        Config.FEATURE_FOREIGN_WORKERS = false;
        failed += check("W 外勞下架：加好友（越語介面）→ 中文歡迎", W, null, follow("vi"), 0, "本國籍工作者");
        failed += check("W 外勞下架：加好友不出三語歡迎", W, null, follow("vi"), 0, "!Chào mừng");
        failed += check("W 外勞下架：加好友不出語言選擇", W, null, follow("vi"), 1, "!Chọn ngôn ngữ");
        failed += check("W 外勞下架：綁定卡只有本國籍入口", W, null, text("帳號綁定"), 0, "本國籍工作者");
        failed += check("W 外勞下架：綁定卡無外籍入口", W, null, text("帳號綁定"), 0, "!外籍移工");
        failed += check("W 外勞下架：綁定卡無 worker_foreign 網址", W, null, text("帳號綁定"), 0, "!role=worker_foreign");
        failed += expectEq("W 外勞下架：不產生 worker_foreign 綁定網址",
                String.valueOf(FlexMessageBuilder.bindUrl(W, Identity.WORKER_FOREIGN, "vi", null)), "null");
        failed += check("W 外勞下架：越南籍舊使用者 → 暫停說明", W, "vn", text("nhiệm vụ"), 0, "外籍工作者服務暫停中");
        failed += check("W 外勞下架：越南籍舊使用者 → 聯絡卡", W, "vn", text("nhiệm vụ"), 1, "聯絡合作社");
        failed += check("W 外勞下架：印尼籍選單 postback → 暫停", W, "id", pb("a=clock"), 0, "暫停中");
        failed += check("W 外勞下架：越南籍加好友 → 暫停", W, "vn", follow("vi"), 0, "暫停中");
        failed += check("W 外勞下架：本勞任務卡", W, "local", text("今日任務"), 0, "今日派工任務卡");
        failed += check("W 外勞下架：輸入越南語不切換", W, "local", text("Tiếng Việt"), 0, "使用說明");
        failed += check("W 外勞下架：切換語言 → 僅中文", W, "local", text("切換語言"), 0, "僅提供繁體中文");
        failed += check("W 外勞下架：舊選單 a=lang&v=vi", W, null, pb("a=lang&v=vi"), 0, "僅提供繁體中文");
        failed += check("W 外勞下架：舊選單語言・說明 → 說明", W, "local", pb("a=lang_help"), 0, "使用說明");
        failed += check("W 外勞下架：說明不列切換語言", W, "local", text("使用說明"), 0, "!切換語言");
        failed += check("F 外勞下架：引導文案不提外勞", F, null, text("薪資"), 0, "!外勞");
        String vuid = "U_CHECK_FOREIGN_OFF";
        IdentityStore.override(W, vuid, IdentityStore.preset("local").withLang("vi", true));
        failed += expect("W 外勞下架：本勞選過越語仍為中文", LineBotDispatcher.handle(BotEvent.text(W, vuid, "任務")), 0, "今日派工任務卡");
        failed += expectEq("選單（外勞下架）：越南籍 → 預設選單", RichMenus.menuKeyFor(W, IdentityStore.preset("vn")), "worker_guest");
        failed += expectEq("選單（外勞下架）：本勞選越語仍中文", RichMenus.menuKeyFor(W, IdentityStore.preset("local").withLang("vi", true)), "worker_main_zh");
        failed += voiceChecks();
        Config.FEATURE_FOREIGN_WORKERS = true;

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

        // P3：新選單動作
        failed += check("F 費率與產曆（後端離線→導覽）", F, null, pb("a=rates_calendar"), 0, "服務導覽");
        failed += check("W 聯絡合作社（越語）", W, "vn", pb("a=contact"), 0, "Liên hệ hợp tác xã");
        failed += check("W 聯絡合作社含 1955", W, "local", pb("a=contact"), 0, "tel:1955");
        failed += check("W 語言・說明 → 語言卡", W, "id", pb("a=lang_help"), 0, "Pilih bahasa");
        failed += check("W 語言・說明 → 說明卡", W, "id", pb("a=lang_help"), 1, "Panduan penggunaan");

        // P3：圖文選單代碼依身分與語言（規格第捌章）
        failed += expectEq("選單：農場主已綁定", RichMenus.menuKeyFor(F, IdentityStore.preset("farmer")), "farmer_main");
        failed += expectEq("選單：農場主未綁定", RichMenus.menuKeyFor(F, IdentityStore.preset("unbound")), "farmer_guest");
        failed += expectEq("選單：工作者未綁定", RichMenus.menuKeyFor(W, IdentityStore.preset("unbound")), "worker_guest");
        failed += expectEq("選單：越南籍", RichMenus.menuKeyFor(W, IdentityStore.preset("vn")), "worker_main_vi");
        failed += expectEq("選單：印尼籍", RichMenus.menuKeyFor(W, IdentityStore.preset("id")), "worker_main_id");
        failed += expectEq("選單：泰國籍暫用中文", RichMenus.menuKeyFor(W, IdentityStore.preset("th")), "worker_main_zh");
        failed += expectEq("選單：越南籍改中文", RichMenus.menuKeyFor(W, IdentityStore.preset("vn").withLang("zh", true)), "worker_main_zh");
        failed += expectEq("選單：農場主身分在工作者帳號視為未綁定", RichMenus.menuKeyFor(W, IdentityStore.preset("farmer")), "worker_guest");

        // P3：6 份選單檔（JSON 結構、按鈕動作都在分派器支援清單內、底圖尺寸與大小）
        List<String> menuProblems = RichMenuUploader.validateAll();
        menuProblems.forEach(p -> System.out.println("     " + p));
        failed += expectEq("圖文選單檔檢核", menuProblems.isEmpty() ? "OK" : menuProblems.size() + " 項問題", "OK");
        // 檢核器本身：不支援的動作要被抓出來
        failed += expectEq("檢核器抓得到未知動作", String.valueOf(!FarmerDispatcher.ACTIONS.contains("task")), "true");

        // 每個選單按鈕與 ACTIONS 清單實際送 postback，不得落到「未知動作」
        int unknownBefore = RichMenus.UNKNOWN_ACTIONS.get();
        for (String act : FarmerDispatcher.ACTIONS) LineBotDispatcher.handle(BotEvent.postback(F, "U_CHECK_ACT", "a=" + act));
        for (String act : WorkerDispatcher.ACTIONS) LineBotDispatcher.handle(BotEvent.postback(W, "U_CHECK_ACT", "a=" + act + "&v=zh"));
        for (Channel c : Channel.values()) {
            for (String key : RichMenus.menusOf(c)) {
                String as = key.endsWith("_guest") ? "unbound" : (key.startsWith("farmer") ? "farmer"
                        : key.endsWith("_vi") ? "vn" : key.endsWith("_id") ? "id" : "local");
                for (String data : menuData(key)) {
                    failed += check("選單 " + key + " " + data, c, as, pb(data), 0, "\"type\"");
                }
            }
        }

        failed += expectEq("選單按鈕與 ACTIONS 都有對應處理", String.valueOf(RichMenus.UNKNOWN_ACTIONS.get() - unknownBefore), "0");

        // P5：主動推播訊息（依語言產生，JSON 合法、只用該帳號支援的種類）
        Map<String, Object> np = new java.util.HashMap<>();
        np.put("orderId", "ORD-2026-001"); np.put("status", "confirmed"); np.put("statusLabel", "已排定");
        np.put("crop", "芒果"); np.put("period", "2026-10-06 ～ 2026-10-08"); np.put("amount", 10584);
        np.put("weather", "暴雨警戒"); np.put("orders", List.of("ORD-2026-001")); np.put("date", "2026-10-03");
        np.put("place", "枋山"); np.put("indoor", true);
        np.put("month", "2026-09"); np.put("netPay", 36850); np.put("payDate", "2026-10-05");
        np.put("leaveId", "LV-2026-001"); np.put("leaveType", "事假"); np.put("leaveTypeCode", "personal"); np.put("note", "請領班安排代班");
        for (String type : NotifyMessages.FARMER_TYPES) {
            failed += expectEq("推播 farmer " + type, String.valueOf(validMessages(NotifyMessages.render(F, type, "zh", np))), "true");
        }
        for (String type : NotifyMessages.WORKER_TYPES) {
            for (String lang : List.of("zh", "vi", "id")) {
                failed += expectEq("推播 worker " + type + " " + lang, String.valueOf(validMessages(NotifyMessages.render(W, type, lang, np))), "true");
            }
        }
        failed += expect("推播 明日出工（越語）", NotifyMessages.render(W, "tomorrow_task", "vi", np), 0, "Thông báo đi làm ngày mai");
        failed += expect("推播 天候室內（印尼語）", NotifyMessages.render(W, "weather_worker", "id", np), 0, "dalam ruangan");
        failed += expect("推播 定金入帳金額", NotifyMessages.render(F, "deposit_paid", "zh", np), 0, "NT$ 10,584");
        failed += expect("推播 薪資入帳實發金額", NotifyMessages.render(W, "salary_paid", "zh", np), 0, "NT$ 36,850");
        failed += expect("推播 薪資入帳（越語）", NotifyMessages.render(W, "salary_paid", "vi", np), 0, "Thông báo lương đã chuyển");
        Map<String, Object> lp = new java.util.HashMap<>(np);
        lp.put("status", "approved");
        failed += expect("推播 請假核准", NotifyMessages.render(W, "leave_decided", "zh", lp), 0, "請假已核准");
        failed += expect("推播 請假核准（假別依代碼翻越語）", NotifyMessages.render(W, "leave_decided", "vi", lp), 0, "Nghỉ việc riêng");
        lp.put("status", "rejected"); lp.put("note", "產季人力不足");
        failed += expect("推播 請假駁回（印尼語）", NotifyMessages.render(W, "leave_decided", "id", lp), 0, "Cuti tidak disetujui");
        failed += expect("推播 請假駁回原因", NotifyMessages.render(W, "leave_decided", "zh", lp), 0, "原因：產季人力不足");
        for (String lang : List.of("zh", "vi", "id")) {
            failed += expectEq("推播 worker leave_decided rejected " + lang, String.valueOf(validMessages(NotifyMessages.render(W, "leave_decided", lang, lp))), "true");
        }
        lp.put("leaveTypeCode", "unknown_type"); lp.put("leaveType", "測試假別");
        failed += expect("推播 請假未知假別退回中文名稱", NotifyMessages.render(W, "leave_decided", "vi", lp), 0, "測試假別");
        failed += workerCardChecks();
        failed += farmerCardChecks();
        failed += expectEq("工作者帳號不支援農場主通知",String.valueOf(NotifyMessages.supports(W, "order_status")), "false");
        failed += expectEq("工作者帳號禁止 broadcast", String.valueOf(LineApiClient.of(W).broadcast(FlexMessageBuilder.wrapText("x"))), "false");

        // 主動選擇的語言不被國籍覆蓋（規格 3.2 第 1 點）
        String uid = "U_CHECK_LOCK";
        IdentityStore.override(W, uid, IdentityStore.preset("vn"));
        LineBotDispatcher.handle(BotEvent.text(W, uid, "中文"));
        failed += expect("W-06 越南籍改中文後任務卡為中文", LineBotDispatcher.handle(BotEvent.text(W, uid, "任務")), 0, "今日派工任務卡");

        // 回覆不得帶出 LINE userId（規格第拾章第 2 點）；只有 Mock 模式的綁定頁網址附模擬用 mockUid
        String secretUid = "U0123456789abcdef0123456789abcdef";
        String mockCard = FlexMessageBuilder.getFarmerBindingFlex(secretUid);
        failed += expectEq("Mock 模式綁定頁網址帶 mockUid", String.valueOf(mockCard.contains("line-bind.html?channel=farmer")
                && mockCard.contains("mockUid=" + secretUid)), "true");
        String savedSecret = F.secret, savedToken = F.accessToken, savedWs = W.secret, savedWt = W.accessToken;
        try {
            // 暫時視為正式模式
            F.secret = "real_secret_for_check"; F.accessToken = "real_token_for_check";
            W.secret = "real_secret_for_check"; W.accessToken = "real_token_for_check";
            String prodCard = FlexMessageBuilder.getFarmerBindingFlex(secretUid) + FlexMessageBuilder.getWorkerBindingFlex("vi", secretUid);
            failed += expectEq("正式模式綁定卡不含 LINE userId", String.valueOf(!prodCard.contains(secretUid)), "true");
        } finally {
            F.secret = savedSecret; F.accessToken = savedToken;
            W.secret = savedWs; W.accessToken = savedWt;
        }
        Config.FEATURE_FOREIGN_WORKERS = foreignSetting;

        System.out.println(failed == 0 ? "✅ 自動檢核全部通過！" : "❌ 自動檢核失敗 " + failed + " 項");
        return failed;
    }

    /**
     * W-01、W-03、W-04 本人資料卡片（WorkerCards）：以後端 data 的樣本產生，三語 JSON 合法、帶出本人資料；
     * 正式模式後端查不到時不出示範資料
     */
    private static int workerCardChecks() {
        int failed = 0;
        Channel W = Channel.WORKER;
        String url = FlexMessageBuilder.webPageUrl("worker-local.html");
        Map<String, Object> item = new java.util.HashMap<>(Map.of("orderId", "ORD-2026-005", "statusLabel", "已排定",
                "timeSlotLabel", "全日", "place", "屏東縣枋山鄉", "cropName", "愛文芒果", "categoryName", "採收分級",
                "area", 2.0, "foremanName", "林小祥", "foremanPhone", "0912-000-111", "preferredByFarmer", true));
        Map<String, Object> crew = Map.of("teamName", "工班 B", "total", 6L, "present", 4L, "absentNames", List.of("甲", "乙"));
        Map<String, Object> task = new java.util.HashMap<>(Map.of("kind", "task", "date", "2026-10-13", "workerName", "林小祥",
                "machinery", false, "items", List.of(item), "crew", crew));
        Map<String, Object> idle = new java.util.HashMap<>(Map.of("kind", "task", "date", "2026-10-13", "workerName", "林小祥",
                "machinery", true, "items", List.of(),
                "next", Map.of("startDate", "2026-10-15", "timeSlotLabel", "上午", "place", "雲林縣", "cropName", "蒜頭")));
        Map<String, Object> wItem = Map.of("orderId", "ORD-2026-005", "timeSlotLabel", "全日", "indoor", true,
                "place", "屏南冷鏈中心", "work", "分級包裝", "vehicle", "ABC-1234", "driver", "陳司機");
        Map<String, Object> weather = Map.of("kind", "weather", "date", "2026-10-13", "workerName", "林小祥",
                "weather", "暴雨 24°C", "alertLevel", "danger", "items", List.of(wItem));
        Map<String, Object> salary = new java.util.HashMap<>(Map.of("kind", "salary", "month", "2026-10", "workerName", "林小祥",
                "payDate", "2026-11-05", "workedHours", 64.5, "overtimeHours", 2.0, "baseSalary", 29500L,
                "agriSubsidy", 0L, "totalDeductions", 1201L, "netPay", 30000L));
        for (String lang : List.of("zh", "vi", "id")) {
            failed += expectEq("W-01 任務卡 " + lang, String.valueOf(validMessages(WorkerCards.task(lang, task, url))), "true");
            failed += expectEq("W-01 無任務 " + lang, String.valueOf(validMessages(WorkerCards.task(lang, idle, url))), "true");
            failed += expectEq("W-03 集合卡 " + lang, String.valueOf(validMessages(List.of(WorkerCards.weather(lang, weather)))), "true");
            failed += expectEq("W-04 薪資卡 " + lang, String.valueOf(validMessages(List.of(WorkerCards.salary(lang, salary, url)))), "true");
        }
        List<String> t = WorkerCards.task("zh", task, url);
        failed += expect("W-01 帶出本人訂單與地點", t, 0, "【ORD-2026-005】全日・已排定");
        failed += expect("W-01 面積", t, 0, "採收分級・愛文芒果・2 公頃");
        failed += expect("W-01 農場主指名", t, 0, "農場主指名您");
        failed += expect("W-01 組長附工班摘要", t, 1, "工班 B：6 人中 4 人已上班打卡");
        failed += expect("W-01 尚未打卡名單", t, 1, "尚未打卡：甲、乙");
        failed += expectEq("W-01 非組長只有一張卡", String.valueOf(WorkerCards.task("zh", idle, url).size()), "1");
        failed += expect("W-01 無任務時附下一筆", WorkerCards.task("zh", idle, url), 0, "下一筆：2026-10-15・上午・雲林縣・蒜頭");
        failed += expect("W-01 機耕師標題", WorkerCards.task("zh", idle, url), 0, "今日機耕工單");
        failed += expect("W-01 越語", WorkerCards.task("vi", task, url), 0, "Nhiệm vụ hôm nay");
        failed += expect("W-03 室內與接駁", List.of(WorkerCards.weather("zh", weather)), 0, "接駁：ABC-1234・司機 陳司機");
        failed += expect("W-03 改室內（印尼語）", List.of(WorkerCards.weather("id", weather)), 0, "dalam ruangan");
        String sal = WorkerCards.salary("zh", salary, url);
        failed += expect("W-04 預估實領", List.of(sal), 0, "預估實領：NT$ 30,000");
        failed += expect("W-04 工時", List.of(sal), 0, "本月出勤 64.5 小時（加班 2 小時）");
        failed += expectEq("W-04 津貼為 0 不列", String.valueOf(!sal.contains("農業津貼")), "true");

        // 正式模式（假金鑰）且後端查不到：不回示範資料（阮文雄、W001）
        String savedSecret = W.secret, savedToken = W.accessToken;
        try {
            W.secret = "real_secret_for_check"; W.accessToken = "real_token_for_check";
            for (String a : List.of("a=task", "a=weather", "a=salary")) {
                String u = "U_CHECK_PROD_" + a.substring(2);
                IdentityStore.override(W, u, IdentityStore.preset("local"));
                List<String> r = LineBotDispatcher.handle(BotEvent.postback(W, u, a));
                String all = String.join("", r);
                failed += expectEq("正式模式查不到 " + a + " 不出示範資料",
                        String.valueOf(all.contains("暫時查不到您的資料") && !all.contains("阮文雄") && !all.contains("W001")), "true");
            }
        } finally {
            W.secret = savedSecret; W.accessToken = savedToken;
        }
        return failed;
    }

    /** F-01 我的訂單、F-04 天候避險（FarmerCards）：以後端 data 的樣本產生；正式模式後端查不到時不出示範資料 */
    private static int farmerCardChecks() {
        int failed = 0;
        Channel F = Channel.FARMER;
        Map<String, Object> order = new java.util.HashMap<>(Map.of("id", "ORD-2026-001", "statusLabel", "施工中",
                "cropName", "愛文芒果", "categoryName", "採收分級", "area", 2.0, "farmLocation", "屏東縣枋山鄉",
                "startDate", "2026-10-13", "endDate", "2026-10-14", "timeSlotLabel", "全日", "teamName", "工班 B"));
        order.putAll(Map.of("workersPerDay", 4L, "foremanName", "陳建龍", "foremanPhone", "0921-000-111",
                "finalAmount", 52920L, "depositAmount", 10584L, "depositPaid", true));
        Map<String, Object> orders = Map.of("kind", "orders", "total", 3L, "order", order,
                "others", List.of(Map.of("id", "ORD-2026-013", "statusLabel", "已排定", "startDate", "2026-10-23", "cropName", "芒果")));
        String oc = FarmerCards.orders(orders);
        failed += expectEq("F-01 訂單卡 JSON", String.valueOf(validMessages(List.of(oc))), "true");
        failed += expect("F-01 本人訂單標題", List.of(oc), 0, "ORD-2026-001｜施工中");
        failed += expect("F-01 工班與人數", List.of(oc), 0, "指派工班：工班 B（每日 4 人）");
        failed += expect("F-01 金額與定金", List.of(oc), 0, "NT$ 52,920（定金 NT$ 10,584 已付）");
        failed += expect("F-01 其他訂單", List.of(oc), 0, "ORD-2026-013（已排定・2026-10-23・芒果）");
        failed += expect("F-01 撥打帶隊對口", List.of(oc), 0, "tel:0921000111");
        failed += expectEq("F-01 不含寫死資料", String.valueOf(!oc.contains("0912-222-444") && !oc.contains("agridispatch.tw/track")
                && !oc.contains("阿迪")), "true");
        String none = FarmerCards.orders(Map.of("kind", "orders", "total", 0L));
        failed += expect("F-01 沒有訂單", List.of(none), 0, "目前查不到您名下的訂單");

        Map<String, Object> wo = new java.util.HashMap<>(Map.of("id", "ORD-2026-001", "cropName", "愛文芒果", "place", "屏東縣枋山鄉",
                "startDate", "2026-10-13", "endDate", "2026-10-14", "timeSlotLabel", "全日", "workingToday", true, "teamIndoor", true,
                "region", Map.of("region", "屏東枋山", "condition", "雷陣雨", "temp", "27°C", "rainChance", "80%", "advice", "暫停採收")));
        Map<String, Object> later = Map.of("id", "ORD-2026-013", "cropName", "芒果", "place", "枋山", "startDate", "2026-10-15",
                "endDate", "2026-10-16", "workingToday", false, "teamIndoor", false);
        String wc = FarmerCards.weather(Map.of("kind", "weather", "date", "2026-10-13", "weather", "暴雨", "alertLevel", "danger",
                "orders", List.of(wo, later)));
        failed += expectEq("F-04 天候卡 JSON", String.valueOf(validMessages(List.of(wc))), "true");
        failed += expect("F-04 當地天氣", List.of(wc), 0, "當地：雷陣雨・27°C・降雨 80%");
        failed += expect("F-04 工班改室內", List.of(wc), 0, "今日工班改到室內避險作業");
        failed += expect("F-04 近期開工", List.of(wc), 0, "2026-10-15 開工，出發前會依天候再確認");
        failed += expect("F-04 警戒色", List.of(wc), 0, "#B91C1C");
        failed += expect("F-04 沒有訂單", List.of(FarmerCards.weather(Map.of("kind", "weather", "weather", "晴", "alertLevel", "normal",
                "orders", List.of()))), 0, "目前沒有施工中或 3 天內開工的訂單");

        // 正式模式（假金鑰）且後端查不到：不回示範資料
        String savedSecret = F.secret, savedToken = F.accessToken;
        try {
            F.secret = "real_secret_for_check"; F.accessToken = "real_token_for_check";
            for (String a : List.of("a=orders", "a=weather")) {
                String u = "U_CHECK_FPROD_" + a.substring(2);
                IdentityStore.override(F, u, IdentityStore.preset("farmer"));
                String all = String.join("", LineBotDispatcher.handle(BotEvent.postback(F, u, a)));
                failed += expectEq("正式模式查不到 " + a + " 不出示範資料", String.valueOf(all.contains("暫時查不到您的資料")
                        && !all.contains("枋山愛文芒果派工確認單") && !all.contains("突發暴雨避險")), "true");
            }
        } finally {
            F.secret = savedSecret; F.accessToken = savedToken;
        }
        return failed;
    }

    private interface Ev { BotEvent make(Channel c, String userId); }

    private static Ev text(String t) { return (c, u) -> BotEvent.text(c, u, t); }
    private static Ev pb(String d) { return (c, u) -> BotEvent.postback(c, u, d); }
    private static Ev follow(String lang) { return (c, u) -> BotEvent.follow(c, u, lang); }
    private static Ev voice(String t) { return (c, u) -> BotEvent.voice(c, u, t, null); }
    private static Ev voiceError(String err) { return (c, u) -> BotEvent.voice(c, u, "", err); }

    /**
     * 語音訊息（規格 5.5）：分派器的確認卡，以及以本機假 Whisper 伺服器驗證 multipart 上傳與回應解析（不呼叫 OpenAI）
     */
    private static int voiceChecks() {
        int failed = 0;
        Channel F = Channel.FARMER, W = Channel.WORKER;
        failed += check("V 語音：今天的任務 → 確認卡", W, "local", voice("我今天的任務是什麼"), 0, "我聽到：「我今天的任務是什麼」");
        failed += check("V 語音：確認鈕送 a=task", W, "local", voice("我今天的任務是什麼"), 0, "\"data\": \"a=task\"");
        failed += expectEq("V 語音：只回一張確認卡", String.valueOf(LineBotDispatcher.handle(BotEvent.voice(W, "U_V1", "薪資", null)).size()), "1");
        failed += check("V 語音：薪資 → a=salary", W, "local", voice("查一下我的薪資"), 0, "a=salary");
        failed += check("V 語音：集合地點 → a=weather", W, "local", voice("明天在哪裡集合"), 0, "a=weather");
        failed += check("V 語音：都不是 → a=help", W, "local", voice("出勤紀錄"), 0, "a=help");
        failed += check("V 語音：聽不出動作 → 說明", W, "local", voice("你好"), 0, "聽不出要查詢什麼");
        failed += check("V 語音：聽不出動作附說明卡", W, "local", voice("你好"), 1, "使用說明");
        failed += check("V 語音：辨識失敗", W, "local", voiceError(BotEvent.VOICE_FAILED), 0, "沒有聽清楚");
        failed += check("V 語音：超過 60 秒", W, "local", voiceError(BotEvent.VOICE_TOO_LONG), 0, "60 秒");
        failed += check("V 語音：空白結果視為失敗", W, "local", voice("  "), 0, "沒有聽清楚");
        failed += check("V 語音：外勞下架越南籍 → 暫停", W, "vn", voice("nhiệm vụ"), 0, "暫停中");
        failed += check("V 語音：農場主帳號不辨識", F, null, voice("派工"), 0, "只看得懂文字");
        failed += check("V 語音：問農場主功能 → 引導鈕", W, "local", voice("費率多少錢"), 0, "a=cross");
        // 外籍開關打開時：依使用者語言回覆
        Config.FEATURE_FOREIGN_WORKERS = true;
        failed += check("V 語音（越語）：確認卡", W, "vn", voice("nhiệm vụ hôm nay"), 0, "Tôi nghe được");
        failed += check("V 語音（越語）：說中文 → 切換語言鈕", W, "vn", voice("中文"), 0, "a=lang&v=zh");
        Config.FEATURE_FOREIGN_WORKERS = false;

        failed += expectEq("V 簡轉繁（關鍵字）", SpeechToText.toTraditional("查一下我的薪资和出勤纪录，台风天在哪集合"),
                "查一下我的薪資和出勤紀錄，颱風天在哪集合");
        failed += expectEq("V 提示詞回聲視為未辨識", String.valueOf(SpeechToText.isPromptEcho(I18n.t("zh", "voice.prompt"), "zh")), "true");
        failed += expectEq("V 正常句子不是回聲", String.valueOf(SpeechToText.isPromptEcho("今天的任務和天氣", "zh")), "false");

        // 功能開關：預設停用（未設定金鑰）時，webhook 的語音照舊轉成 OTHER_MESSAGE
        boolean voiceSetting = Config.FEATURE_WORKER_VOICE;
        String keySetting = Config.STT_API_KEY, urlSetting = Config.STT_API_URL;
        Config.FEATURE_WORKER_VOICE = false;
        failed += expectEq("V 開關關閉：說明卡不提語音", String.valueOf(FlexMessageBuilder.getWorkerHelpFlex("zh").contains("語音")), "false");
        failed += expectEq("V 語音事件解析（不辨識時）", String.valueOf(WebhookHandler.toBotEvent(W, "U_V",
                Map.of("type", "message", "message", Map.of("type", "audio", "id", "1", "duration", 3000))).type()), "OTHER_MESSAGE");

        com.sun.net.httpserver.HttpServer fake = null;
        try {
            fake = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
            final String[] got = new String[2];
            fake.createContext("/v1/audio/transcriptions", ex -> {
                got[0] = ex.getRequestHeaders().getFirst("Authorization");
                got[1] = new String(ex.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                byte[] out = "{\"text\":\"我今天的任务\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                ex.getResponseHeaders().set("Content-Type", "application/json");
                ex.sendResponseHeaders(200, out.length);
                ex.getResponseBody().write(out);
                ex.close();
            });
            fake.createContext("/fail", ex -> { ex.sendResponseHeaders(401, -1); ex.close(); });
            fake.start();
            String base = "http://127.0.0.1:" + fake.getAddress().getPort();
            Config.FEATURE_WORKER_VOICE = true;
            Config.STT_API_KEY = "sk-test-check";
            Config.STT_API_URL = base + "/v1/audio/transcriptions";
            failed += expectEq("V 開關打開：說明卡提語音", String.valueOf(FlexMessageBuilder.getWorkerHelpFlex("zh").contains("語音")), "true");
            String text = SpeechToText.transcribe("FAKE-M4A".getBytes(java.nio.charset.StandardCharsets.UTF_8), "zh");
            failed += expectEq("V Whisper 回應解析＋簡轉繁", String.valueOf(text), "我今天的任務");
            failed += expectEq("V Whisper 帶 Bearer 金鑰", String.valueOf(got[0]), "Bearer sk-test-check");
            String body = String.valueOf(got[1]);
            failed += expectEq("V multipart 含模型、語言、提示詞、檔案", String.valueOf(body.contains("name=\"model\"\r\n\r\n" + Config.STT_MODEL)
                    && body.contains("name=\"language\"\r\n\r\nzh") && body.contains("name=\"prompt\"")
                    && body.contains("filename=\"voice.m4a\"") && body.contains("FAKE-M4A")), "true");
            Config.STT_API_URL = base + "/fail";
            failed += expectEq("V Whisper 失敗回 null", String.valueOf(SpeechToText.transcribe(new byte[]{1}, "zh")), "null");
            // Mock 模式不下載 LINE 音檔 → 辨識失敗事件
            failed += expectEq("V Mock 模式下載不到音檔 → failed", String.valueOf(SpeechToText.toEvent(W, "U_V2", "123", 3000).voiceError()), BotEvent.VOICE_FAILED);
            failed += expectEq("V 超過 60 秒不下載", String.valueOf(SpeechToText.toEvent(W, "U_V3", "123", 61_000).voiceError()), BotEvent.VOICE_TOO_LONG);
        } catch (java.io.IOException e) {
            failed += expectEq("V 假 Whisper 伺服器啟動", e.getMessage(), "OK");
        } finally {
            if (fake != null) fake.stop(0);
            Config.FEATURE_WORKER_VOICE = voiceSetting;
            Config.STT_API_KEY = keySetting;
            Config.STT_API_URL = urlSetting;
        }
        return failed;
    }

    private static int check(String name, Channel channel, String as, Ev ev, int index, String expected) {
        String userId = "U_CHECK_" + (++seq);
        if (as != null) IdentityStore.override(channel, userId, IdentityStore.preset(as));
        return expect(name, LineBotDispatcher.handle(ev.make(channel, userId)), index, expected);
    }

    private static int expect(String name, List<String> replies, int index, String expected) {
        String got = index < replies.size() ? replies.get(index) : "";
        // 「!文字」：該則有內容且不得含此文字
        boolean ok = expected.startsWith("!") ? !got.isEmpty() && !got.contains(expected.substring(1)) : got.contains(expected);
        System.out.printf("  %s %-36s → %s%n", ok ? "✅" : "❌", name, ok ? summary(got) : "預期第 " + (index + 1)
                + " 則含「" + expected + "」，實際：" + (got.isEmpty() ? "（無）" : summary(got)));
        return ok ? 0 : 1;
    }

    private static int expectEq(String name, String got, String expected) {
        boolean ok = expected.equals(got);
        System.out.printf("  %s %-36s → %s%n", ok ? "✅" : "❌", name, ok ? got : "預期 " + expected + "，實際 " + got);
        return ok ? 0 : 1;
    }

    /** 每則都是可解析的 LINE 訊息物件（flex 有 altText 與 bubble，text 有內容） */
    @SuppressWarnings("unchecked")
    private static boolean validMessages(List<String> messages) {
        if (messages.isEmpty() || messages.size() > 5) return false;
        for (String m : messages) {
            Object o = SimpleJson.parse(m);
            if (!(o instanceof Map)) return false;
            Map<String, Object> msg = (Map<String, Object>) o;
            if ("flex".equals(msg.get("type"))) {
                if (msg.get("altText") == null || !(msg.get("contents") instanceof Map)) return false;
            } else if (!"text".equals(msg.get("type")) || msg.get("text") == null) {
                return false;
            }
        }
        return true;
    }

    /** 讀出選單 JSON 每格的 postback data */
    @SuppressWarnings("unchecked")
    private static List<String> menuData(String key) {
        List<String> out = new java.util.ArrayList<>();
        try {
            Map<String, Object> m = SimpleJson.parseObject(java.nio.file.Files.readString(RichMenus.dir().resolve(key + ".json")));
            for (Object a : (List<Object>) m.get("areas")) {
                out.add(String.valueOf(((Map<String, Object>) ((Map<String, Object>) a).get("action")).get("data")));
            }
        } catch (Exception e) {
            out.add("讀取失敗:" + e.getMessage());
        }
        return out;
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
