package com.agridispatch.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 智農派工 (AgriDispatch) - RESTful API 處理器 (北部產區樞紐 ╳ 三層混合模型 ╳ 專車物流精算升級版)
 */
public class ApiHandler implements HttpHandler {
    private final Database db = Database.getInstance();

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod().toUpperCase();
        URI uri = exchange.getRequestURI();
        String path = uri.getPath();

        // 1. CORS 預檢請求
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Authorization");

        if ("OPTIONS".equals(method)) {
            exchange.sendResponseHeaders(204, -1);
            return;
        }

        try {
            // 2. 路由分發
            if (path.equals("/api/auth/login") && "POST".equals(method)) {
                handleLogin(exchange);
            } else if (path.equals("/api/crops") && "GET".equals(method)) {
                handleGetCrops(exchange);
            } else if (path.equals("/api/orders/calculate") && "POST".equals(method)) {
                handleCalculateOrder(exchange);
            } else if (path.equals("/api/orders")) {
                if ("GET".equals(method)) handleGetOrders(exchange);
                else if ("POST".equals(method)) handleCreateOrder(exchange);
            } else if (path.equals("/api/dispatch/overview") && "GET".equals(method)) {
                handleDispatchOverview(exchange);
            } else if (path.equals("/api/dispatch/weather-state") && "GET".equals(method)) {
                handleWeatherState(exchange);
            } else if (path.equals("/api/dispatch/weather-scenario") && "POST".equals(method)) {
                handleWeatherScenario(exchange);
            } else if (path.equals("/api/dispatch/weather-regions") && ("PUT".equals(method) || "POST".equals(method))) {
                handleWeatherRegions(exchange);
            } else if (path.equals("/api/dispatch/weather-switch") && "POST".equals(method)) {
                handleWeatherSwitch(exchange);
            } else if (path.equals("/api/workers") && "GET".equals(method)) {
                handleGetWorkers(exchange);
            } else if (path.equals("/api/workers/local/task") && "GET".equals(method)) {
                handleLocalWorkerTask(exchange);
            } else if (path.startsWith("/api/workers/") && path.endsWith("/task") && "GET".equals(method)) {
                handleWorkerTask(exchange, path, uri.getQuery());
            } else if (path.startsWith("/api/workers/") && path.endsWith("/clock-in") && "POST".equals(method)) {
                handleWorkerClockIn(exchange, path);
            } else if (path.equals("/api/talents/register") && "POST".equals(method)) {
                handleRegisterTalent(exchange);
            } else if (path.equals("/api/talents") && "GET".equals(method)) {
                handleGetTalents(exchange);
            } else if (path.equals("/api/financial/chart-data") && "GET".equals(method)) {
                handleChartData(exchange);
            } else if (path.equals("/api/financial/summary") && "GET".equals(method)) {
                handleFinancialSummary(exchange);
            } else if ((path.equals("/api/brochures") || path.startsWith("/api/brochures/"))) {
                handleBrochures(exchange, path, method);
            } else if (path.equals("/api/calendar") && "GET".equals(method)) {
                handleCalendar(exchange);
            } else if (path.equals("/health")) {
                handleHealth(exchange);
            } else {
                sendJsonResponse(exchange, 404, Map.of("error", "Not Found", "path", path));
            }
        } catch (Exception e) {
            e.printStackTrace();
            sendJsonResponse(exchange, 500, Map.of("error", e.getMessage()));
        }
    }

    private void handleLogin(HttpExchange exchange) throws IOException {
        String body = readRequestBody(exchange);
        Map<String, String> req = JsonHelper.parseSimpleJson(body);
        String username = req.get("username");
        String password = req.get("password");

        Map<String, Object> user = db.authenticate(username, password);
        if (user != null) {
            sendJsonResponse(exchange, 200, Map.of(
                    "success", true,
                    "user", user,
                    "token", "jwt-mock-" + user.get("role") + "-" + System.currentTimeMillis()
            ));
        } else {
            sendJsonResponse(exchange, 401, Map.of(
                    "success", false,
                    "error", "帳號或密碼錯誤。可選帳號：worker_foreign / worker_local / farmer / admin_tech"
            ));
        }
    }

    private void handleGetCrops(HttpExchange exchange) throws IOException {
        sendJsonResponse(exchange, 200, Map.of("success", true, "data", db.getCrops()));
    }

    private void handleCalculateOrder(HttpExchange exchange) throws IOException {
        String body = readRequestBody(exchange);
        Map<String, String> req = JsonHelper.parseSimpleJson(body);

        String cropId = req.getOrDefault("cropId", "taoyuan_veg");
        double area = parseDouble(req.get("area"), 5.0);
        String plan = req.getOrDefault("plan", "early_bird");
        String zone = req.getOrDefault("zone", "core_35km");

        Map<String, Object> crop = db.getCropById(cropId);
        int baseHours = (int) crop.get("baseHoursPerFen");
        int workersPerFen = (int) crop.get("workersPerFen");
        int unitRate = (int) crop.get("rate");

        int totalHours = (int) Math.round(baseHours * area);
        int suggestedWorkers = Math.max(2, (int) Math.round(workersPerFen * (area > 3 ? 1.5 : 1)));
        double estDays = Math.round((totalHours / (suggestedWorkers * 8.0)) * 10.0) / 10.0;

        double discount = 1.0;
        String planName = "【Tier 1】產季早鳥工時包 (9折・保證出工)";
        int surcharge = "cross_region".equals(zone) ? 1200 : 0;

        if ("early_bird".equals(plan)) {
            discount = 0.90;
            planName = "【Tier 1】核心外展移工早鳥包 (預售 9 折・20%定金鎖定)";
        } else if ("hourly_flex".equals(plan)) {
            discount = 1.0;
            unitRate = 310;
            planName = "【Tier 2】在地彈性兼職包 (隨叫隨派・時薪 $310・零底薪負擔)";
        } else if ("turnkey".equals(plan)) {
            discount = 0.95;
            planName = "【Tier 3】成果統包專業代耕 (無人機/機耕驗收付款・免監工)";
        }

        long standardTotal = Math.round(totalHours * unitRate);
        long discountedTotal = Math.round(standardTotal * discount);
        long actualTotal = discountedTotal + surcharge;
        long deposit = "early_bird".equals(plan) ? Math.round(discountedTotal * 0.2) : ("turnkey".equals(plan) ? Math.round(discountedTotal * 0.3) : 0);
        long savings = standardTotal - discountedTotal;

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("cropId", crop.get("id"));
        data.put("cropName", crop.get("name"));
        data.put("area", area);
        data.put("plan", plan);
        data.put("planName", planName);
        data.put("transportZone", zone);
        data.put("transportSurcharge", surcharge);
        data.put("totalHours", totalHours);
        data.put("suggestedWorkers", suggestedWorkers);
        data.put("estDays", estDays);
        data.put("unitRate", unitRate);
        data.put("standardTotal", standardTotal + surcharge);
        data.put("actualTotal", actualTotal);
        data.put("deposit", deposit);
        data.put("savings", savings);

        sendJsonResponse(exchange, 200, Map.of("success", true, "data", data));
    }

    private void handleCreateOrder(HttpExchange exchange) throws IOException {
        String body = readRequestBody(exchange);
        Map<String, String> req = JsonHelper.parseSimpleJson(body);

        String farmerName = req.getOrDefault("farmerName", "示範農場");
        String phone = req.getOrDefault("phone", "0912-345-678");
        String farmLocation = req.getOrDefault("farmLocation", "桃園市八德區興豐路");
        String cropId = req.getOrDefault("cropId", "taoyuan_veg");
        double area = parseDouble(req.get("area"), 5.0);
        String plan = req.getOrDefault("plan", "early_bird");
        String zone = req.getOrDefault("zone", "core_35km");

        Map<String, Object> crop = db.getCropById(cropId);
        int totalHours = (int) Math.round((int) crop.get("baseHoursPerFen") * area);
        int suggestedWorkers = Math.max(2, (int) Math.round((int) crop.get("workersPerFen") * (area > 3 ? 1.5 : 1)));
        int unitRate = (int) crop.get("rate");
        if ("hourly_flex".equals(plan)) unitRate = 310;

        double discount = "early_bird".equals(plan) ? 0.90 : ("turnkey".equals(plan) ? 0.95 : 1.0);
        int surcharge = "cross_region".equals(zone) ? 1200 : 0;
        long standardAmount = Math.round(totalHours * unitRate);
        long discountedAmount = Math.round(standardAmount * discount);
        long finalAmount = discountedAmount + surcharge;
        long depositAmount = "early_bird".equals(plan) ? Math.round(discountedAmount * 0.2) : ("turnkey".equals(plan) ? Math.round(discountedAmount * 0.3) : 0);

        Map<String, Object> newOrder = new LinkedHashMap<>();
        newOrder.put("farmerName", farmerName);
        newOrder.put("phone", phone);
        newOrder.put("farmLocation", farmLocation);
        newOrder.put("cropId", crop.get("id"));
        newOrder.put("cropName", crop.get("name"));
        newOrder.put("area", area);
        newOrder.put("plan", plan);
        newOrder.put("planName", "early_bird".equals(plan) ? "【Tier 1】產季早鳥工時包" : ("hourly_flex".equals(plan) ? "【Tier 2】在地彈性兼職包" : "【Tier 3】成果統包代耕"));
        newOrder.put("transportZone", zone);
        newOrder.put("transportSurcharge", surcharge);
        newOrder.put("totalHours", totalHours);
        newOrder.put("suggestedWorkers", suggestedWorkers);
        newOrder.put("unitRate", unitRate);
        newOrder.put("standardAmount", standardAmount + surcharge);
        newOrder.put("finalAmount", finalAmount);
        newOrder.put("depositAmount", depositAmount);
        newOrder.put("depositPaid", "early_bird".equals(plan));
        newOrder.put("status", "confirmed");
        newOrder.put("assignedTeamId", "cross_region".equals(zone) ? "team_b" : "team_a");

        Map<String, Object> created = db.addOrder(newOrder);
        sendJsonResponse(exchange, 201, Map.of("success", true, "data", created));
    }

    private void handleGetOrders(HttpExchange exchange) throws IOException {
        sendJsonResponse(exchange, 200, Map.of("success", true, "data", db.getOrders()));
    }

    private void handleDispatchOverview(HttpExchange exchange) throws IOException {
        boolean rain = db.isRainMode();
        List<Map<String, Object>> teams = db.getDispatchTeams();
        List<Map<String, Object>> displayTeams = new ArrayList<>();

        for (Map<String, Object> t : teams) {
            Map<String, Object> dt = new LinkedHashMap<>(t);
            dt.put("currentLocation", rain ? t.get("hedgeLocation") : t.get("normalLocation"));
            dt.put("currentTask", rain ? t.get("hedgeTask") : t.get("normalTask"));
            dt.put("displayStatus", rain
                    ? ("team_d".equals(t.get("id")) ? "冷鏈常態駐點" : "冷鏈避險作業中")
                    : ("team_d".equals(t.get("id")) ? "冷鏈常態駐點" : "田間出勤中"));
            displayTeams.add(dt);
        }

        Map<String, Object> weather = new LinkedHashMap<>();
        weather.put("scenarioId", db.getCurrentScenarioId());
        weather.put("isRainMode", rain);
        weather.put("currentWeather", db.getCurrentWeather());
        weather.put("location", "桃園八德 / 宜蘭三星");
        weather.put("alertLevel", db.getAlertLevel());
        weather.put("message", db.getWeatherMessage());
        weather.put("securedRevenue", db.getSecuredRevenue());
        weather.put("dailyCapacityHours", db.getDailyCapacityHrs());
        weather.put("regionalWeather", db.getRegionalWeather());

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("weather", weather);
        data.put("quota", Map.of("total", 20, "current", 20, "attendanceRate", "100.0%"));
        data.put("financial", Map.of(
                "annualGmv", 9257000,
                "monthlyRevenue", 771416,
                "totalAgriSubsidy", 176000,
                "techTakeRate", 173100,
                "cooperativeShare", 598316
        ));
        data.put("teams", displayTeams);

        sendJsonResponse(exchange, 200, Map.of("success", true, "data", data));
    }

    private void handleWeatherState(HttpExchange exchange) throws IOException {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("scenarioId", db.getCurrentScenarioId());
        state.put("isRainMode", db.isRainMode());
        state.put("currentWeather", db.getCurrentWeather());
        state.put("alertLevel", db.getAlertLevel());
        state.put("weatherMessage", db.getWeatherMessage());
        state.put("securedRevenue", db.getSecuredRevenue());
        state.put("dailyCapacityHrs", db.getDailyCapacityHrs());
        state.put("regionalWeather", db.getRegionalWeather());
        sendJsonResponse(exchange, 200, Map.of("success", true, "data", state));
    }

    private void handleWeatherScenario(HttpExchange exchange) throws IOException {
        String body = readRequestBody(exchange);
        Map<String, String> req = JsonHelper.parseSimpleJson(body);
        String scenarioId = req.getOrDefault("scenarioId", "sunny");
        Map<String, Object> result = db.applyWeatherScenario(scenarioId);
        sendJsonResponse(exchange, 200, Map.of("success", true, "data", result, "message", "已成功切換天候情境: " + scenarioId));
    }

    private void handleWeatherRegions(HttpExchange exchange) throws IOException {
        String body = readRequestBody(exchange);
        sendJsonResponse(exchange, 200, Map.of("success", true, "message", "已成功同步全台農場地點與微氣象"));
    }

    private void handleWeatherSwitch(HttpExchange exchange) throws IOException {
        String body = readRequestBody(exchange);
        Map<String, String> req = JsonHelper.parseSimpleJson(body);
        boolean newRainMode = req.containsKey("isRainMode")
                ? Boolean.parseBoolean(req.get("isRainMode"))
                : !db.isRainMode();

        db.setRainMode(newRainMode);

        Map<String, Object> weather = new LinkedHashMap<>();
        weather.put("scenarioId", db.getCurrentScenarioId());
        weather.put("isRainMode", newRainMode);
        weather.put("currentWeather", db.getCurrentWeather());
        weather.put("message", db.getWeatherMessage());
        weather.put("securedRevenue", db.getSecuredRevenue());
        weather.put("regionalWeather", db.getRegionalWeather());

        String msg = newRainMode
                ? "已啟動室內八德冷鏈避險機制，成功保全 NT$ 62,500 營收！"
                : "已恢復田間正常溫室作業模式";

        sendJsonResponse(exchange, 200, Map.of("success", true, "data", Map.of("weather", weather, "message", msg)));
    }

    private void handleGetWorkers(HttpExchange exchange) throws IOException {
        sendJsonResponse(exchange, 200, Map.of("success", true, "data", db.getWorkers()));
    }

    // 本地勞工專屬任務卡
    private void handleLocalWorkerTask(HttpExchange exchange) throws IOException {
        Map<String, Object> task = new LinkedHashMap<>();
        task.put("workerId", "W012");
        task.put("name", "陳建宏");
        task.put("roleTitle", "資深農機領航技師 / 無人機代噴雙證照飛手 (Tier 3)");
        task.put("assignedVehicle", "智農 3 號工程車 (車號: T50-8888)");
        task.put("equipment", "大疆 T50 農業植保機 (雙RTK衛星導航) ╳ 久保田 80馬力曳引機");
        task.put("shiftTime", "06:00 ~ 15:30 (避開午後高溫)");
        task.put("location", "新竹峨眉茶園專區 / 桃園設施蔬果區");
        task.put("taskContent", "30 分地無人機微粒噴霧精準代噴 ＋ 曳引機整地翻土作業");
        task.put("salaryInfo", Map.of(
                "baseSalary", "NT$ 42,000",
                "performanceBonus", "+ NT$ 12,500 (分地成果統包分紅)",
                "pensionFund", "依法強制提繳 6% (NT$ 2,520)",
                "insurance", "勞工職災保險 ＋ 500 萬高空農機意外險 (合作社全額投保)",
                "totalEst", "NT$ 54,500"
        ));
        task.put("checklist", List.of(
                "無人機噴嘴超音波清洗與濾網檢驗",
                "鋰電池循環充放電電壓平衡測試 (14S)",
                "農田上空 50 公尺空域地圖校準與避障雷達檢測"
        ));

        sendJsonResponse(exchange, 200, Map.of("success", true, "data", task));
    }

    // 外籍移工三語任務卡
    private void handleWorkerTask(HttpExchange exchange, String path, String query) throws IOException {
        String[] parts = path.split("/");
        String workerId = parts.length >= 4 ? parts[3] : "W001";
        String lang = "zh";
        if (query != null && query.contains("lang=")) {
            int idx = query.indexOf("lang=");
            lang = query.substring(idx + 5).split("&")[0];
        }

        Map<String, Object> worker = db.getWorkerById(workerId);
        Map<String, Object> team = db.getTeamById((String) worker.get("teamId"));
        boolean rain = db.isRainMode();

        Map<String, String> card = new LinkedHashMap<>();
        if ("vi".equals(lang)) {
            card.put("title", "Thẻ Nhiệm Vụ Hôm Nay");
            card.put("badge", rain ? "🌧️ Phòng Tránh Mưa・Chuyển Kho Lạnh" : "Đã Điều Phái・Xe Đón Tận Nơi");
            card.put("shiftTime", "Thời Gian Làm Việc");
            card.put("timeVal", "06:30 Tập hợp lên xe / 07:00 ~ 16:00 Làm việc");
            card.put("van", "Xe Đưa Đón");
            card.put("vanVal", team.get("vehicle") + " (Tài xế: " + team.get("driver") + ")");
            card.put("location", "Địa Điểm Làm Việc");
            card.put("locVal", (String) (rain ? team.get("hedgeLocation") : team.get("normalLocation")));
            card.put("task", "Nội Dung Công Việc");
            card.put("taskVal", (String) (rain ? team.get("hedgeTask") : team.get("normalTask")));
            card.put("safety", "Quy Tắc An Toàn Lao Động");
            card.put("safetyVal", rain ? "Kho lạnh 12~15°C: Mặc áo ấm và giày chống trượt." : "Chú ý chống sốc nhiệt! Uống 300cc nước mỗi 45 phút.");
            card.put("salaryTitle", "Thu Nhập Tháng & Trợ Cấp Chính Phủ");
            card.put("basePayVal", "NT$ 28,590");
            card.put("bonusVal", "+ NT$ 8,800 (Đã Chuyển Khoản)");
            card.put("totalEstVal", "NT$ 38,590");
        } else if ("id".equals(lang)) {
            card.put("title", "Kartu Tugas Hari Ini");
            card.put("badge", rain ? "🌧️ Mitigasi Hujan・Pindah Cold Chain" : "Ditugaskan・Penjemputan Armada");
            card.put("shiftTime", "Jam Kerja");
            card.put("timeVal", "06:30 Kumpul naik mobil / 07:00 ~ 16:00 Kerja");
            card.put("van", "Mobil Jemputan");
            card.put("vanVal", team.get("vehicle") + " (Sopir: " + team.get("driver") + ")");
            card.put("location", "Lokasi Kerja");
            card.put("locVal", (String) (rain ? team.get("hedgeLocation") : team.get("normalLocation")));
            card.put("task", "Tugas Utama");
            card.put("taskVal", (String) (rain ? team.get("hedgeTask") : team.get("normalTask")));
            card.put("safety", "Keselamatan Kerja Pertanian");
            card.put("safetyVal", rain ? "Suhu dingin 12~15°C: Gunakan jaket hangat." : "Waspada dehidrasi panas! Istirahat minum tiap 45 menit.");
            card.put("salaryTitle", "Akumulasi Gaji & Subsidi Pemerintah");
            card.put("basePayVal", "NT$ 28,590");
            card.put("bonusVal", "+ NT$ 8,800 (Sudah Masuk Rekening)");
            card.put("totalEstVal", "NT$ 38,590");
        } else {
            card.put("title", "今日派工任務卡");
            card.put("badge", rain ? "🌧️ 冷鏈避險・專車轉移" : "已派工・專車接送");
            card.put("shiftTime", "出勤時間");
            card.put("timeVal", "06:30 集合上車 / 07:00 ~ 16:00 作業");
            card.put("van", "接送專車");
            card.put("vanVal", team.get("vehicle") + " (司機: " + team.get("driver") + ")");
            card.put("location", "作業地點");
            card.put("locVal", (String) (rain ? team.get("hedgeLocation") : team.get("normalLocation")));
            card.put("task", "主要作業內容");
            card.put("taskVal", (String) (rain ? team.get("hedgeTask") : team.get("normalTask")));
            card.put("safety", "農務安全注意事項");
            card.put("safetyVal", rain ? "冷鏈低溫防護：室內預冷室 12~15°C，請穿著防風外套與防滑鞋。" : "高溫防暑！每 45 分鐘強制至遮陽棚飲水 300cc。");
            card.put("salaryTitle", "本月累計所得與政府就業獎勵");
            card.put("basePayVal", "NT$ 28,590");
            card.put("bonusVal", "+ NT$ 8,800 (中央撥付已入帳)");
            card.put("totalEstVal", "NT$ 38,590");
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("worker", worker);
        data.put("team", team);
        data.put("isRainMode", rain);
        data.put("card", card);

        sendJsonResponse(exchange, 200, Map.of("success", true, "data", data));
    }

    private void handleWorkerClockIn(HttpExchange exchange, String path) throws IOException {
        String[] parts = path.split("/");
        String workerId = parts.length >= 4 ? parts[3] : "W001";
        String body = readRequestBody(exchange);
        Map<String, String> req = JsonHelper.parseSimpleJson(body);

        Map<String, Object> clockIn = new LinkedHashMap<>();
        clockIn.put("workerId", workerId);
        clockIn.put("latitude", req.getOrDefault("latitude", "24.9348"));
        clockIn.put("longitude", req.getOrDefault("longitude", "121.2868"));
        clockIn.put("locationName", req.getOrDefault("locationName", "桃園市八德區有機蔬菜產銷班現場"));
        clockIn.put("status", "success");

        Map<String, Object> saved = db.addClockIn(clockIn);
        sendJsonResponse(exchange, 201, Map.of(
                "success", true,
                "data", saved,
                "message", "✅ 打卡成功！GPS 定位: " + saved.get("locationName")
        ));
    }

    private void handleRegisterTalent(HttpExchange exchange) throws IOException {
        String body = readRequestBody(exchange);
        Map<String, String> req = JsonHelper.parseSimpleJson(body);

        String name = req.get("name");
        String phone = req.get("phone");
        String category = req.getOrDefault("category", "transfer_worker");
        String exp = req.getOrDefault("experience", "無特殊備註");

        Map<String, Object> talent = new LinkedHashMap<>();
        talent.put("name", name);
        talent.put("phone", phone);
        talent.put("category", category);
        talent.put("experience", exp);
        talent.put("pdpaConsent", true);

        Map<String, Object> saved = db.addTalent(talent);
        sendJsonResponse(exchange, 201, Map.of(
                "success", true,
                "data", saved,
                "message", "登錄成功！您的資料已妥善加密保存於特許合規人才庫。"
        ));
    }

    private void handleGetTalents(HttpExchange exchange) throws IOException {
        sendJsonResponse(exchange, 200, Map.of("success", true, "data", db.getTalents()));
    }

    private void handleBrochures(HttpExchange exchange, String path, String method) throws IOException {
        if ("GET".equals(method)) {
            sendJsonResponse(exchange, 200, Map.of("success", true, "data", db.getBrochures()));
        } else if ("POST".equals(method)) {
            String body = readRequestBody(exchange);
            Map<String, String> parsed = JsonHelper.parseSimpleJson(body);
            Map<String, Object> dm = new LinkedHashMap<>(parsed);
            db.addBrochure(dm);
            sendJsonResponse(exchange, 201, Map.of("success", true, "data", dm, "message", "DM 專案已建立"));
        } else if ("PUT".equals(method)) {
            String[] parts = path.split("/");
            String id = parts[parts.length - 1];
            String body = readRequestBody(exchange);
            Map<String, String> parsed = JsonHelper.parseSimpleJson(body);
            Map<String, Object> dm = new LinkedHashMap<>(parsed);
            db.updateBrochure(id, dm);
            sendJsonResponse(exchange, 200, Map.of("success", true, "data", dm, "message", "DM 專案已更新"));
        } else if ("DELETE".equals(method)) {
            String[] parts = path.split("/");
            String id = parts[parts.length - 1];
            db.deleteBrochure(id);
            sendJsonResponse(exchange, 200, Map.of("success", true, "message", "DM 專案已刪除"));
        } else {
            sendJsonResponse(exchange, 200, Map.of("success", true));
        }
    }

    private void handleCalendar(HttpExchange exchange) throws IOException {
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("success", true);
        res.put("quarters", List.of(
            Map.of("id", "Q1", "title", "Q1 春耕整地與早春搶收 (1月 ~ 3月)", "highlight", "屏東恆春/車城洋蔥拔蔥、大湖草莓第二期花、北部有機溫室早春換畦"),
            Map.of("id", "Q2", "title", "Q2 初夏黃金盛產大收穫 (4月 ~ 6月)", "highlight", "高屏外銷愛文芒果留蒂採收、新竹峨眉手採夏茶、宜蘭三星青蔥盛產拔蔥"),
            Map.of("id", "Q3", "title", "Q3 盛夏秋收與防颱搶收 (7月 ~ 9月)", "highlight", "壯圍網紋洋香瓜採收、麻豆老欉文旦柚白露搶收、全台突發颱風24H防汛馳援"),
            Map.of("id", "Q4", "title", "Q4 冬季高價值作物與整枝 (10月 ~ 12月)", "highlight", "新埔九降風牛心柿餅削皮曬盤、大湖滿意香水草莓首期花、全台冬茶手採")
        ));
        sendJsonResponse(exchange, 200, res);
    }

    // 科技公司專屬圖表數據 (對齊財務計畫報表3)
    private void handleChartData(HttpExchange exchange) throws IOException {
        Map<String, Object> chartData = new LinkedHashMap<>();

        // 1. 財務分流比例 (科技公司技術費 18% + 交通加成 NT$ 2,077,200 vs 合作社成本平移 82% NT$ 7,179,800)
        chartData.put("revenueSplit", Map.of(
                "labels", List.of("科技公司技術代管費 18% + 專車加成 (NT$ 2,077,200)", "合作社移工成本平移 82% (NT$ 7,179,800)"),
                "values", List.of(2077200, 7179800),
                "colors", List.of("#10B981", "#3B82F6")
        ));

        // 2. 產季月份 GMV 營收成長預估 (萬元)
        chartData.put("monthlyGmv", Map.of(
                "labels", List.of("1月", "2月", "3月", "4月", "5月", "6月", "7月", "8月", "9月", "10月", "11月", "12月"),
                "values", List.of(52, 55, 68, 85, 120, 145, 130, 95, 72, 60, 58, 62)
        ));

        // 3. 工班出勤率與避險成效 (%)
        chartData.put("attendanceHedge", Map.of(
                "labels", List.of("週一", "週二", "週三 (突發暴雨)", "週四", "週五", "週六", "週日"),
                "withHedge", List.of(100, 100, 100, 100, 100, 95, 95),
                "withoutHedge", List.of(100, 100, 0, 100, 100, 95, 95)
        ));

        sendJsonResponse(exchange, 200, Map.of("success", true, "data", chartData));
    }

    private void handleFinancialSummary(HttpExchange exchange) throws IOException {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("totalGmv", 9257000);
        data.put("techTakeRate", 2077200);
        data.put("takeRatePercentage", "18% + 交通加成");
        data.put("coopPassThrough", 7179800);
        data.put("totalAgriSubsidy", 2112000);
        data.put("ebitda", 994800);
        data.put("youthLoan", 1000000);
        data.put("dscr", "41.8x");
        data.put("breakEvenWorkers", 11.3);
        data.put("workerCount", 20);

        sendJsonResponse(exchange, 200, Map.of("success", true, "data", data));
    }

    private void handleHealth(HttpExchange exchange) throws IOException {
        sendJsonResponse(exchange, 200, Map.of(
                "status", "ok",
                "service", "AgriDispatch Java Server",
                "runtime", System.getProperty("java.version")
        ));
    }

    private void sendJsonResponse(HttpExchange exchange, int statusCode, Object data) throws IOException {
        String json = JsonHelper.toJson(data);
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private String readRequestBody(HttpExchange exchange) throws IOException {
        try (InputStream is = exchange.getRequestBody();
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[1024];
            int n;
            while ((n = is.read(buf)) != -1) {
                bos.write(buf, 0, n);
            }
            return bos.toString(StandardCharsets.UTF_8);
        }
    }

    private double parseDouble(String str, double def) {
        if (str == null) return def;
        try {
            return Double.parseDouble(str);
        } catch (Exception e) {
            return def;
        }
    }
}
