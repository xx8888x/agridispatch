package com.agridispatch.controller.linebot;

import com.agridispatch.common.ApiException;
import com.agridispatch.entity.LineUser;
import com.agridispatch.entity.Order;
import com.agridispatch.repository.OrderRepository;
import com.agridispatch.security.AuthContext;
import com.agridispatch.common.LaborConstants;
import com.agridispatch.config.AgriSettings;
import com.agridispatch.service.CalendarService;
import com.agridispatch.service.LineBindingService;
import com.agridispatch.service.LineBotClient;
import com.agridispatch.service.LineFarmerQueryService;
import com.agridispatch.service.LineWorkerQueryService;
import com.agridispatch.service.OrderService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.*;

/**
 * 智農派工 (AgriDispatch) - LINE 相關端點：LINE Bot 內部查詢、帳號設定狀態、體驗頁推播卡片
 * （舊版 /webhook 已停用，一律回 410；LINE webhook 由 LINE Bot 服務接收）
 */
@RestController
@RequestMapping("/api/line")
public class LineController {

    private final OrderRepository orderRepository;
    private final CalendarService calendarService;
    private final AgriSettings settings;
    private final LineBindingService lineBindingService;
    private final LineBotClient lineBotClient;
    private final LineWorkerQueryService workerQueryService;
    private final LineFarmerQueryService farmerQueryService;

    /** LINE Channel Secret / Access Token：由 agri.line.* 或環境變數 LINE_CHANNEL_SECRET / LINE_CHANNEL_ACCESS_TOKEN 提供，預設空值 */
    private volatile String channelSecret;
    private volatile String channelAccessToken;

    public LineController(OrderRepository orderRepository,
                          CalendarService calendarService,
                          AgriSettings settings,
                          LineBindingService lineBindingService,
                          LineBotClient lineBotClient,
                          LineWorkerQueryService workerQueryService,
                          LineFarmerQueryService farmerQueryService,
                          @Value("${agri.line.channel-secret:}") String channelSecret,
                          @Value("${agri.line.channel-access-token:}") String channelAccessToken) {
        this.orderRepository = orderRepository;
        this.calendarService = calendarService;
        this.settings = settings;
        this.lineBindingService = lineBindingService;
        this.lineBotClient = lineBotClient;
        this.workerQueryService = workerQueryService;
        this.farmerQueryService = farmerQueryService;
        this.channelSecret = channelSecret == null ? "" : channelSecret.trim();
        this.channelAccessToken = channelAccessToken == null ? "" : channelAccessToken.trim();
    }

    /** 未設定 channel secret → 本地模擬（MOCK）模式（僅供 GET /config 顯示狀態） */
    private boolean isMockMode() {
        return channelSecret.isEmpty();
    }

    /** 已停用端點的固定回應（規格 03 第 5 節） */
    static final Map<String, Object> WEBHOOK_GONE_BODY = Map.of(
            "success", false,
            "error", "GONE",
            "message", "此端點已停用，LINE webhook 改由 LINE Bot 服務（/callback/farmer、/callback/worker）處理");

    /**
     * 舊版 webhook 入口（已停用）：一律回 410 Gone，不讀取 body、不處理訊息、不查訂單。
     * LINE webhook 由 LINE Bot 服務（Software/Line Bot）的 /callback/farmer、/callback/worker 接收，
     * 需要後端資料時改呼叫 /api/line/internal/*（規格 05 第 6.3 節）。
     */
    @PostMapping("/webhook")
    public ResponseEntity<?> handleWebhook() {
        return ResponseEntity.status(HttpStatus.GONE).body(WEBHOOK_GONE_BODY);
    }

    // =========================================================================
    // LINE Bot 內部端點（規格 05 第 6.3 節）：X-Internal-Key 驗證；未設定金鑰時整組停用（403）
    // =========================================================================

    @PostMapping("/internal/identity")
    public ResponseEntity<?> internalIdentity(@RequestHeader(value = "X-Internal-Key", required = false) String key,
                                              @RequestBody Map<String, Object> req) {
        requireInternalKey(key);
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("success", true);
        res.putAll(lineBindingService.identity(str(req.get("channel")), str(req.get("lineUserId"))));
        return ResponseEntity.ok(res);
    }

    @PostMapping("/internal/follow-state")
    public ResponseEntity<?> internalFollowState(@RequestHeader(value = "X-Internal-Key", required = false) String key,
                                                 @RequestBody Map<String, Object> req) {
        requireInternalKey(key);
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("success", true);
        res.putAll(lineBindingService.followState(str(req.get("channel")), str(req.get("lineUserId")),
                bool(req.get("followed")), str(req.get("lang")), bool(req.get("langLocked")), str(req.get("displayName"))));
        return ResponseEntity.ok(res);
    }

    /**
     * 依帳號與身分限制可回答的動作（規格 4.1、4.2）：
     * 農場主帳號 rates、calendar（公開）回 messages（LINE 訊息物件）；orders、weather（限已綁定、只回本人）回 data（見 LineFarmerQueryService）；
     * 工作者帳號 task、weather、salary（限已綁定、只回本人）回 data（見 LineWorkerQueryService）；data 由 LINE Bot 組卡片；
     * 其餘回空清單、不帶 data
     */
    @PostMapping("/internal/query")
    public ResponseEntity<?> internalQuery(@RequestHeader(value = "X-Internal-Key", required = false) String key,
                                           @RequestBody Map<String, Object> req) {
        requireInternalKey(key);
        String channel = str(req.get("channel"));
        if (channel == null || !LineBindingService.CHANNELS.contains(channel)) {
            throw ApiException.badRequest("channel 必須是 farmer 或 worker");
        }
        String action = str(req.get("action"));
        String text = Optional.ofNullable(str(req.get("text"))).orElse("").trim().toLowerCase();
        List<Map<String, Object>> messages = new ArrayList<>();
        if (LineUser.CHANNEL_FARMER.equals(channel) && action != null) {
            switch (action) {
                case "rates" -> messages.add(buildRateQueryFlex());
                case "calendar" -> messages.add(buildCalendarQueryFlex(text));
                default -> { }
            }
        }
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("success", true);
        res.put("messages", messages);
        if (LineUser.CHANNEL_FARMER.equals(channel)) {
            lineBindingService.boundUser(channel, str(req.get("lineUserId")))
                    .filter(u -> AuthContext.ROLE_FARMER.equals(u.getRole()))
                    .flatMap(u -> farmerQueryService.query(u.getRefId(), action, text))
                    .ifPresent(d -> res.put("data", d));
        }
        if (LineUser.CHANNEL_WORKER.equals(channel)) {
            lineBindingService.boundUser(channel, str(req.get("lineUserId")))
                    .filter(u -> AuthContext.ROLE_WORKER_LOCAL.equals(u.getRole()) || AuthContext.ROLE_WORKER_FOREIGN.equals(u.getRole()))
                    .flatMap(u -> workerQueryService.query(u.getRefId(), action))
                    .ifPresent(d -> res.put("data", d));
        }
        return ResponseEntity.ok(res);
    }

    private void requireInternalKey(String provided) {
        String expected = settings.getLinebotInternalKey();
        if (expected == null) {
            throw new ApiException(HttpStatus.FORBIDDEN, "內部端點未啟用：請設定 AGRI_LINEBOT_INTERNAL_KEY");
        }
        if (provided == null || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                provided.trim().getBytes(StandardCharsets.UTF_8))) {
            throw ApiException.forbidden();
        }
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static Boolean bool(Object o) {
        if (o == null) return null;
        if (o instanceof Boolean b) return b;
        return "true".equalsIgnoreCase(String.valueOf(o));
    }

    @PostMapping("/simulate-push")
    public ResponseEntity<?> simulatePush(@RequestBody Map<String, Object> req) {
        // 卡片含真實訂單的小工頭聯絡方式與金額：正式模式僅限 admin（LINE 體驗頁屬路演功能）
        if (!settings.isDemoEnabled()) com.agridispatch.security.AuthContext.require().requireAdmin();
        String type = Objects.toString(req.get("type"), "daily_task");
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
        // LINE 雙帳號（規格 05 第 6.3 節）：channel token 只在 LINE Bot，後端只回設定狀態
        res.put("internalKeyConfigured", settings.getLinebotInternalKey() != null);
        res.put("loginChannelConfigured", settings.getLineLoginChannelId() != null);
        Map<String, Object> bot = lineBotClient.health();
        Map<String, Object> linebot = new LinkedHashMap<>();
        linebot.put("reachable", bot != null);
        if (bot != null) linebot.putAll(bot);
        res.put("linebot", linebot);
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
    // Flex Message Builders (高擬真 LINE 氣泡卡片)
    // =========================================================================

    /**
     * 1. 訂單進度查詢結果卡片
     */
    private Map<String, Object> buildOrderQueryFlex(String query) {
        return buildOrderQueryFlex(query, orderRepository.findAll());
    }

    /** 從指定的訂單中挑出符合單號或作物名稱者（無符合則取第一筆）產生卡片 */
    private Map<String, Object> buildOrderQueryFlex(String query, List<Order> orders) {
        Order target = orders.isEmpty() ? null : orders.get(0);
        for (Order o : orders) {
            if (query.contains(o.getId().toLowerCase()) || (o.getCropName() != null && query.contains(o.getCropName()))) {
                target = o;
                break;
            }
        }

        Map<String, Object> flex = new LinkedHashMap<>();
        flex.put("type", "flex");
        flex.put("altText", "📋 派工訂單查詢結果 - " + (target != null ? target.getId() : "查無訂單"));

        Map<String, Object> contents = new LinkedHashMap<>();
        contents.put("type", "bubble");
        contents.put("size", "mega");

        // Header
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("type", "box");
        header.put("layout", "vertical");
        header.put("backgroundColor", "#047857");
        header.put("paddingAll", "20px");

        // 欄位一律取自訂單（LineFarmerQueryService.detail，與 LINE 農場主帳號 F-01 相同）；查無訂單時只顯示提示
        Map<String, Object> d = target != null ? farmerQueryService.detail(target) : Map.of();
        String orderId = target != null ? target.getId() : "（查無訂單）";
        String period = d.get("startDate") == null ? "待排定"
                : d.get("startDate") + " ～ " + d.get("endDate") + "・" + d.get("timeSlotLabel");
        String foreman = nz(d.get("foremanName"), "待通知") + (d.get("foremanPhone") == null ? "" : " (" + d.get("foremanPhone") + ")");

        List<Map<String, Object>> hItems = new ArrayList<>();
        hItems.add(createLabel("訂單查詢結果", "#A7F3D0", "xs", "bold"));
        hItems.add(createLabel(orderId + (target != null ? " ｜ " + d.get("statusLabel") : ""), "#FFFFFF", "xl", "bold"));
        if (target != null) {
            hItems.add(createLabel("作物：" + nz(d.get("cropName"), "—") + " ｜ 類別：" + nz(d.get("categoryName"), "—"), "#D1FAE5", "xs", "regular"));
        }
        header.put("contents", hItems);
        contents.put("header", header);

        // Body
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "box");
        body.put("layout", "vertical");
        body.put("spacing", "md");
        body.put("paddingAll", "20px");

        List<Map<String, Object>> bItems = new ArrayList<>();
        if (target != null) {
            bItems.add(createRowKV("工期", period));
            bItems.add(createRowKV("指派工班", nz(d.get("teamName"), "待安排") + "（每日 " + d.get("workersPerDay") + " 人）"));
            bItems.add(createRowKV("帶隊對口", foreman));
            if (!nz(d.get("foremanNotes"), "").isEmpty()) bItems.add(createRowKV("現場交辦", nz(d.get("foremanNotes"), "")));
            bItems.add(createRowKV("總計金額", "NT$ " + String.format("%,d", target.getFinalAmount())
                    + (target.isDepositPaid() ? "（定金已付）" : "（定金未付）")));
        } else {
            bItems.add(createLabel("目前沒有訂單資料。", "#1E293B", "sm", "regular"));
        }
        body.put("contents", bItems);
        contents.put("body", body);

        // Footer：只放有真實資料的按鈕（帶隊對口電話）
        Map<String, Object> footer = new LinkedHashMap<>();
        footer.put("type", "box");
        footer.put("layout", "vertical");
        footer.put("spacing", "sm");
        footer.put("paddingAll", "16px");

        List<Map<String, Object>> fItems = new ArrayList<>();
        if (d.get("foremanPhone") != null) {
            fItems.add(createButton("📞 撥打帶隊對口電話", "tel:" + String.valueOf(d.get("foremanPhone")).replaceAll("[^0-9+]", ""), "#3B82F6"));
        }
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

        bItems.add(createRowKV("出工稼動率", "96.8% (合法外展與本籍機耕雙軌調度)"));
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
        fItems.add(createButton("⚡ 預購產季早鳥保證工時包 (鎖定名額・優先排班)", "https://agridispatch.tw/web/farmer.html", "#D97706"));
        fItems.add(createButton("🗓️ 瀏覽 52 週完整產曆動態輪播", "https://agridispatch.tw/web/index.html#calendar", "#2563EB"));
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
        bItems.add(createRowKV("產季早鳥保證包", "NT$ 315 / 小時 (鎖定名額・優先排班，定金 20%)"));
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
        if (!fItems.isEmpty()) contents.put("footer", footer);

        flex.put("contents", contents);
        return flex;
    }

    private static String nz(Object v, String fallback) {
        return v == null || String.valueOf(v).isBlank() ? fallback : String.valueOf(v);
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
        hItems.add(createLabel("合法外展農工・政府津貼公示", "#E9D5FF", "xs", "bold"));
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
        bItems.add(createRowKV("合計入帳", "NT$ " + LaborConstants.formatMoney(LaborConstants.MIN_MONTHLY_WAGE + LaborConstants.AGRI_EMPLOYMENT_BONUS) + " (合法外展・全額勞健保)"));
        body.put("contents", bItems);
        contents.put("body", body);

        flex.put("contents", contents);
        return flex;
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

