package com.agridispatch.server;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 智農派工 (AgriDispatch) - LINE Bot 核心服務引擎 (純 Java 零依賴實現)
 * 支援 HMAC-SHA256 簽章驗證、Flex Message 卡片產生、多語系關鍵字處理、Rich Menu 設定與推播模擬
 */
public class LineBotService {
    private static LineBotService instance;
    private final Database db = Database.getInstance();
    private final HttpClient httpClient = HttpClient.newHttpClient();

    public static synchronized LineBotService getInstance() {
        if (instance == null) {
            instance = new LineBotService();
        }
        return instance;
    }

    private LineBotService() {}

    /**
     * 1. 驗證 LINE Webhook X-Line-Signature (HMAC-SHA256)
     */
    public boolean verifySignature(String body, String signature) {
        String secret = db.getLineChannelSecret();
        if (secret == null || secret.isBlank() || signature == null) {
            return false;
        }
        // 若為模擬密鑰，允許通過
        if ("mock_secret_88888888".equals(secret)) {
            return true;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec secretKeySpec = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(secretKeySpec);
            byte[] hash = mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
            String expectedSignature = Base64.getEncoder().encodeToString(hash);
            return expectedSignature.equals(signature);
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    /**
     * 2. 處理 LINE Webhook 接收到的事件清單
     */
    public List<Map<String, Object>> processWebhookEvents(String rawBody) {
        List<Map<String, Object>> replies = new ArrayList<>();
        try {
            // 簡易解析 events JSON
            String normalized = rawBody.replaceAll("\\s+", "");
            if (!normalized.contains("\"events\":")) {
                return replies;
            }

            // 針對模擬事件或標準事件進行處理
            // 模擬預設事件：若包含 message 關鍵字
            String userText = "";
            String replyToken = "mock_reply_token_" + System.currentTimeMillis();
            String userId = "U1001_FARMER";

            if (rawBody.contains("\"text\":")) {
                int start = rawBody.indexOf("\"text\":") + 7;
                while (start < rawBody.length() && (rawBody.charAt(start) == ' ' || rawBody.charAt(start) == '"')) {
                    start++;
                }
                int end = rawBody.indexOf("\"", start);
                if (end > start) {
                    userText = rawBody.substring(start, end);
                }
            }

            if (rawBody.contains("\"userId\":")) {
                int start = rawBody.indexOf("\"userId\":") + 9;
                while (start < rawBody.length() && (rawBody.charAt(start) == ' ' || rawBody.charAt(start) == '"')) {
                    start++;
                }
                int end = rawBody.indexOf("\"", start);
                if (end > start) {
                    userId = rawBody.substring(start, end);
                }
            }

            Map<String, Object> reply = generateReplyForText(userId, userText);
            reply.put("replyToken", replyToken);
            replies.add(reply);

            // 記錄至資料庫
            Map<String, Object> logMsg = new LinkedHashMap<>();
            logMsg.put("sender", userId);
            logMsg.put("type", "incoming");
            logMsg.put("content", userText);
            logMsg.put("reply", reply.get("altText"));
            db.addLineMessage(logMsg);

        } catch (Exception e) {
            e.printStackTrace();
        }
        return replies;
    }

    /**
     * 3. 依據使用者輸入或點擊生成 Flex Message 回應
     */
    public Map<String, Object> generateReplyForText(String lineUserId, String text) {
        String clean = text == null ? "" : text.trim().toLowerCase();
        Map<String, Object> lineUser = db.findLineUserByLineId(lineUserId);
        String lang = lineUser != null ? (String) lineUser.get("lang") : "zh";
        String role = lineUser != null ? (String) lineUser.get("role") : "farmer";

        // 語言切換
        if (clean.contains("越南") || clean.contains("viet") || clean.contains("tiếng việt")) {
            if (lineUser != null) lineUser.put("lang", "vi");
            return buildTextReply("✅ Đã chuyển đổi ngôn ngữ sang Tiếng Việt. Gõ 'nhiệm vụ' để xem thẻ điều phái!");
        } else if (clean.contains("印尼") || clean.contains("indo") || clean.contains("bahasa")) {
            if (lineUser != null) lineUser.put("lang", "id");
            return buildTextReply("✅ Bahasa telah diubah ke Bahasa Indonesia. Ketik 'tugas' untuk melihat kartu kerja!");
        } else if (clean.contains("中文") || clean.contains("繁體") || clean.contains("chinese")) {
            if (lineUser != null) lineUser.put("lang", "zh");
            return buildTextReply("✅ 已切換為繁體中文介面。請點選圖文選單或輸入關鍵字查詢！");
        }

        // 0. 智慧查詢功能
        if (clean.contains("查訂單") || clean.contains("訂單") || clean.contains("工單") || clean.contains("ord-")
                || clean.contains("單號") || clean.contains("派工單") || clean.contains("進度")) {
            return buildFarmerOrderFlex();
        }
        if (clean.contains("查產曆") || clean.contains("產曆") || clean.contains("產季") || clean.contains("收穫")
                || clean.contains("採收") || clean.contains("芒果") || clean.contains("洋蔥") || clean.contains("鳳梨")
                || clean.contains("西瓜") || clean.contains("青蔥") || clean.contains("茶")) {
            return buildEarlyBirdFlex();
        }
        if (clean.contains("查工班") || clean.contains("工班") || clean.contains("農工") || clean.contains("外展")
                || clean.contains("移工") || clean.contains("名冊") || clean.contains("司機")) {
            return buildWorkerTaskFlex(lang, db.isRainMode());
        }
        if (clean.contains("查費率") || clean.contains("費率") || clean.contains("價格") || clean.contains("多少錢")
                || clean.contains("計費")) {
            return buildEarlyBirdFlex();
        }
        if (clean.contains("查詢") || clean.contains("查") || clean.contains("search") || clean.contains("menu") || clean.contains("功能")) {
            return buildHelpMenuFlex(lang);
        }

        // 今日派工 / 任務卡
        if (clean.contains("派工") || clean.contains("任務") || clean.contains("工單") || clean.contains("task")
                || clean.contains("nhiệm vụ") || clean.contains("tugas")) {
            if ("worker_foreign".equals(role)) {
                return buildWorkerTaskFlex(lang, db.isRainMode());
            } else if ("worker_local".equals(role)) {
                return buildMachineryTaskFlex();
            } else {
                return buildFarmerOrderFlex();
            }
        }

        // 天候避險
        if (clean.contains("避險") || clean.contains("天氣") || clean.contains("暴雨") || clean.contains("雨")
                || clean.contains("weather") || clean.contains("mưa") || clean.contains("hujan")) {
            return buildWeatherHedgeFlex();
        }

        // 早鳥工時包
        if (clean.contains("早鳥") || clean.contains("預購") || clean.contains("優惠") || clean.contains("early")) {
            return buildEarlyBirdFlex();
        }

        // 機耕 / 代噴
        if (clean.contains("機耕") || clean.contains("代噴") || clean.contains("無人機") || clean.contains("曳引機")) {
            return buildMachineryTaskFlex();
        }

        // 薪資津貼 / 獎勵
        if (clean.contains("薪資") || clean.contains("津貼") || clean.contains("8800") || clean.contains("lương") || clean.contains("gaji")) {
            return buildSalaryBonusFlex(lang);
        }

        // 帳號綁定
        if (clean.contains("綁定") || clean.contains("帳號") || clean.contains("bind")) {
            return buildBindingFlex(lineUserId);
        }

        // 預設歡迎與說明選單
        return buildHelpMenuFlex(lang);
    }

    /**
     * 4. 產生農場主派工確認 Flex Message
     */
    public Map<String, Object> buildFarmerOrderFlex() {
        Map<String, Object> flex = new LinkedHashMap<>();
        flex.put("type", "flex");
        flex.put("altText", "📋 智農派工 - 枋山愛文芒果派工確認單");

        Map<String, Object> contents = new LinkedHashMap<>();
        contents.put("type", "bubble");
        contents.put("size", "mega");

        // 標題區
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("type", "box");
        header.put("layout", "vertical");
        header.put("backgroundColor", "#047857");
        header.put("paddingAll", "20px");

        List<Map<String, Object>> headerItems = new ArrayList<>();
        headerItems.add(createLabel("智農派工・履約派遣單", "#A7F3D0", "xs", "bold"));
        headerItems.add(createLabel("枋山愛文芒果 (6.0 分地)", "#FFFFFF", "xl", "bold"));
        headerItems.add(createLabel("單號: ORD-2026-001 ｜ 早鳥保證出工", "#D1FAE5", "xs", "regular"));
        header.put("contents", headerItems);
        contents.put("header", header);

        // 內容區
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "box");
        body.put("layout", "vertical");
        body.put("spacing", "md");
        body.put("paddingAll", "20px");

        List<Map<String, Object>> bodyItems = new ArrayList<>();
        bodyItems.add(createRowKV("作業日期", "2026/05/20 (明日 07:00 出工)"));
        bodyItems.add(createRowKV("指派工班", "工班 B 組 (6人，領班: 阿迪)"));
        bodyItems.add(createRowKV("接送專車", "智農 2 號車 (司機: 李大哥 0912-222-444)"));
        bodyItems.add(createRowKV("作業手路", "留蒂 0.5cm 護果粉外銷級採收"));
        bodyItems.add(createRowKV("合約保證", "SLA 遲到免單、放鳥雙倍賠償"));
        bodyItems.add(createRowKV("應付金額", "NT$ 45,360 (早鳥9折 / 定金已收 20%)"));
        body.put("contents", bodyItems);
        contents.put("body", body);

        // 底部操作區
        Map<String, Object> footer = new LinkedHashMap<>();
        footer.put("type", "box");
        footer.put("layout", "vertical");
        footer.put("spacing", "sm");
        footer.put("paddingAll", "16px");

        List<Map<String, Object>> footerItems = new ArrayList<>();
        footerItems.add(createButton("📍 查看工班 GPS 即時位置", "https://agridispatch.tw/track/ORD-2026-001", "#059669"));
        footerItems.add(createButton("📞 聯絡調度專車司機", "tel:0912222444", "#3B82F6"));
        footer.put("contents", footerItems);
        contents.put("footer", footer);

        flex.put("contents", contents);
        return flex;
    }

    /**
     * 5. 產生外籍移工三語每日任務卡 Flex Message
     */
    public Map<String, Object> buildWorkerTaskFlex(String lang, boolean isRain) {
        Map<String, Object> flex = new LinkedHashMap<>();
        flex.put("type", "flex");

        String title = "zh".equals(lang) ? "今日派工任務卡" : ("vi".equals(lang) ? "Thẻ Nhiệm Vụ Hôm Nay" : "Kartu Tugas Hari Ini");
        String badge = isRain
                ? ("zh".equals(lang) ? "🌧️ 突發暴雨・冷鏈避險中" : ("vi".equals(lang) ? "🌧️ Tránh Mưa・Chuyển Kho Lạnh" : "🌧️ Mitigasi Hujan・Cold Chain"))
                : ("zh".equals(lang) ? "☀️ 正常出勤・專車接送" : ("vi".equals(lang) ? "☀️ Đi Làm Bình Thường・Xe Đón" : "☀️ Masuk Kerja・Dijemput Armada"));

        String loc = isRain
                ? ("zh".equals(lang) ? "🏢 屏南冷鏈預冷集貨場 (B棟 分級包裝線)" : ("vi".equals(lang) ? "🏢 Kho Lạnh Bình Nam (Khu B Đóng Gói)" : "🏢 Gudang Pendingin Pingnan (Gedung B)"))
                : ("zh".equals(lang) ? "🌾 高樹洋蔥產銷專區 (第 3 田區)" : ("vi".equals(lang) ? "🌾 Tổ Hợp Hành Cao Thụ (Ruộng Số 3)" : "🌾 Kebun Bawang Gaoshu (Lahan No. 3)"));

        String task = isRain
                ? ("zh".equals(lang) ? "洋蔥低溫乾燥分級、自動秤重裝箱" : ("vi".equals(lang) ? "Phân loại hành khô, đóng thùng cân tự động" : "Sortir bawang kering, kemas timbang otomatis"))
                : ("zh".equals(lang) ? "洋蔥採收剪葉、田間裝袋 (每袋 20kg)" : ("vi".equals(lang) ? "Thu hoạch cắt lá hành, đóng bao 20kg" : "Panen bawang, potong daun, kemas karung 20kg"));

        String driver = "智農 1 號車 (張大哥 / 0912-111-333)";
        String time = "06:30 集合上車 / 07:00 ~ 16:00 作業";

        flex.put("altText", "📋 " + title + " - " + badge);

        Map<String, Object> contents = new LinkedHashMap<>();
        contents.put("type", "bubble");
        contents.put("size", "mega");

        // Header
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("type", "box");
        header.put("layout", "vertical");
        header.put("backgroundColor", isRain ? "#1E3A8A" : "#047857");
        header.put("paddingAll", "20px");

        List<Map<String, Object>> headerItems = new ArrayList<>();
        headerItems.add(createLabel(badge, isRain ? "#93C5FD" : "#A7F3D0", "xs", "bold"));
        headerItems.add(createLabel(title, "#FFFFFF", "xl", "bold"));
        headerItems.add(createLabel("工號: W001 阮文雄 (Nguyễn Văn Hùng)", "#E2E8F0", "xs", "regular"));
        header.put("contents", headerItems);
        contents.put("header", header);

        // Body
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "box");
        body.put("layout", "vertical");
        body.put("spacing", "md");
        body.put("paddingAll", "20px");

        List<Map<String, Object>> bodyItems = new ArrayList<>();
        bodyItems.add(createRowKV("zh".equals(lang) ? "出勤時間" : ("vi".equals(lang) ? "Thời Gian" : "Jam Kerja"), time));
        bodyItems.add(createRowKV("zh".equals(lang) ? "作業地點" : ("vi".equals(lang) ? "Địa Điểm" : "Lokasi"), loc));
        bodyItems.add(createRowKV("zh".equals(lang) ? "接送專車" : ("vi".equals(lang) ? "Xe Đưa Đón" : "Kendaraan"), driver));
        bodyItems.add(createRowKV("zh".equals(lang) ? "作業內容" : ("vi".equals(lang) ? "Công Việc" : "Tugas"), task));
        bodyItems.add(createRowKV("zh".equals(lang) ? "手路標準" : ("vi".equals(lang) ? "Tiêu Chuẩn" : "Standar"), "留蒂 0.5cm 護果粉 / 切口平整"));
        body.put("contents", bodyItems);
        contents.put("body", body);

        // Footer
        Map<String, Object> footer = new LinkedHashMap<>();
        footer.put("type", "box");
        footer.put("layout", "vertical");
        footer.put("spacing", "sm");
        footer.put("paddingAll", "16px");

        List<Map<String, Object>> footerItems = new ArrayList<>();
        footerItems.add(createButton("📍 " + ("zh".equals(lang) ? "GPS 抵達現場數位打卡" : ("vi".equals(lang) ? "Điểm danh GPS hiện trường" : "Presensi Digital GPS")),
                "https://agridispatch.tw/clockin?w=W001", "#10B981"));
        footer.put("contents", footerItems);
        contents.put("footer", footer);

        flex.put("contents", contents);
        return flex;
    }

    /**
     * 6. 產生天候避險緊急通知 Flex Message
     */
    public Map<String, Object> buildWeatherHedgeFlex() {
        boolean isRain = db.isRainMode();
        Map<String, Object> flex = new LinkedHashMap<>();
        flex.put("type", "flex");
        flex.put("altText", isRain ? "🌧️ 突發暴雨避險快訊 - 全體轉移室內冷鏈" : "☀️ 天候良好・田間出勤中");

        Map<String, Object> contents = new LinkedHashMap<>();
        contents.put("type", "bubble");
        contents.put("size", "mega");

        Map<String, Object> header = new LinkedHashMap<>();
        header.put("type", "box");
        header.put("layout", "vertical");
        header.put("backgroundColor", isRain ? "#1E3A8A" : "#065F46");
        header.put("paddingAll", "20px");

        List<Map<String, Object>> headerItems = new ArrayList<>();
        headerItems.add(createLabel(isRain ? "⚡ 天候避險大腦啟動" : "🌱 田區天候監測正常", isRain ? "#93C5FD" : "#A7F3D0", "xs", "bold"));
        headerItems.add(createLabel(isRain ? "突發梅雨警報・專車安全轉移" : "全區田間正常採收出勤", "#FFFFFF", "lg", "bold"));
        header.put("contents", headerItems);
        contents.put("header", header);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "box");
        body.put("layout", "vertical");
        body.put("spacing", "md");
        body.put("paddingAll", "20px");

        List<Map<String, Object>> bodyItems = new ArrayList<>();
        bodyItems.add(createRowKV("目前天候", db.getCurrentWeather()));
        bodyItems.add(createRowKV("避險成效", "工班出勤率 100% (20/20 人在崗) ｜ 零閒置虧損"));
        bodyItems.add(createRowKV("轉移據點", "🏢 屏南生鮮冷鏈分級包裝中心 (A/B棟)"));
        bodyItems.add(createRowKV("作業內容", "芒果清洗預冷、自動秤重套網袋外銷封箱"));
        body.put("contents", bodyItems);
        contents.put("body", body);

        flex.put("contents", contents);
        return flex;
    }

    /**
     * 7. 產生早鳥工時包 Flex Message
     */
    public Map<String, Object> buildEarlyBirdFlex() {
        Map<String, Object> flex = new LinkedHashMap<>();
        flex.put("type", "flex");
        flex.put("altText", "⚡ 2026 產季早鳥保證工時包 - 預付20%定金享9折");

        Map<String, Object> contents = new LinkedHashMap<>();
        contents.put("type", "bubble");
        contents.put("size", "mega");

        Map<String, Object> header = new LinkedHashMap<>();
        header.put("type", "box");
        header.put("layout", "vertical");
        header.put("backgroundColor", "#D97706");
        header.put("paddingAll", "20px");

        List<Map<String, Object>> headerItems = new ArrayList<>();
        headerItems.add(createLabel("⚡ 產季防搶工首選", "#FEF3C7", "xs", "bold"));
        headerItems.add(createLabel("產季早鳥保證工時包", "#FFFFFF", "xl", "bold"));
        headerItems.add(createLabel("預付 20% 定金 ｜ 享 9 折 ｜ SLA 保證出工", "#FDE68A", "xs", "regular"));
        header.put("contents", headerItems);
        contents.put("header", header);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "box");
        body.put("layout", "vertical");
        body.put("spacing", "sm");
        body.put("paddingAll", "20px");

        List<Map<String, Object>> bodyItems = new ArrayList<>();
        bodyItems.add(createRowKV("合約承諾", "遲到免單、放鳥雙倍賠償"));
        bodyItems.add(createRowKV("合法保障", "合法特許合作社雇主、全額勞健保、合法發票"));
        bodyItems.add(createRowKV("適用作物", "芒果、洋蔥、設施甜椒、三星蔥、文旦柚"));
        bodyItems.add(createRowKV("專車接送", "智農 1~4 號冷氣專車定點接駁"));
        body.put("contents", bodyItems);
        contents.put("body", body);

        Map<String, Object> footer = new LinkedHashMap<>();
        footer.put("type", "box");
        footer.put("layout", "vertical");
        footer.put("paddingAll", "16px");
        footer.put("contents", List.of(createButton("⚡ 線上試算與早鳥預約", "https://agridispatch.tw/web/app.html#farmer", "#D97706")));
        contents.put("footer", footer);

        flex.put("contents", contents);
        return flex;
    }

    /**
     * 8. 產生本國機耕師任務卡 Flex Message
     */
    public Map<String, Object> buildMachineryTaskFlex() {
        Map<String, Object> flex = new LinkedHashMap<>();
        flex.put("type", "flex");
        flex.put("altText", "🚜 本國專業農機與無人機代噴任務卡");

        Map<String, Object> contents = new LinkedHashMap<>();
        contents.put("type", "bubble");
        contents.put("size", "mega");

        Map<String, Object> header = new LinkedHashMap<>();
        header.put("type", "box");
        header.put("layout", "vertical");
        header.put("backgroundColor", "#1F2937");
        header.put("paddingAll", "20px");
        header.put("contents", List.of(
                createLabel("本國專業機耕領航員", "#9CA3AF", "xs", "bold"),
                createLabel("大疆 T50 植保機雙證照代噴", "#10B981", "lg", "bold"),
                createLabel("機耕師: 陳建宏 (#W012) ｜ 工班 C 組", "#E5E7EB", "xs", "regular")
        ));
        contents.put("header", header);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "box");
        body.put("layout", "vertical");
        body.put("spacing", "sm");
        body.put("paddingAll", "20px");
        body.put("contents", List.of(
                createRowKV("作業排程", "今日 06:00 ~ 11:30 (清晨微風時段)"),
                createRowKV("作業地點", "雲林設施甜椒產銷專區 (第 5 號溫室外區)"),
                createRowKV("設備檢核", "噴嘴超音波清洗完畢、50米避障雷達校準完成"),
                createRowKV("實領薪資", "底薪 $42,000 + 分地統包分紅 $12,500 = $54,500")
        ));
        contents.put("body", body);

        flex.put("contents", contents);
        return flex;
    }

    /**
     * 9. 產生政府津貼與薪資 Flex Message
     */
    public Map<String, Object> buildSalaryBonusFlex(String lang) {
        Map<String, Object> flex = new LinkedHashMap<>();
        flex.put("type", "flex");
        flex.put("altText", "💰 薪資明細與中央就業獎勵津貼");

        Map<String, Object> contents = new LinkedHashMap<>();
        contents.put("type", "bubble");
        contents.put("size", "mega");

        Map<String, Object> header = new LinkedHashMap<>();
        header.put("type", "box");
        header.put("layout", "vertical");
        header.put("backgroundColor", "#065F46");
        header.put("paddingAll", "20px");
        header.put("contents", List.of(
                createLabel("🏛️ 農業部外展農務津貼透明看板", "#A7F3D0", "xs", "bold"),
                createLabel("本月所得預估: NT$ 39,790", "#FFFFFF", "xl", "bold")
        ));
        contents.put("header", header);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "box");
        body.put("layout", "vertical");
        body.put("spacing", "sm");
        body.put("paddingAll", "20px");
        body.put("contents", List.of(
                createRowKV("外展保障底薪", "NT$ 28,590 (每月 5 號入帳)"),
                createRowKV("農業部就業獎勵", "+ NT$ 8,800 (中央政府直接撥付)"),
                createRowKV("高溫與交通補助", "+ NT$ 2,400"),
                createRowKV("勞健保與職災", "合作社全額依法投保 500 萬意外險")
        ));
        contents.put("body", body);

        flex.put("contents", contents);
        return flex;
    }

    /**
     * 10. 產生帳號綁定 Flex Message
     */
    public Map<String, Object> buildBindingFlex(String lineUserId) {
        Map<String, Object> flex = new LinkedHashMap<>();
        flex.put("type", "flex");
        flex.put("altText", "🔗 智農派工系統 - LINE 帳號綁定");

        Map<String, Object> contents = new LinkedHashMap<>();
        contents.put("type", "bubble");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "box");
        body.put("layout", "vertical");
        body.put("paddingAll", "20px");
        body.put("spacing", "md");
        body.put("contents", List.of(
                createLabel("🔗 綁定您的智農派工帳號", "#10B981", "lg", "bold"),
                createLabel("您的 LINE ID: " + lineUserId, "#64748B", "xs", "regular"),
                createLabel("綁定後即可即時接收出工推播、天候避險警報與薪資津貼明細！", "#334155", "sm", "regular")
        ));
        contents.put("body", body);

        Map<String, Object> footer = new LinkedHashMap<>();
        footer.put("type", "box");
        footer.put("layout", "vertical");
        footer.put("paddingAll", "16px");
        footer.put("contents", List.of(
                createButton("農場主身分綁定", "https://agridispatch.tw/web/app.html#farmer", "#059669"),
                createButton("外籍移工身分綁定", "https://agridispatch.tw/web/app.html#worker_foreign", "#3B82F6")
        ));
        contents.put("footer", footer);

        flex.put("contents", contents);
        return flex;
    }

    /**
     * 11. 產生說明與快速導覽選單
     */
    public Map<String, Object> buildHelpMenuFlex(String lang) {
        Map<String, Object> flex = new LinkedHashMap<>();
        flex.put("type", "flex");
        flex.put("altText", "🌱 智農派工官方助理 - 服務導覽");

        Map<String, Object> contents = new LinkedHashMap<>();
        contents.put("type", "bubble");

        Map<String, Object> header = new LinkedHashMap<>();
        header.put("type", "box");
        header.put("layout", "vertical");
        header.put("backgroundColor", "#047857");
        header.put("paddingAll", "16px");
        header.put("contents", List.of(
                createLabel("智農派工 AgriDispatch", "#D1FAE5", "xs", "bold"),
                createLabel("歡迎使用 LINE 官方派遣助理！", "#FFFFFF", "md", "bold")
        ));
        contents.put("header", header);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "box");
        body.put("layout", "vertical");
        body.put("spacing", "sm");
        body.put("paddingAll", "16px");
        body.put("contents", List.of(
                createLabel("您可以輸入關鍵字或點擊下方選單：", "#475569", "xs", "regular"),
                createLabel("• 輸入「派工」：查看今日出工或訂單", "#1E293B", "sm", "bold"),
                createLabel("• 輸入「避險」：查看突發暴雨冷鏈避險狀態", "#1E293B", "sm", "bold"),
                createLabel("• 輸入「早鳥」：試算產季早鳥保證工時包", "#1E293B", "sm", "bold"),
                createLabel("• 輸入「vietnam」或「indonesia」：切換多語系", "#1E293B", "sm", "bold")
        ));
        contents.put("body", body);

        flex.put("contents", contents);
        return flex;
    }

    /**
     * 12. 模擬推播發送 (供前端控制台與後台管理調用)
     */
    public Map<String, Object> simulatePush(String targetRole, String messageType, String customMsg) {
        Map<String, Object> res = new LinkedHashMap<>();
        List<Map<String, Object>> targets = new ArrayList<>();

        for (Map<String, Object> u : db.getLineUsers()) {
            String r = (String) u.get("role");
            if ("all".equals(targetRole) || targetRole.equals(r)) {
                targets.add(u);
            }
        }

        Map<String, Object> flex;
        if ("weather_alert".equals(messageType)) {
            flex = buildWeatherHedgeFlex();
        } else if ("daily_task".equals(messageType)) {
            flex = buildWorkerTaskFlex("zh", db.isRainMode());
        } else if ("early_bird".equals(messageType)) {
            flex = buildEarlyBirdFlex();
        } else if ("salary_bonus".equals(messageType)) {
            flex = buildSalaryBonusFlex("zh");
        } else {
            flex = buildTextReply(customMsg != null ? customMsg : "智農派工即時系統通報");
        }

        // 記錄推播歷程
        Map<String, Object> log = new LinkedHashMap<>();
        log.put("sender", "SYSTEM_BROADCAST");
        log.put("targetRole", targetRole);
        log.put("recipientCount", targets.size());
        log.put("messageType", messageType);
        log.put("content", flex.get("altText"));
        db.addLineMessage(log);

        res.put("success", true);
        res.put("sentCount", targets.size());
        res.put("recipients", targets);
        res.put("flexMessage", flex);
        return res;
    }

    // Helper 工具方法
    private Map<String, Object> createLabel(String text, String color, String size, String weight) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "text");
        m.put("text", text);
        m.put("color", color);
        m.put("size", size);
        m.put("weight", weight);
        m.put("wrap", true);
        return m;
    }

    private Map<String, Object> createRowKV(String key, String value) {
        Map<String, Object> box = new LinkedHashMap<>();
        box.put("type", "box");
        box.put("layout", "baseline");
        box.put("spacing", "sm");

        Map<String, Object> k = createLabel(key, "#64748B", "xs", "regular");
        k.put("flex", 2);

        Map<String, Object> v = createLabel(value, "#0F172A", "xs", "bold");
        v.put("flex", 5);

        box.put("contents", List.of(k, v));
        return box;
    }

    private Map<String, Object> createButton(String label, String uri, String color) {
        Map<String, Object> btn = new LinkedHashMap<>();
        btn.put("type", "button");
        btn.put("style", "primary");
        btn.put("color", color);
        btn.put("height", "sm");

        Map<String, Object> action = new LinkedHashMap<>();
        action.put("type", "uri");
        action.put("label", label);
        action.put("uri", uri);
        btn.put("action", action);
        return btn;
    }

    private Map<String, Object> buildTextReply(String text) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "text");
        m.put("text", text);
        m.put("altText", text);
        return m;
    }
}
