package com.agridispatch.service;

import com.agridispatch.common.ApiException;
import com.agridispatch.common.IdGenerator;
import com.agridispatch.common.PartialUpdate;
import com.agridispatch.entity.DispatchTeam;
import com.agridispatch.entity.WeatherState;
import com.agridispatch.repository.DispatchTeamRepository;
import com.agridispatch.repository.OrderRepository;
import com.agridispatch.repository.TeamRatingRepository;
import com.agridispatch.repository.WeatherStateRepository;
import com.agridispatch.repository.WorkerRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 智慧派遣與天候避險服務
 * 天候狀態為全域可變狀態：所有讀寫皆以 synchronized 保護，並於啟動時由 weather_states 讀回。
 */
@Service
@Transactional
public class DispatchService {

    private static final Logger log = LoggerFactory.getLogger(DispatchService.class);
    private static final int MAX_REGIONS = 50;

    private final DispatchTeamRepository teamRepository;
    private final WeatherStateRepository weatherStateRepository;
    private final WorkerRepository workerRepository;
    private final OrderRepository orderRepository;
    private final TeamRatingRepository teamRatingRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private String currentScenarioId = "cloudy";
    private boolean isRainMode = false;
    private String currentWeather = "舒適多雲 26°C (微風涼爽宜農作)";
    private String weatherMessage = "🟢 全台天候良好・外展農工出勤率 100% (26/26人在崗)・作業稼動率高";
    private String alertLevel = "normal";
    private int securedRevenue = 62500;
    private int dailyCapacityHrs = 160;

    private List<Map<String, Object>> currentRegionalWeather = new ArrayList<>();

    public DispatchService(DispatchTeamRepository teamRepository,
                           WeatherStateRepository weatherStateRepository,
                           WorkerRepository workerRepository,
                           OrderRepository orderRepository,
                           TeamRatingRepository teamRatingRepository) {
        this.teamRepository = teamRepository;
        this.weatherStateRepository = weatherStateRepository;
        this.workerRepository = workerRepository;
        this.orderRepository = orderRepository;
        this.teamRatingRepository = teamRatingRepository;
        initDefaultRegionalWeather();
    }

    private void initDefaultRegionalWeather() {
        currentRegionalWeather = createScenarioRegions("cloudy");
    }

    /**
     * 啟動時從 weather_states（WS-ACTIVE）讀回天候避險狀態，避免重啟後回到預設值
     */
    public synchronized void restoreStateFromDb() {
        try {
            weatherStateRepository.findById("WS-ACTIVE").ifPresent(ws -> {
                if (ws.getScenarioId() != null) this.currentScenarioId = ws.getScenarioId();
                this.isRainMode = "Y".equalsIgnoreCase(ws.getIsRainMode());
                if (ws.getCurrentWeather() != null) this.currentWeather = ws.getCurrentWeather();
                if (ws.getAlertLevel() != null) this.alertLevel = ws.getAlertLevel();
                if (ws.getMessage() != null) this.weatherMessage = ws.getMessage();
                if (ws.getSecuredRevenue() != null) this.securedRevenue = ws.getSecuredRevenue();
                if (ws.getDailyCapacityHrs() != null) this.dailyCapacityHrs = ws.getDailyCapacityHrs();
                if (ws.getRegionalJson() != null && !ws.getRegionalJson().isBlank()) {
                    try {
                        List<Map<String, Object>> regions = objectMapper.readValue(ws.getRegionalJson(),
                                new TypeReference<List<Map<String, Object>>>() {});
                        if (regions != null && !regions.isEmpty()) this.currentRegionalWeather = new ArrayList<>(regions);
                    } catch (Exception e) {
                        log.warn("weather_states.regional_json 解析失敗，沿用預設產區氣象：{}", e.getMessage());
                    }
                }
            });
        } catch (Exception e) {
            log.warn("無法從 weather_states 讀回天候狀態：{}", e.getMessage());
        }
    }

    public List<DispatchTeam> getAllTeams() {
        return teamRepository.findAll();
    }

    public Optional<DispatchTeam> getTeamById(String id) {
        return teamRepository.findById(id);
    }

    /**
     * 新增工班：名稱、領班、領班電話必填；未給代號時自動編 team_01、team_02…。
     * 車輛、司機、晴雨天地點與任務在 Oracle 為 NOT NULL（空字串在 Oracle 即 NULL），未填一律存「待安排」。
     */
    public DispatchTeam createTeam(DispatchTeam team) {
        if (team == null) throw ApiException.badRequest("缺少工班資料");
        team.setName(requireText(team.getName(), "工班名稱 (name)", 50));
        team.setLeader(requireText(team.getLeader(), "領班姓名 (leader)", 50));
        team.setLeaderPhone(requireText(team.getLeaderPhone(), "領班電話 (leaderPhone)", 30));
        team.setVehicle(orPending(team.getVehicle(), "車輛 (vehicle)", 100));
        team.setDriver(orPending(team.getDriver(), "司機 (driver)", 50));
        team.setNormalLocation(orPending(team.getNormalLocation(), "晴天作業地點", 255));
        team.setNormalTask(orPending(team.getNormalTask(), "晴天作業內容", 255));
        team.setHedgeLocation(orPending(team.getHedgeLocation(), "雨天避險地點", 255));
        team.setHedgeTask(orPending(team.getHedgeTask(), "雨天避險內容", 255));
        if (team.getStatus() == null || team.getStatus().isBlank()) team.setStatus("active_field");
        if (!TEAM_STATUSES.contains(team.getStatus())) throw ApiException.badRequest("工班狀態只能是 " + TEAM_STATUSES);
        if (team.getWorkerCount() < 0) throw ApiException.badRequest("工班人數不可為負數");

        String id = team.getId() == null ? "" : team.getId().trim();
        if (id.isEmpty()) {
            List<String> ids = teamRepository.findAll().stream().map(DispatchTeam::getId).toList();
            id = IdGenerator.next("team_", 2, ids, teamRepository::existsById);
        } else if (!id.matches("[A-Za-z0-9_-]{1,32}")) {
            throw ApiException.badRequest("工班代號只能用英數字、底線、連字號，最多 32 字");
        } else if (teamRepository.existsById(id)) {
            throw ApiException.conflict("工班代號已存在: " + id);
        }
        team.setId(id);
        return teamRepository.save(team);
    }

    /** 部分更新：只改 body 中有帶且非 null 的欄位（id 不可改）；名稱明確傳空字串回 400 */
    public DispatchTeam updateTeam(String id, Map<String, Object> body) {
        DispatchTeam existing = teamRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("找不到該工班代號: " + id));
        PartialUpdate.rejectBlank(body, "name", "工班名稱");
        PartialUpdate.apply(existing, body, Set.of("id"));
        if (existing.getWorkerCount() < 0) throw ApiException.badRequest("工班人數不可為負數");
        return teamRepository.save(existing);
    }

    /** 刪除工班：仍有工人、訂單或評價掛在這個工班時拒絕（409），請先移走工人或改派訂單 */
    public void deleteTeam(String id) {
        if (!teamRepository.existsById(id)) throw ApiException.notFound("找不到該工班代號: " + id);
        int workers = workerRepository.findByTeamId(id).size();
        int orders = orderRepository.findByAssignedTeamId(id).size();
        int ratings = teamRatingRepository.findByTeamIdOrderByCreatedAtDesc(id).size();
        if (workers + orders + ratings > 0) {
            throw ApiException.conflict(String.format(
                    "工班 %s 仍有 %d 位工人、%d 張訂單、%d 筆評價，請先移走工人或改派訂單後再刪除", id, workers, orders, ratings));
        }
        teamRepository.deleteById(id);
    }

    private static final Set<String> TEAM_STATUSES = Set.of("active_field", "hedged_indoor", "standby");

    private static String requireText(String value, String label, int maxLen) {
        if (value == null || value.isBlank()) throw ApiException.badRequest(label + " 為必填欄位");
        return limit(value.trim(), label, maxLen);
    }

    private static String orPending(String value, String label, int maxLen) {
        return value == null || value.isBlank() ? "待安排" : limit(value.trim(), label, maxLen);
    }

    private static String limit(String value, String label, int maxLen) {
        if (value.length() > maxLen) throw ApiException.badRequest(label + " 最多 " + maxLen + " 字");
        return value;
    }

    public synchronized boolean isRainMode() { return isRainMode; }
    public synchronized String getCurrentWeather() { return currentWeather; }
    public synchronized String getWeatherMessage() { return weatherMessage; }
    public synchronized String getCurrentScenarioId() { return currentScenarioId; }
    public synchronized String getAlertLevel() { return alertLevel; }
    public synchronized List<Map<String, Object>> getRegionalWeather() { return new ArrayList<>(currentRegionalWeather); }

    /** 一次取得完整天候狀態快照（避免多次呼叫 getter 之間狀態被改動） */
    public synchronized Map<String, Object> getWeatherStateSnapshot() {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("scenarioId", currentScenarioId);
        state.put("isRainMode", isRainMode);
        state.put("currentWeather", currentWeather);
        state.put("alertLevel", alertLevel);
        state.put("weatherMessage", weatherMessage);
        state.put("regionalWeather", new ArrayList<>(currentRegionalWeather));
        return state;
    }

    /**
     * 應用指定的天氣情境 (sunny, cloudy, shower, heavy_rain, typhoon, cold_wave)
     */
    public synchronized Map<String, Object> applyWeatherScenario(String scenarioId) {
        if (scenarioId == null || scenarioId.trim().isEmpty()) {
            scenarioId = "sunny";
        }
        if (!List.of("sunny", "cloudy", "shower", "heavy_rain", "typhoon", "cold_wave").contains(scenarioId)) {
            throw new IllegalArgumentException("不支援的天候情境：" + scenarioId);
        }
        this.currentScenarioId = scenarioId;

        List<DispatchTeam> teams = teamRepository.findAll();
        String scenarioName;

        switch (scenarioId) {
            case "sunny":
                scenarioName = "全台晴朗炎熱";
                this.isRainMode = false;
                this.currentWeather = "全台晴朗 31°C (高溫無雨)";
                this.alertLevel = "normal";
                this.weatherMessage = "🟢 全台晴朗田間作業中・5 大核心產區出勤率 100%・注意防曬與定時補水";
                this.securedRevenue = 62500;
                this.dailyCapacityHrs = 160;
                for (DispatchTeam t : teams) {
                    if (!"team_d".equals(t.getId())) {
                        t.setStatus("active_field");
                    } else {
                        t.setStatus("hedged_indoor");
                    }
                    teamRepository.save(t);
                }
                break;

            case "cloudy":
                scenarioName = "舒適多雲時晴";
                this.isRainMode = false;
                this.currentWeather = "舒適多雲 26°C (微風涼爽宜農作)";
                this.alertLevel = "normal";
                this.weatherMessage = "🟢 舒適天候正常出勤中・氣溫宜人作業稼動率高・無人機航測與田間管理順暢";
                this.securedRevenue = 62500;
                this.dailyCapacityHrs = 160;
                for (DispatchTeam t : teams) {
                    if (!"team_d".equals(t.getId())) {
                        t.setStatus("active_field");
                    } else {
                        t.setStatus("hedged_indoor");
                    }
                    teamRepository.save(t);
                }
                break;

            case "shower":
                scenarioName = "局部短暫對流陣雨";
                this.isRainMode = false;
                this.currentWeather = "短暫陣雨 25°C (對流雨胞逼近)";
                this.alertLevel = "info";
                this.weatherMessage = "⚠️ 局部產區有雨・宜蘭三星與北部啟動半室內洗蔥與預冷彈性備援・枋山雲林維持田間出勤";
                this.securedRevenue = 62500;
                this.dailyCapacityHrs = 160;
                for (DispatchTeam t : teams) {
                    if ("team_b".equals(t.getId()) || "team_d".equals(t.getId())) {
                        t.setStatus("hedged_indoor");
                    } else {
                        t.setStatus("active_field");
                    }
                    teamRepository.save(t);
                }
                break;

            case "heavy_rain":
                scenarioName = "滯留鋒面大暴雨 (避險模式)";
                this.isRainMode = true;
                this.currentWeather = "暴雨警戒 22°C (滯留鋒面豪雨特報)";
                this.alertLevel = "warning";
                this.weatherMessage = "⚡ 突發暴雨避險啟動：全體工班已專車轉派八德蔬果預冷冷鏈包裝線，出勤率 100%，零閒置虧損！保全營收 NT$ 62,500！";
                this.securedRevenue = 62500;
                this.dailyCapacityHrs = 160;
                for (DispatchTeam t : teams) {
                    t.setStatus("hedged_indoor");
                    teamRepository.save(t);
                }
                break;

            case "typhoon":
                scenarioName = "強烈颱風陸警警戒";
                this.isRainMode = true;
                this.currentWeather = "強颱警戒 25°C (陸上強風豪雨特報)";
                this.alertLevel = "danger";
                this.weatherMessage = "🚨 強烈颱風警戒發布：啟動 24H 緊急搶收應變方案，全區設施加固防汛，全員調度至冷鏈物流中心備戰！";
                this.securedRevenue = 85000;
                this.dailyCapacityHrs = 192;
                for (DispatchTeam t : teams) {
                    t.setStatus("hedged_indoor");
                    teamRepository.save(t);
                }
                break;

            case "cold_wave":
                scenarioName = "強烈寒流低溫特報";
                this.isRainMode = false;
                this.currentWeather = "寒流低溫 9°C (橙色低溫霜害警報)";
                this.alertLevel = "warning";
                this.weatherMessage = "❄️ 寒流急凍防霜警戒：溫室全面密閉保溫防霜害，田間噴防凍保護劑，工班於恆溫冷鏈中心進行精緻分級";
                this.securedRevenue = 62500;
                this.dailyCapacityHrs = 160;
                for (DispatchTeam t : teams) {
                    if ("team_d".equals(t.getId())) {
                        t.setStatus("hedged_indoor");
                    } else {
                        t.setStatus("active_field");
                    }
                    teamRepository.save(t);
                }
                break;

            default:
                scenarioName = "自訂天候狀態";
                break;
        }

        // 更新產區農場對應氣象 (依目前選定之農場地點自動推估帶出)
        this.currentRegionalWeather = updateScenarioForCurrentRegions(scenarioId);

        // 持久化儲存至資料表 weather_states
        saveWeatherStateToDb(scenarioId, scenarioName);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("scenarioId", this.currentScenarioId);
        result.put("scenarioName", scenarioName);
        result.put("isRainMode", this.isRainMode);
        result.put("currentWeather", this.currentWeather);
        result.put("alertLevel", this.alertLevel);
        result.put("weatherMessage", this.weatherMessage);
        result.put("securedRevenue", this.securedRevenue);
        result.put("dailyCapacityHrs", this.dailyCapacityHrs);
        result.put("regionalWeather", new ArrayList<>(this.currentRegionalWeather));
        result.put("teams", teams);

        return result;
    }

    /**
     * 更新自訂單一產區或全部產區氣象
     */
    public synchronized List<Map<String, Object>> updateRegionalWeather(List<Map<String, Object>> newRegions) {
        if (newRegions != null && newRegions.size() > MAX_REGIONS) {
            throw new IllegalArgumentException("產區數量不得超過 " + MAX_REGIONS + " 筆");
        }
        if (newRegions != null && !newRegions.isEmpty()) {
            this.currentRegionalWeather = new ArrayList<>(newRegions);
            saveWeatherStateToDb(this.currentScenarioId, "自訂產區氣象");
        }
        return new ArrayList<>(this.currentRegionalWeather);
    }

    public synchronized Map<String, Object> setRainMode(boolean rain) {
        return applyWeatherScenario(rain ? "heavy_rain" : "sunny");
    }

    public synchronized Map<String, Object> getOverview() {
        Map<String, Object> overview = new LinkedHashMap<>();
        overview.put("scenarioId", currentScenarioId);
        overview.put("isRainMode", isRainMode);
        overview.put("currentWeather", currentWeather);
        overview.put("weatherMessage", weatherMessage);
        overview.put("alertLevel", alertLevel);
        overview.put("securedRevenue", securedRevenue);
        overview.put("dailyCapacityHrs", dailyCapacityHrs);
        overview.put("regionalWeather", new ArrayList<>(currentRegionalWeather));
        overview.put("teams", teamRepository.findAll());
        return overview;
    }

    private void saveWeatherStateToDb(String scenarioId, String scenarioName) {
        try {
            String regionalJson = objectMapper.writeValueAsString(currentRegionalWeather);
            WeatherState state = weatherStateRepository.findById("WS-ACTIVE")
                    .orElse(new WeatherState("WS-ACTIVE", scenarioId, scenarioName,
                            isRainMode ? "Y" : "N", currentWeather, alertLevel,
                            weatherMessage, securedRevenue, dailyCapacityHrs,
                            regionalJson, LocalDateTime.now()));

            state.setScenarioId(scenarioId);
            state.setScenarioName(scenarioName);
            state.setIsRainMode(isRainMode ? "Y" : "N");
            state.setCurrentWeather(currentWeather);
            state.setAlertLevel(alertLevel);
            state.setMessage(weatherMessage);
            state.setSecuredRevenue(securedRevenue);
            state.setDailyCapacityHrs(dailyCapacityHrs);
            state.setRegionalJson(regionalJson);
            state.setUpdatedAt(LocalDateTime.now());

            weatherStateRepository.save(state);
        } catch (Exception e) {
            // Log and tolerate persistence error
            log.warn("無法寫入 weather_states：{}", e.getMessage());
        }
    }

    private List<Map<String, Object>> updateScenarioForCurrentRegions(String scenario) {
        if (this.currentRegionalWeather == null || this.currentRegionalWeather.isEmpty()) {
            return createScenarioRegions(scenario);
        }
        List<Map<String, Object>> list = new ArrayList<>();
        for (Map<String, Object> card : this.currentRegionalWeather) {
            String region = (String) card.getOrDefault("region", "台灣產區");
            String crop = (String) card.getOrDefault("crop", "核心農作");
            String county = (String) card.getOrDefault("county", "");
            String district = (String) card.getOrDefault("district", "");
            String farmName = (String) card.getOrDefault("farmName", region);
            list.add(deriveJavaWeatherForLocation(region, county, district, farmName, crop, scenario));
        }
        return list;
    }

    private Map<String, Object> deriveJavaWeatherForLocation(String region, String county, String district, String farmName, String crop, String scenario) {
        boolean isSouth = region.contains("屏東") || region.contains("高雄") || region.contains("台南") || county.contains("屏東") || county.contains("高雄") || county.contains("台南");
        boolean isMountain = region.contains("阿里山") || region.contains("鹿谷") || district.contains("阿里山") || district.contains("鹿谷");
        boolean isNorthEast = region.contains("宜蘭") || region.contains("桃園") || region.contains("新竹") || region.contains("花蓮") || county.contains("宜蘭") || county.contains("桃園") || county.contains("新竹") || county.contains("花蓮");
        boolean isTea = crop.contains("茶");
        boolean isVeg = crop.contains("菜") || crop.contains("蔥") || crop.contains("椒") || crop.contains("洋蔥");

        String temp = "26°C";
        String icon = "⛅";
        String condition = "多雲舒適";
        String rainChance = "20%";
        String humidity = "65%";
        String uv = "中量 (5)";
        String advice = "田間例行管理，落實定時修剪與水肥調控。";

        switch (scenario) {
            case "sunny":
                if (isSouth) {
                    temp = "33°C"; icon = "☀️"; condition = "南台灣艷陽晴朗"; rainChance = "10%"; humidity = "62%"; uv = "極高 (10)";
                } else if (isMountain) {
                    temp = "23°C"; icon = "🌤️"; condition = "高山晴朗清涼"; rainChance = "10%"; humidity = "58%"; uv = "過量 (9)";
                } else if (isNorthEast) {
                    temp = "29°C"; icon = "☀️"; condition = "陽光普照微風"; rainChance = "10%"; humidity = "64%"; uv = "高量 (8)";
                } else {
                    temp = "32°C"; icon = "☀️"; condition = "晴空萬里炎熱"; rainChance = "10%"; humidity = "56%"; uv = "過量 (9)";
                }
                if (isTea) advice = "微風涼爽適宜，利於人工採摘一心二葉，適合植保機執行超低空精準追肥巡查。";
                else if (isVeg) advice = "設施溫室雙層遮陽網展開，加強水簾通風降溫，持續精緻修葉採收直送冷鏈。";
                else advice = "果園外銷採收與套袋作業正常推進，正午加強果實遮陰防日燒，適時補充灌溉水。";
                break;

            case "shower":
                if (isNorthEast) {
                    temp = "24°C"; icon = "🌧️"; condition = "局部短暫對流陣雨"; rainChance = "70%"; humidity = "85%"; uv = "低量 (3)";
                } else if (isMountain) {
                    temp = "21°C"; icon = "🌦️"; condition = "山區午後雷陣雨"; rainChance = "60%"; humidity = "82%"; uv = "中量 (5)";
                } else {
                    temp = "30°C"; icon = "⛅"; condition = "晴午後局部對流"; rainChance = "35%"; humidity = "72%"; uv = "過量 (8)";
                }
                if (isTea) advice = "午後陣雨山區易起霧，採青作業提早收工，轉入室內浪菁萎凋與茶葉精緻分級。";
                else if (isVeg) advice = "溫室遮雨簾閉合，防潮排濕開啟，溫室內無風雨阻礙正常採收與水洗。";
                else advice = "田間搶收果品，注意降雨泥濘；若遇豪大雨全員專車轉移至冷鏈包裝線。";
                break;

            case "heavy_rain":
                temp = isMountain ? "18°C" : (isSouth ? "24°C" : "22°C");
                icon = "⛈️";
                condition = "滯留鋒面豪大暴雨";
                rainChance = "95%";
                humidity = "95%";
                uv = "微弱 (1)";
                advice = "田區泥濘安全停工！啟動暴雨避險機制，外展工班專車轉派至周邊冷鏈蔬果集貨包裝線，保全 100% 稼動率！";
                break;

            case "typhoon":
                temp = isMountain ? "20°C" : "25°C";
                icon = "🌀";
                condition = "暴風圈警戒強風驟雨";
                rainChance = "100%";
                humidity = "98%";
                uv = "微弱 (1)";
                advice = "🚨 防颱緊急搶收完成，設施溫網室壓條加固，抽水機待命，工班全員進駐室內集貨場避險包裝！";
                break;

            case "cold_wave":
                if (isMountain) {
                    temp = "5°C"; icon = "❄️"; condition = "高山急凍嚴寒霜害"; rainChance = "20%"; humidity = "65%"; uv = "低量 (3)";
                    advice = "高海拔茶園防霜風扇定時運轉，噴施葉面防凍保護劑，防嫩芽霜害凍死。";
                } else if (isNorthEast) {
                    temp = "9°C"; icon = "❄️"; condition = "寒流低溫特報"; rainChance = "25%"; humidity = "72%"; uv = "低量 (3)";
                    advice = "溫室雙層內膜密閉防寒，加開熱風機防凍傷；農工穿著防寒防水工作服作業。";
                } else {
                    temp = "13°C"; icon = "❄️"; condition = "多雲乾燥強冷空氣"; rainChance = "10%"; humidity = "52%"; uv = "中量 (5)";
                    advice = "果園迎風面架設防風網，傍晚加強灌溉提高土溫，預防冷風落花落果。";
                }
                break;

            case "cloudy":
            default:
                if (isSouth) {
                    temp = "30°C"; icon = "⛅"; condition = "多雲時晴微風"; rainChance = "20%"; humidity = "68%"; uv = "中量 (6)";
                } else if (isMountain) {
                    temp = "22°C"; icon = "⛅"; condition = "多雲涼爽適農作"; rainChance = "20%"; humidity = "65%"; uv = "中量 (5)";
                } else {
                    temp = "26°C"; icon = "⛅"; condition = "舒適多雲溫和"; rainChance = "20%"; humidity = "66%"; uv = "中量 (5)";
                }
                if (isTea) advice = "微風氣候極利於無人機巡檢噴施與精緻一心二葉採收，排程作業順暢。";
                else if (isVeg) advice = "溫室通風良好溫度適宜，進行吊蔓修葉、採收與冷鏈直配。";
                else advice = "果園採收與套袋作業正常推進，果實外觀品相優良，農工出勤滿載。";
                break;
        }

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("region", region);
        m.put("county", county);
        m.put("district", district);
        m.put("farmName", farmName);
        m.put("crop", crop);
        m.put("temp", temp);
        m.put("icon", icon);
        m.put("condition", condition);
        m.put("rainChance", rainChance);
        m.put("humidity", humidity);
        m.put("uv", uv);
        m.put("advice", advice);
        return m;
    }

    private List<Map<String, Object>> createScenarioRegions(String scenario) {
        List<Map<String, Object>> list = new ArrayList<>();
        switch (scenario) {
            case "sunny":
                list.add(createRegionMap("屏東枋山", "🥭 愛文芒果專區", "33°C", "☀️", "晴朗炎熱", "10%", "65%", "極高 (10)", "套袋疏果與外銷留蒂採收中，注意高溫遮蔭與適時補水。"));
                list.add(createRegionMap("桃園八德", "🥬 設施有機蔬菜", "29°C", "☀️", "陽光普照", "10%", "62%", "高量 (8)", "溫室雙層遮陽網展開，加強水簾通風降溫，持續精緻修葉採收。"));
                list.add(createRegionMap("宜蘭三星", "🌱 三星青蔥專班", "28°C", "☀️", "晴天微風", "15%", "70%", "高量 (7)", "青蔥生長旺盛，執行拔蔥、水洗分級，排程穩定順暢。"));
                list.add(createRegionMap("雲林元長", "🫑 設施彩椒專區", "32°C", "☀️", "晴空萬里", "10%", "58%", "過量 (9)", "溫室整枝落蔓，灌溉管路正常供水，預防日燒果。"));
                list.add(createRegionMap("新竹峨眉", "🍵 東方美人茶園", "30°C", "☀️", "晴朗微風", "5%", "55%", "高量 (8)", "微風 1 級非常適合無人機植保代噴與精緻一心二葉採收。"));
                break;

            case "shower":
                list.add(createRegionMap("屏東枋山", "🥭 愛文芒果專區", "31°C", "☀️", "晴午後短暫對流", "45%", "72%", "極高 (9)", "套袋疏果中，若遇雷陣雨即刻專車轉移至屏南冷鏈物流園區。"));
                list.add(createRegionMap("桃園八德", "🥬 設施有機蔬菜", "25°C", "🌧️", "陣雨轉多雲", "60%", "82%", "低量 (3)", "溫室遮雨簾閉合，防潮排濕開啟，溫室內無風雨阻礙正常採收。"));
                list.add(createRegionMap("宜蘭三星", "🌱 三星青蔥專班", "24°C", "🌧️", "陰短暫雷陣雨", "75%", "88%", "低量 (2)", "田間拔蔥提早 1 小時收工，全員轉入農會青蔥冷鏈場操作洗蔥機。"));
                list.add(createRegionMap("雲林元長", "🫑 設施彩椒專區", "29°C", "⛅", "晴時多雲", "20%", "66%", "過量 (8)", "溫室整枝摘心，注意防中暑補水與高溫通風。"));
                list.add(createRegionMap("新竹峨眉", "🍵 東方美人茶園", "26°C", "⛅", "多雲偶陣雨", "35%", "70%", "中量 (5)", "暫緩無人機噴灑作業，轉為室內茶葉分級挑枝。"));
                break;

            case "heavy_rain":
                list.add(createRegionMap("屏東枋山", "🥭 愛文芒果專區", "24°C", "⛈️", "大雨滂沱", "90%", "92%", "微弱 (1)", "田區泥濘停工，工班全員專車進駐屏南冷鏈預冷分級場清洗包裝。"));
                list.add(createRegionMap("桃園八德", "🥬 設施有機蔬菜", "22°C", "⛈️", "豪雨強降水", "95%", "95%", "微弱 (1)", "八德溫室外部排水啟動，工班全員轉入室內 B 棟冷鏈包裝線。"));
                list.add(createRegionMap("宜蘭三星", "🌱 三星青蔥專班", "21°C", "⛈️", "連續豪大雨", "95%", "96%", "微弱 (1)", "田區防汛排澇，全員移至三星冷鏈中心進行青蔥差壓預冷與封裝。"));
                list.add(createRegionMap("雲林元長", "🫑 設施彩椒專區", "23°C", "⛈️", "雷雨交加", "85%", "90%", "微弱 (1)", "巡檢溫室排水溝渠，工班轉移至西螺蔬果集貨冷藏中心包裝。"));
                list.add(createRegionMap("新竹峨眉", "🍵 東方美人茶園", "22°C", "⛈️", "豪雨雷鳴", "90%", "92%", "微弱 (1)", "戶外全面暫停，機耕隊轉入資材維護所執行無人機深層保養。"));
                break;

            case "typhoon":
                list.add(createRegionMap("屏東枋山", "🥭 愛文芒果專區", "26°C", "🌀", "暴風圈籠罩", "100%", "98%", "微弱 (1)", "🚨 外銷芒果防颱緊急搶收完成，全員進駐屏南冷鏈中心全力裝箱。"));
                list.add(createRegionMap("桃園八德", "🥬 設施有機蔬菜", "24°C", "🌀", "強風驟雨", "95%", "96%", "微弱 (1)", "溫室壓條加固防風網，排水抽水機待命，採收蔬菜直送冷鏈庫。"));
                list.add(createRegionMap("宜蘭三星", "🌱 三星青蔥專班", "23°C", "🌀", "狂風暴雨", "100%", "99%", "微弱 (1)", "青蔥搶收完成避免折損，全員集貨場通宵進行截切與冷凍保溫。"));
                list.add(createRegionMap("雲林元長", "🫑 設施彩椒專區", "25°C", "🌀", "豪雨強陣風", "90%", "95%", "微弱 (1)", "檢查溫室捲揚防強風灌入，工班全員至集貨場支援備援出貨。"));
                list.add(createRegionMap("新竹峨眉", "🍵 東方美人茶園", "24°C", "🌀", "豪大雨警戒", "95%", "96%", "微弱 (1)", "茶園邊坡排水防塌，機具機耕設備進庫防颱固定。"));
                break;

            case "cold_wave":
                list.add(createRegionMap("屏東枋山", "🥭 愛文芒果專區", "15°C", "❄️", "晴冷乾燥", "10%", "50%", "高量 (7)", "果園迎風面設立防風網，灌溉調節田間地溫，預防冷風落花落果。"));
                list.add(createRegionMap("桃園八德", "🥬 設施有機蔬菜", "9°C", "❄️", "寒風刺骨", "20%", "60%", "低量 (3)", "溫室雙層內膜密閉保溫，必要時開啟加溫熱風機，防霜害凍傷。"));
                list.add(createRegionMap("宜蘭三星", "🌱 三星青蔥專班", "10°C", "❄️", "陰冷低溫", "30%", "75%", "低量 (2)", "洗蔥水線加裝回溫裝置，農工著防寒保暖防水衣物作業。"));
                list.add(createRegionMap("雲林元長", "🫑 設施彩椒專區", "11°C", "❄️", "多雲寒冷", "15%", "55%", "中量 (5)", "設施溫室保溫防寒，午後提早關閉通風口蓄積日照熱能。"));
                list.add(createRegionMap("新竹峨眉", "🍵 東方美人茶園", "8°C", "❄️", "低溫嚴寒", "10%", "50%", "中量 (5)", "茶園噴施防凍葉面保護劑，高海拔茶區防霜風扇定時運轉。"));
                break;

            case "cloudy":
            default:
                list.add(createRegionMap("屏東枋山", "🥭 愛文芒果專區", "30°C", "⛅", "多雲時晴", "20%", "68%", "中量 (6)", "果園採收與套袋作業正常推進，果實外觀品相優良。"));
                list.add(createRegionMap("桃園八德", "🥬 設施有機蔬菜", "26°C", "⛅", "多雲舒適", "20%", "68%", "中量 (5)", "溫室通風良好，持續精緻修葉分級與生鮮冷鏈直配。"));
                list.add(createRegionMap("宜蘭三星", "🌱 三星青蔥專班", "25°C", "⛅", "陰天多雲", "25%", "75%", "低量 (4)", "氣溫涼爽極利拔蔥，洗蔥機水線高效作業中。"));
                list.add(createRegionMap("雲林元長", "🫑 設施彩椒專區", "28°C", "⛅", "多雲微風", "15%", "65%", "中量 (6)", "設施內溫度適宜，進行吊蔓修葉與病蟲害巡視。"));
                list.add(createRegionMap("新竹峨眉", "🍵 東方美人茶園", "26°C", "⛅", "多雲風輕", "15%", "65%", "中量 (5)", "適合大疆 T50 執行大面積超低空精準施肥作業。"));
                break;
        }
        return list;
    }

    private Map<String, Object> createRegionMap(String region, String crop, String temp, String icon,
                                                String condition, String rainChance, String humidity,
                                                String uv, String advice) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("region", region);
        m.put("crop", crop);
        m.put("temp", temp);
        m.put("icon", icon);
        m.put("condition", condition);
        m.put("rainChance", rainChance);
        m.put("humidity", humidity);
        m.put("uv", uv);
        m.put("advice", advice);
        return m;
    }
}
