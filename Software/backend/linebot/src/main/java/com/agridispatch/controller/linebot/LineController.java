package com.agridispatch.controller.linebot;

import com.agridispatch.entity.Order;
import com.agridispatch.entity.Worker;
import com.agridispatch.entity.Crop;
import com.agridispatch.repository.OrderRepository;
import com.agridispatch.repository.WorkerRepository;
import com.agridispatch.repository.CropRepository;
import com.agridispatch.common.LaborConstants;
import com.agridispatch.config.AgriSettings;
import com.agridispatch.service.CalendarService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.*;

/**
 * 智農派工 (AgriDispatch) - LINE Bot Webhook 與智慧查詢控制器
 * 提供訂單進度、全台產曆收穫雷達、在籍工班與服務費率之即時查詢與 Flex Message 生成
 */
@RestController
@RequestMapping("/api/line")
public class LineController {

    private static final Logger log = LoggerFactory.getLogger(LineController.class);

    private final OrderRepository orderRepository;
    private final WorkerRepository workerRepository;
    private final CropRepository cropRepository;
    private final CalendarService calendarService;
    private final AgriSettings settings;
    private final ObjectMapper objectMapper;

    /** LINE Channel Secret / Access Token：由 agri.line.* 或環境變數 LINE_CHANNEL_SECRET / LINE_CHANNEL_ACCESS_TOKEN 提供，預設空值 */
    private volatile String channelSecret;
    private volatile String channelAccessToken;

    public LineController(OrderRepository orderRepository,
                          WorkerRepository workerRepository,
                          CropRepository cropRepository,
                          CalendarService calendarService,
                          AgriSettings settings,
                          ObjectMapper objectMapper,
                          @Value("${agri.line.channel-secret:}") String channelSecret,
                          @Value("${agri.line.channel-access-token:}") String channelAccessToken) {
        this.orderRepository = orderRepository;
        this.workerRepository = workerRepository;
        this.cropRepository = cropRepository;
        this.calendarService = calendarService;
        this.settings = settings;
        this.objectMapper = objectMapper;
        this.channelSecret = channelSecret == null ? "" : channelSecret.trim();
        this.channelAccessToken = channelAccessToken == null ? "" : channelAccessToken.trim();
    }

    /** 未設定 channel secret → 本地模擬（MOCK）模式（僅 demo 模式可處理 webhook） */
    private boolean isMockMode() {
        return channelSecret.isEmpty();
    }

    /**
     * 驗證 X-Line-Signature：Base64(HMAC-SHA256(channelSecret, rawBody))，以 constant-time 比對
     */
    static boolean verifySignature(String secret, byte[] rawBody, String signatureHeader) {
        if (secret == null || secret.isEmpty() || signatureHeader == null || signatureHeader.isBlank()) return false;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] expected = Base64.getEncoder().encode(mac.doFinal(rawBody));
            return MessageDigest.isEqual(expected, signatureHeader.trim().getBytes(StandardCharsets.US_ASCII));
        } catch (Exception e) {
            return false;
        }
    }

    @PostMapping("/webhook")
    @SuppressWarnings("unchecked")
    public ResponseEntity<?> handleWebhook(@RequestBody(required = false) byte[] rawBody,
                                           @RequestHeader(value = "X-Line-Signature", required = false) String signature) {
        byte[] bodyBytes = rawBody == null ? new byte[0] : rawBody;
        if (isMockMode()) {
            if (!settings.isDemoEnabled()) {
                // production 未設定 channel secret：無法驗證簽章，一律拒絕
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                        .body(Map.of("success", false, "error", "LINE channel secret 未設定"));
            }
            // demo 模式且未設定金鑰：本地模擬，不驗證簽章
        } else if (!verifySignature(channelSecret, bodyBytes, signature)) {
            log.warn("[LINE] X-Line-Signature 驗證失敗，拒絕 webhook 請求");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("success", false, "error", "INVALID_SIGNATURE"));
        }

        Map<String, Object> body;
        try {
            body = bodyBytes.length == 0 ? Map.of() : objectMapper.readValue(bodyBytes, Map.class);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", "請求內容格式錯誤"));
        }
        List<Map<String, Object>> replies = new ArrayList<>();

        if (body.containsKey("events") && body.get("events") instanceof List) {
            List<?> events = (List<?>) body.get("events");
            for (Object eventObj : events) {
                if (eventObj instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> event = (Map<String, Object>) eventObj;
                    String userId = extractUserId(event);
                    
                    String text = "";
                    if (event.containsKey("message") && event.get("message") instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> message = (Map<String, Object>) event.get("message");
                        text = message.getOrDefault("text", "").toString();
                    }

                    Map<String, Object> reply = processQueryOrCommand(userId, text);
                    replies.add(reply);
                }
            }
        }

        if (replies.isEmpty()) {
            replies.add(buildQueryHubFlex("zh"));
        }

        return ResponseEntity.ok(Map.of("success", true, "replies", replies));
    }

    /** LINE 正式事件的 userId 位於 source.userId；相容舊版模擬格式 event.userId */
    @SuppressWarnings("unchecked")
    private static String extractUserId(Map<String, Object> event) {
        Object source = event.get("source");
        if (source instanceof Map && ((Map<String, Object>) source).get("userId") != null) {
            return ((Map<String, Object>) source).get("userId").toString();
        }
        Object uid = event.get("userId");
        return uid != null ? uid.toString() : "ANONYMOUS";
    }

    @PostMapping("/simulate-push")
    public ResponseEntity<?> simulatePush(@RequestBody Map<String, Object> req) {
        String type = req.getOrDefault("type", "daily_task").toString();
        Map<String, Object> flex;

        switch (type) {
            case "weather_alert":
                flex = buildWeatherAlertFlex();
                break;
            case "early_bird":
                flex = buildEarlyBirdFlex();
                break;
            case "salary_bonus":
                flex = buildSalaryBonusFlex();
                break;
            case "order_query":
                flex = buildOrderQueryFlex("ORD-2026-001");
                break;
            case "calendar_query":
                flex = buildCalendarQueryFlex("all");
                break;
            default:
                flex = buildFarmerOrderFlex();
                break;
        }

        return ResponseEntity.ok(Map.of("success", true, "flexMessage", flex));
    }

    /** 僅 admin_tech（AuthInterceptor 檢查）；不回傳明文金鑰 */
    @GetMapping("/config")
    public ResponseEntity<?> getConfig() {
        boolean isMock = isMockMode();
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("success", true);
        res.put("status", "UP");
        res.put("mode", isMock ? "MOCK" : "PRODUCTION");
        res.put("channelSecretConfigured", !channelSecret.isEmpty());
        res.put("channelAccessTokenConfigured", !channelAccessToken.isEmpty());
        res.put("channelSecretMasked", channelSecret.isEmpty() ? "" : "********");
        res.put("channelAccessTokenMasked", channelAccessToken.isEmpty() ? "" : "********");
        res.put("webhookPath", "/api/line/webhook");
        return ResponseEntity.ok(res);
    }

    /** 僅 admin_tech：執行期更新金鑰（僅存記憶體，重啟後以環境變數為準） */
    @PostMapping("/config")
    public ResponseEntity<?> updateConfig(@RequestBody Map<String, String> cfg) {
        String secret = cfg.get("channelSecret");
        String token = cfg.get("channelAccessToken");
        if (secret != null && !secret.isBlank()) {
            if (secret.length() > 256) throw new IllegalArgumentException("channelSecret 過長");
            this.channelSecret = secret.trim();
        }
        if (token != null && !token.isBlank()) {
            if (token.length() > 1024) throw new IllegalArgumentException("channelAccessToken 過長");
            this.channelAccessToken = token.trim();
        }
        boolean isMock = isMockMode();
        return ResponseEntity.ok(Map.of(
                "success", true,
                "mode", isMock ? "MOCK" : "PRODUCTION",
                "message", "LINE 設定已更新，目前模式: " + (isMock ? "【本地模擬】" : "【正式生產】")
        ));
    }

    // =========================================================================
    // 智慧查詢路由引擎
    // =========================================================================

    private Map<String, Object> processQueryOrCommand(String userId, String rawText) {
        String clean = rawText == null ? "" : rawText.trim().toLowerCase();

        // 1. 訂單查詢
        if (clean.contains("查訂單") || clean.contains("訂單") || clean.contains("工單") || clean.contains("ord-")
                || clean.contains("單號") || clean.contains("派工單") || clean.contains("進度")) {
            return buildOrderQueryFlex(clean);
        }

        // 2. 全台產曆與收穫倒數查詢
        if (clean.contains("查產曆") || clean.contains("產曆") || clean.contains("產季") || clean.contains("收穫")
                || clean.contains("採收") || clean.contains("芒果") || clean.contains("洋蔥") || clean.contains("鳳梨")
                || clean.contains("西瓜") || clean.contains("青蔥") || clean.contains("茶")) {
            return buildCalendarQueryFlex(clean);
        }

        // 3. 工班與農工名冊查詢
        if (clean.contains("查工班") || clean.contains("工班") || clean.contains("農工") || clean.contains("外展")
                || clean.contains("移工") || clean.contains("名冊") || clean.contains("司機")) {
            return buildCrewQueryFlex(clean);
        }

        // 4. 服務費率與試算查詢
        if (clean.contains("查費率") || clean.contains("費率") || clean.contains("價格") || clean.contains("多少錢")
                || clean.contains("計費") || clean.contains("早鳥") || clean.contains("加乘")) {
            return buildRateQueryFlex();
        }

        // 5. 天候避險查詢
        if (clean.contains("避險") || clean.contains("暴雨") || clean.contains("下雨") || clean.contains("冷鏈")) {
            return buildWeatherAlertFlex();
        }

        // 6. 機耕與無人機代噴
        if (clean.contains("機耕") || clean.contains("代噴") || clean.contains("無人機") || clean.contains("曳引機")) {
            return buildMachineryTaskFlex();
        }

        // 7. 多語工單切換
        if (clean.contains("越南") || clean.contains("viet") || clean.contains("tiếng việt")) {
            return buildTextReply("✅ Đã chuyển đổi sang Tiếng Việt. Gõ 'nhiệm vụ' hoặc 'tra cứu' để xem thông tin điều phái!");
        } else if (clean.contains("印尼") || clean.contains("indo") || clean.contains("bahasa")) {
            return buildTextReply("✅ Bahasa telah diubah ke Bahasa Indonesia. Ketik 'tugas' atau 'cari' untuk melihat info tugas!");
        }

        // 預設：查詢大廳功能導覽
        return buildQueryHubFlex("zh");
    }

    // =========================================================================
    // Flex Message Builders (高擬真 LINE 氣泡卡片)
    // =========================================================================

    /**
     * 查詢大廳總導覽卡片
     */
    private Map<String, Object> buildQueryHubFlex(String lang) {
        Map<String, Object> flex = new LinkedHashMap<>();
        flex.put("type", "flex");
        flex.put("altText", "🔍 智農派工 - 智慧農務查詢中心");

        Map<String, Object> contents = new LinkedHashMap<>();
        contents.put("type", "bubble");
        contents.put("size", "mega");

        // Header
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("type", "box");
        header.put("layout", "vertical");
        header.put("backgroundColor", "#047857");
        header.put("paddingAll", "20px");

        List<Map<String, Object>> hItems = new ArrayList<>();
        hItems.add(createLabel("智農派工・LINE 智慧查詢中心", "#A7F3D0", "xs", "bold"));
        hItems.add(createLabel("農務查詢快捷大廳", "#FFFFFF", "xl", "bold"));
        hItems.add(createLabel("支援即時查單 ╳ 產曆收穫雷達 ╳ 工班戰情 ╳ 標準費率", "#D1FAE5", "xs", "regular"));
        header.put("contents", hItems);
        contents.put("header", header);

        // Body
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "box");
        body.put("layout", "vertical");
        body.put("spacing", "md");
        body.put("paddingAll", "20px");

        List<Map<String, Object>> bItems = new ArrayList<>();
        bItems.add(createLabel("請點選下方快捷按鈕，或直接在對話框輸入關鍵字查詢：", "#475569", "xs", "regular"));
        bItems.add(createRowKV("📋 訂單進度", "輸入「查訂單」或「ORD-2026-001」"));
        bItems.add(createRowKV("📅 全台產曆", "輸入「查產曆」、「芒果產季」、「洋蔥」"));
        bItems.add(createRowKV("👷 工班人力", "輸入「查工班」、「工班B組」、「外展移工」"));
        bItems.add(createRowKV("💰 服務費率", "輸入「查費率」、「早鳥9折」、「防颱搶收」"));
        body.put("contents", bItems);
        contents.put("body", body);

        // Footer Actions
        Map<String, Object> footer = new LinkedHashMap<>();
        footer.put("type", "box");
        footer.put("layout", "vertical");
        footer.put("spacing", "sm");
        footer.put("paddingAll", "16px");

        List<Map<String, Object>> fItems = new ArrayList<>();
        fItems.add(createButton("📋 立即查詢派工單進度", "https://agridispatch.tw/track/ORD-2026-001", "#059669"));
        fItems.add(createButton("📅 查詢全台產曆與收穫倒數", "https://agridispatch.tw/web/index.html#calendar", "#3B82F6"));
        fItems.add(createButton("💰 查詢早鳥與派工費率", "https://agridispatch.tw/web/farmer-login.html", "#D97706"));
        footer.put("contents", fItems);
        contents.put("footer", footer);

        flex.put("contents", contents);
        return flex;
    }

    /**
     * 1. 訂單進度查詢結果卡片
     */
    private Map<String, Object> buildOrderQueryFlex(String query) {
        List<Order> orders = orderRepository.findAll();
        Order target = orders.isEmpty() ? null : orders.get(0);
        for (Order o : orders) {
            if (query.contains(o.getId().toLowerCase()) || (o.getCropName() != null && query.contains(o.getCropName()))) {
                target = o;
                break;
            }
        }

        Map<String, Object> flex = new LinkedHashMap<>();
        flex.put("type", "flex");
        flex.put("altText", "📋 派工訂單查詢結果 - " + (target != null ? target.getId() : "ORD-2026-001"));

        Map<String, Object> contents = new LinkedHashMap<>();
        contents.put("type", "bubble");
        contents.put("size", "mega");

        // Header
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("type", "box");
        header.put("layout", "vertical");
        header.put("backgroundColor", "#047857");
        header.put("paddingAll", "20px");

        String orderId = target != null ? target.getId() : "ORD-2026-001";
        String cropName = target != null ? target.getCropName() : "枋山愛文芒果 (6.0 分地)";
        String status = target != null ? target.getStatus() : "已派工 (出勤中)";
        String foreman = (target != null && target.getForemanName() != null && !target.getForemanName().isBlank())
                ? target.getForemanName() + " (" + target.getForemanPhone() + ")"
                : "陳建龍 (農場主本人帶隊 0921-999-888)";
        String catName = target != null && target.getCategoryName() != null ? target.getCategoryName() : "採收與初級分級";
        double total = target != null ? target.getFinalAmount() : 45360.0;

        List<Map<String, Object>> hItems = new ArrayList<>();
        hItems.add(createLabel("訂單查詢結果・即時履約戰情", "#A7F3D0", "xs", "bold"));
        hItems.add(createLabel(orderId + " ｜ " + status, "#FFFFFF", "xl", "bold"));
        hItems.add(createLabel("作物：" + cropName + " ｜ 類別：" + catName, "#D1FAE5", "xs", "regular"));
        header.put("contents", hItems);
        contents.put("header", header);

        // Body
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "box");
        body.put("layout", "vertical");
        body.put("spacing", "md");
        body.put("paddingAll", "20px");

        List<Map<String, Object>> bItems = new ArrayList<>();
        bItems.add(createRowKV("指派工班", "工班 B 組 (6人，領班: 阿迪 Agus)"));
        bItems.add(createRowKV("接送專車", "智農 2 號車 (司機: 李大哥 0912-222-444)"));
        bItems.add(createRowKV("現場小工頭", foreman));
        bItems.add(createRowKV("作業手路", "留蒂 0.5cm 護果粉採收 / 完整驗收標準"));
        bItems.add(createRowKV("合約保障", "SLA 遲到免單、放鳥雙倍賠償、100% 納保"));
        bItems.add(createRowKV("總計金額", "NT$ " + String.format("%,.0f", total) + " (定金已收 20%)"));
        body.put("contents", bItems);
        contents.put("body", body);

        // Footer
        Map<String, Object> footer = new LinkedHashMap<>();
        footer.put("type", "box");
        footer.put("layout", "vertical");
        footer.put("spacing", "sm");
        footer.put("paddingAll", "16px");

        List<Map<String, Object>> fItems = new ArrayList<>();
        fItems.add(createButton("📍 查看工班 GPS 即時座標", "https://agridispatch.tw/track/" + orderId, "#059669"));
        fItems.add(createButton("📞 撥打現場專車司機電話", "tel:0912222444", "#3B82F6"));
        footer.put("contents", fItems);
        contents.put("footer", footer);

        flex.put("contents", contents);
        return flex;
    }

    /**
     * 2. 全台產曆與收穫日查詢結果卡片
     */
    private Map<String, Object> buildCalendarQueryFlex(String query) {
        Map<String, Object> flex = new LinkedHashMap<>();
        flex.put("type", "flex");
        flex.put("altText", "📅 全台農作物產曆與收穫倒數查詢結果");

        Map<String, Object> contents = new LinkedHashMap<>();
        contents.put("type", "bubble");
        contents.put("size", "mega");

        LocalDate now = LocalDate.now();
        int curMonth = now.getMonthValue();
        int curQuarter = (curMonth - 1) / 3 + 1;

        // Header
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("type", "box");
        header.put("layout", "vertical");
        header.put("backgroundColor", "#1E3A8A");
        header.put("paddingAll", "20px");

        List<Map<String, Object>> hItems = new ArrayList<>();
        hItems.add(createLabel("全台 52 週農作物產曆・收穫倒數雷達", "#93C5FD", "xs", "bold"));
        hItems.add(createLabel(String.format("%d 第 %d 季 (%d~%d月) 產季概況", now.getYear(), curQuarter, (curQuarter-1)*3+1, curQuarter*3), "#FFFFFF", "xl", "bold"));
        hItems.add(createLabel(String.format("📍 現實時間：%d年%d月%d日 (即時聯網調度)", now.getYear(), now.getMonthValue(), now.getDayOfMonth()), "#BFDBFE", "xs", "regular"));
        header.put("contents", hItems);
        contents.put("header", header);

        // Body
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "box");
        body.put("layout", "vertical");
        body.put("spacing", "md");
        body.put("paddingAll", "20px");

        List<Map<String, Object>> bItems = new ArrayList<>();
        bItems.add(createLabel("🔥 近期關鍵作物採收日倒數：", "#1E293B", "xs", "bold"));

        List<Map<String, Object>> upcoming = calendarService.getUpcomingHarvests(null, null);
        int count = 0;
        for (Map<String, Object> h : upcoming) {
            if (count++ >= 4) break;
            String name = (String) h.get("cropName");
            int countdown = (int) h.get("countdownDays");
            String window = (String) h.get("harvestWindow");
            String status = (String) h.get("statusLabel");
            bItems.add(createRowKV(name, String.format("倒數 %d 天 (%s・%s)", countdown, window, status)));
        }

        bItems.add(createRowKV("出工稼動率", "96.8% (特許外展與本籍機耕雙軌調度)"));
        bItems.add(createRowKV("調度建議", "目前為產季調度關鍵期，建議提前鎖定工班"));
        body.put("contents", bItems);
        contents.put("body", body);

        // Footer
        Map<String, Object> footer = new LinkedHashMap<>();
        footer.put("type", "box");
        footer.put("layout", "vertical");
        footer.put("spacing", "sm");
        footer.put("paddingAll", "16px");

        List<Map<String, Object>> fItems = new ArrayList<>();
        fItems.add(createButton("⚡ 預購產季早鳥保證工時包 (9折)", "https://agridispatch.tw/web/farmer.html", "#D97706"));
        fItems.add(createButton("🗓️ 瀏覽 52 週完整產曆動態輪播", "https://agridispatch.tw/web/index.html#calendar", "#2563EB"));
        footer.put("contents", fItems);
        contents.put("footer", footer);

        flex.put("contents", contents);
        return flex;
    }

    /**
     * 3. 在籍工班與外展農工查詢結果卡片
     */
    private Map<String, Object> buildCrewQueryFlex(String query) {
        List<Worker> workers = workerRepository.findAll();
        int totalWorkers = workers.isEmpty() ? 25 : workers.size();

        Map<String, Object> flex = new LinkedHashMap<>();
        flex.put("type", "flex");
        flex.put("altText", "👷 智農派工 - 在籍工班與人力戰情查詢");

        Map<String, Object> contents = new LinkedHashMap<>();
        contents.put("type", "bubble");
        contents.put("size", "mega");

        // Header
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("type", "box");
        header.put("layout", "vertical");
        header.put("backgroundColor", "#312E81");
        header.put("paddingAll", "20px");

        List<Map<String, Object>> hItems = new ArrayList<>();
        hItems.add(createLabel("在籍特許外展工班・即時人力戰情", "#C7D2FE", "xs", "bold"));
        hItems.add(createLabel("在籍人員 " + totalWorkers + " 名 (100% 納保合規)", "#FFFFFF", "xl", "bold"));
        hItems.add(createLabel("特許外展移工 20 名 ╳ 本國機耕師與兼職 5 名", "#E0E7FF", "xs", "regular"));
        header.put("contents", hItems);
        contents.put("header", header);

        // Body
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "box");
        body.put("layout", "vertical");
        body.put("spacing", "md");
        body.put("paddingAll", "20px");

        List<Map<String, Object>> bItems = new ArrayList<>();
        bItems.add(createRowKV("工班 A 組 (5人)", "桃園設施溫室組・智農 1 號車 (張大哥)"));
        bItems.add(createRowKV("工班 B 組 (6人)", "枋山芒果專線・領班阿迪・智農 2 號車"));
        bItems.add(createRowKV("工班 C 組 (4人)", "雲林機耕無人機隊・陳建宏雙證照飛手"));
        bItems.add(createRowKV("工班 D 組 (5人)", "屏南冷鏈預冷分級・范氏紅冷鏈專線"));
        bItems.add(createRowKV("今日出勤率", "100% (160 工時全滿檔・零閒置)"));
        body.put("contents", bItems);
        contents.put("body", body);

        // Footer
        Map<String, Object> footer = new LinkedHashMap<>();
        footer.put("type", "box");
        footer.put("layout", "vertical");
        footer.put("spacing", "sm");
        footer.put("paddingAll", "16px");

        List<Map<String, Object>> fItems = new ArrayList<>();
        fItems.add(createButton("📊 開啟後台農工名冊總台", "https://agridispatch.tw/web/admin.html", "#4F46E5"));
        fItems.add(createButton("📑 主管機關送審名冊備查", "https://agridispatch.tw/api/workers/export-csv", "#059669"));
        footer.put("contents", fItems);
        contents.put("footer", footer);

        flex.put("contents", contents);
        return flex;
    }

    /**
     * 4. 服務費率與工時標準查詢結果卡片
     */
    private Map<String, Object> buildRateQueryFlex() {
        Map<String, Object> flex = new LinkedHashMap<>();
        flex.put("type", "flex");
        flex.put("altText", "💰 智農派工 - 標準費率與早鳥工時試算查詢");

        Map<String, Object> contents = new LinkedHashMap<>();
        contents.put("type", "bubble");
        contents.put("size", "mega");

        // Header
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("type", "box");
        header.put("layout", "vertical");
        header.put("backgroundColor", "#78350F");
        header.put("paddingAll", "20px");

        List<Map<String, Object>> hItems = new ArrayList<>();
        hItems.add(createLabel("透明收費標準・合作社法定平移成本", "#FDE68A", "xs", "bold"));
        hItems.add(createLabel("2026 年度派工費率標準表", "#FFFFFF", "xl", "bold"));
        hItems.add(createLabel("合約保障・合法抵稅發票・遲到免單放鳥雙倍賠", "#FEF3C7", "xs", "regular"));
        header.put("contents", hItems);
        contents.put("header", header);

        // Body
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "box");
        body.put("layout", "vertical");
        body.put("spacing", "md");
        body.put("paddingAll", "20px");

        List<Map<String, Object>> bItems = new ArrayList<>();
        bItems.add(createRowKV("田間標準採收", "NT$ 300 / 小時 (基本工時)"));
        bItems.add(createRowKV("產季早鳥保證包", "NT$ 270 / 小時 (享 9 折，定金 20%)"));
        bItems.add(createRowKV("防颱搶收馳援", "NT$ 360 / 小時 (24H 優先調度加乘)"));
        bItems.add(createRowKV("大型曳引機整地", "NT$ 310 / 小時 (80hp 機具另計)"));
        bItems.add(createRowKV("無人機精準代噴", "NT$ 250 / 分地 (大疆 T50 植保機)"));
        bItems.add(createRowKV("暴雨避險條款", "室內冷鏈預冷分級場替代，免停工損失"));
        body.put("contents", bItems);
        contents.put("body", body);

        // Footer
        Map<String, Object> footer = new LinkedHashMap<>();
        footer.put("type", "box");
        footer.put("layout", "vertical");
        footer.put("spacing", "sm");
        footer.put("paddingAll", "16px");

        List<Map<String, Object>> fItems = new ArrayList<>();
        fItems.add(createButton("⚡ 立即線上派工試算 (農場主專區)", "https://agridispatch.tw/web/farmer-login.html", "#D97706"));
        fItems.add(createButton("📞 預約產銷班長專屬派工熱線", "tel:0800888999", "#059669"));
        footer.put("contents", fItems);
        contents.put("footer", footer);

        flex.put("contents", contents);
        return flex;
    }

    private Map<String, Object> buildFarmerOrderFlex() {
        return buildOrderQueryFlex("ORD-2026-001");
    }

    private Map<String, Object> buildWeatherAlertFlex() {
        Map<String, Object> flex = new LinkedHashMap<>();
        flex.put("type", "flex");
        flex.put("altText", "🌧️ 突發午後暴雨・冷鏈避險緊急通知");

        Map<String, Object> contents = new LinkedHashMap<>();
        contents.put("type", "bubble");
        contents.put("size", "mega");

        Map<String, Object> header = new LinkedHashMap<>();
        header.put("type", "box");
        header.put("layout", "vertical");
        header.put("backgroundColor", "#1E3A8A");
        header.put("paddingAll", "20px");

        List<Map<String, Object>> hItems = new ArrayList<>();
        hItems.add(createLabel("🌧️ 突發暴雨避險機制啟動", "#93C5FD", "xs", "bold"));
        hItems.add(createLabel("全體專車轉移至冷鏈集貨場", "#FFFFFF", "xl", "bold"));
        hItems.add(createLabel("屏東枋山/高樹午後強降雨・演算法避險大腦運作", "#BFDBFE", "xs", "regular"));
        header.put("contents", hItems);
        contents.put("header", header);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "box");
        body.put("layout", "vertical");
        body.put("spacing", "md");
        body.put("paddingAll", "20px");

        List<Map<String, Object>> bItems = new ArrayList<>();
        bItems.add(createRowKV("轉移地點", "🏢 屏南冷鏈預冷分級場 (B棟 室內分級線)"));
        bItems.add(createRowKV("作業內容", "芒果低溫預冷、外銷秤重、洋蔥乾燥裝箱"));
        bItems.add(createRowKV("接駁專車", "智農 1、2 號車已抵達各田區載運工班"));
        bItems.add(createRowKV("出勤保全", "100% 正常出勤，零工時浪費，保全 $62,500 產值"));
        body.put("contents", bItems);
        contents.put("body", body);

        Map<String, Object> footer = new LinkedHashMap<>();
        footer.put("type", "box");
        footer.put("layout", "vertical");
        footer.put("spacing", "sm");
        footer.put("paddingAll", "16px");

        List<Map<String, Object>> fItems = new ArrayList<>();
        fItems.add(createButton("📍 查看冷鏈接送專車即時車隊", "https://agridispatch.tw/track/fleet", "#2563EB"));
        footer.put("contents", fItems);
        contents.put("footer", footer);

        flex.put("contents", contents);
        return flex;
    }

    private Map<String, Object> buildEarlyBirdFlex() {
        return buildRateQueryFlex();
    }

    private Map<String, Object> buildSalaryBonusFlex() {
        Map<String, Object> flex = new LinkedHashMap<>();
        flex.put("type", "flex");
        flex.put("altText", "💰 農業部就業獎勵津貼入帳通知");

        Map<String, Object> contents = new LinkedHashMap<>();
        contents.put("type", "bubble");
        contents.put("size", "mega");

        Map<String, Object> header = new LinkedHashMap<>();
        header.put("type", "box");
        header.put("layout", "vertical");
        header.put("backgroundColor", "#581C87");
        header.put("paddingAll", "20px");

        List<Map<String, Object>> hItems = new ArrayList<>();
        hItems.add(createLabel("農業部特許外展農工・政府津貼公示", "#E9D5FF", "xs", "bold"));
        // TODO 待農業部確認適用對象（就業獎勵金）
        hItems.add(createLabel("就業獎勵金 NT$ " + LaborConstants.formatMoney(LaborConstants.AGRI_EMPLOYMENT_BONUS) + " 已入帳", "#FFFFFF", "xl", "bold"));
        hItems.add(createLabel("工號: W001 阮文雄 ｜ 2026 年 5 月份津貼", "#F3E8FF", "xs", "regular"));
        header.put("contents", hItems);
        contents.put("header", header);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "box");
        body.put("layout", "vertical");
        body.put("spacing", "md");
        body.put("paddingAll", "20px");

        List<Map<String, Object>> bItems = new ArrayList<>();
        bItems.add(createRowKV("基本薪資", "NT$ " + LaborConstants.formatMoney(LaborConstants.MIN_MONTHLY_WAGE) + " (每月 5 日合作社入帳)"));
        bItems.add(createRowKV("就業獎勵金", "NT$ " + LaborConstants.formatMoney(LaborConstants.AGRI_EMPLOYMENT_BONUS) + " (農業部外展留台補助)"));
        bItems.add(createRowKV("合計入帳", "NT$ " + LaborConstants.formatMoney(LaborConstants.MIN_MONTHLY_WAGE + LaborConstants.AGRI_EMPLOYMENT_BONUS) + " (合法特許・全額勞健保)"));
        body.put("contents", bItems);
        contents.put("body", body);

        flex.put("contents", contents);
        return flex;
    }

    private Map<String, Object> buildMachineryTaskFlex() {
        Map<String, Object> flex = new LinkedHashMap<>();
        flex.put("type", "flex");
        flex.put("altText", "🚜 本國專業機耕隊與無人機植保工單");

        Map<String, Object> contents = new LinkedHashMap<>();
        contents.put("type", "bubble");
        contents.put("size", "mega");

        Map<String, Object> header = new LinkedHashMap<>();
        header.put("type", "box");
        header.put("layout", "vertical");
        header.put("backgroundColor", "#1E293B");
        header.put("paddingAll", "20px");

        List<Map<String, Object>> hItems = new ArrayList<>();
        hItems.add(createLabel("本國高階機耕技師・派工單", "#94A3B8", "xs", "bold"));
        hItems.add(createLabel("無人機植保 ╳ 曳引機整地", "#FFFFFF", "xl", "bold"));
        hItems.add(createLabel("機耕師：陳建宏 (雙證照) ｜ 雲林設施甜椒園區", "#CBD5E1", "xs", "regular"));
        header.put("contents", hItems);
        contents.put("header", header);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "box");
        body.put("layout", "vertical");
        body.put("spacing", "md");
        body.put("paddingAll", "20px");

        List<Map<String, Object>> bItems = new ArrayList<>();
        bItems.add(createRowKV("調派機具", "大疆 Agras T50 (50L 植保無人機)"));
        bItems.add(createRowKV("作業面積", "8.5 分地 (預計 45 分鐘完成代噴)"));
        bItems.add(createRowKV("計費標準", "NT$ 250 / 分地 + 時薪 NT$ 310"));
        body.put("contents", bItems);
        contents.put("body", body);

        flex.put("contents", contents);
        return flex;
    }

    private Map<String, Object> buildTextReply(String text) {
        Map<String, Object> reply = new LinkedHashMap<>();
        reply.put("type", "text");
        reply.put("text", text);
        return reply;
    }

    private Map<String, Object> createLabel(String text, String color, String size, String weight) {
        Map<String, Object> label = new LinkedHashMap<>();
        label.put("type", "text");
        label.put("text", text);
        label.put("color", color);
        label.put("size", size);
        label.put("weight", weight);
        return label;
    }

    private Map<String, Object> createRowKV(String key, String val) {
        Map<String, Object> box = new LinkedHashMap<>();
        box.put("type", "box");
        box.put("layout", "baseline");
        box.put("spacing", "sm");

        List<Map<String, Object>> contents = new ArrayList<>();
        Map<String, Object> k = new LinkedHashMap<>();
        k.put("type", "text");
        k.put("text", key);
        k.put("color", "#64748B");
        k.put("size", "xs");
        k.put("flex", 2);

        Map<String, Object> v = new LinkedHashMap<>();
        v.put("type", "text");
        v.put("text", val);
        v.put("color", "#0F172A");
        v.put("size", "xs");
        v.put("weight", "bold");
        v.put("flex", 4);

        contents.add(k);
        contents.add(v);
        box.put("contents", contents);
        return box;
    }

    private Map<String, Object> createButton(String label, String uri, String color) {
        Map<String, Object> btn = new LinkedHashMap<>();
        btn.put("type", "button");
        btn.put("style", "primary");
        btn.put("color", color);

        Map<String, Object> action = new LinkedHashMap<>();
        action.put("type", "uri");
        action.put("label", label);
        action.put("uri", uri);

        btn.put("action", action);
        return btn;
    }
}

