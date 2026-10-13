package com.agridispatch;

import com.agridispatch.entity.DispatchTeam;
import com.agridispatch.entity.Order;
import com.agridispatch.entity.WeatherState;
import com.agridispatch.repository.DispatchTeamRepository;
import com.agridispatch.repository.LineUserRepository;
import com.agridispatch.repository.OrderRepository;
import com.agridispatch.repository.WeatherStateRepository;
import com.agridispatch.service.DispatchService;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 天候戰情切換（科技後台）：POST /api/dispatch/weather-scenario、/weather-switch、PUT /weather-regions，
 * 以及切換後對工班狀態（田間／室內避險）、weather_states 持久化、總覽與 LINE 通知（受影響訂單）的影響。
 * GET /api/dispatch/weather-state、/overview 為公開端點；POST/PUT /api/dispatch/** 僅 admin。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:agri_weather_scenario_test;DB_CLOSE_DELAY=-1",
        "agri.demo.enabled=true",
        "agri.security.token-secret=unit-test-token-secret-0123456789abcdef",
        "agri.line.notify.async=false",
        "agri.line.notify.worker-hours=00:00-24:00"
})
@AutoConfigureMockMvc
@Import(RecordingLineBotClient.Config.class)
class WeatherScenarioApiTest extends ApiTestSupport {

    private static final String SCENARIO = "/api/dispatch/weather-scenario";
    private static final String VN_UID = "U" + "e".repeat(31) + "1";
    private static final String FARMER_UID = "U" + "9".repeat(31) + "1";

    @Autowired DispatchTeamRepository teamRepository;
    @Autowired WeatherStateRepository weatherStateRepository;
    @Autowired OrderRepository orderRepository;
    @Autowired LineUserRepository lineUserRepository;
    @Autowired DispatchService dispatchService;
    @Autowired RecordingLineBotClient bot;

    private String admin;

    @BeforeEach
    void setUp() throws Exception {
        admin = login("admin_tech", "admin888");
    }

    private JsonNode apply(String scenarioId) throws Exception {
        MvcResult r = call("POST", SCENARIO, admin, Map.of("scenarioId", scenarioId));
        assertEquals(200, r.getResponse().getStatus(), r.getResponse().getContentAsString());
        assertTrue(json(r).get("message").asText().contains(scenarioId));
        return json(r).get("data");
    }

    private JsonNode weatherState() throws Exception {
        MvcResult r = call("GET", "/api/dispatch/weather-state", null, null);
        assertEquals(200, r.getResponse().getStatus());
        return json(r).get("data");
    }

    /** 各情境的工班狀態規則：回傳在室內避險（hedged_indoor）的工班代號 */
    private static Set<String> indoorTeams(String scenario, List<String> allTeams) {
        return switch (scenario) {
            case "sunny", "cloudy", "cold_wave" -> Set.of("team_d");
            case "shower" -> Set.of("team_b", "team_d");
            case "heavy_rain", "typhoon" -> Set.copyOf(allTeams);
            default -> throw new IllegalArgumentException(scenario);
        };
    }

    @Test
    void scenarioSwitchIsAdminOnly() throws Exception {
        String farmer = login("farmer", "123456");
        String worker = login("W001", "123456");
        assertEquals(401, call("POST", SCENARIO, null, Map.of("scenarioId", "typhoon")).getResponse().getStatus());
        assertEquals(403, call("POST", SCENARIO, farmer, Map.of("scenarioId", "typhoon")).getResponse().getStatus());
        assertEquals(403, call("POST", SCENARIO, worker, Map.of("scenarioId", "typhoon")).getResponse().getStatus());
        assertEquals(401, call("POST", "/api/dispatch/weather-switch", null, null).getResponse().getStatus());
        assertEquals(403, call("POST", "/api/dispatch/weather-switch", farmer, Map.of("isRainMode", true)).getResponse().getStatus());
        assertEquals(403, call("PUT", "/api/dispatch/weather-regions", worker, List.of(Map.of("region", "x"))).getResponse().getStatus());
        // 讀取天候狀態公開
        assertEquals(200, call("GET", "/api/dispatch/weather-state", null, null).getResponse().getStatus());
        assertEquals(200, call("GET", "/api/dispatch/overview", null, null).getResponse().getStatus());
    }

    @Test
    void rejectedSwitchDoesNotChangeState() throws Exception {
        apply("cloudy");
        String farmer = login("farmer", "123456");
        call("POST", SCENARIO, farmer, Map.of("scenarioId", "typhoon"));
        assertEquals("cloudy", weatherState().get("scenarioId").asText());

        MvcResult bad = call("POST", SCENARIO, admin, Map.of("scenarioId", "tornado"));
        assertEquals(400, bad.getResponse().getStatus());
        assertTrue(json(bad).get("error").asText().contains("tornado"));
        assertEquals("cloudy", weatherState().get("scenarioId").asText());
        assertEquals("cloudy", weatherStateRepository.findById("WS-ACTIVE").orElseThrow().getScenarioId());
    }

    @Test
    void eachScenarioSetsStateTeamsAndPersists() throws Exception {
        List<String> allTeams = teamRepository.findAll().stream().map(DispatchTeam::getId).toList();
        assertTrue(allTeams.containsAll(List.of("team_a", "team_b", "team_c", "team_d")), allTeams.toString());

        record Expect(String alert, boolean rain, int revenue, int capacity) {}
        Map<String, Expect> expect = Map.of(
                "sunny", new Expect("normal", false, 62500, 160),
                "cloudy", new Expect("normal", false, 62500, 160),
                "shower", new Expect("info", false, 62500, 160),
                "heavy_rain", new Expect("warning", true, 62500, 160),
                "typhoon", new Expect("danger", true, 85000, 192),
                "cold_wave", new Expect("warning", false, 62500, 160));

        for (String s : List.of("sunny", "shower", "heavy_rain", "typhoon", "cold_wave", "cloudy")) {
            Expect e = expect.get(s);
            JsonNode d = apply(s);
            assertEquals(s, d.get("scenarioId").asText());
            assertFalse(d.get("scenarioName").asText().isBlank());
            assertEquals(e.alert(), d.get("alertLevel").asText(), s);
            assertEquals(e.rain(), d.get("isRainMode").asBoolean(), s);
            assertEquals(e.revenue(), d.get("securedRevenue").asInt(), s);
            assertEquals(e.capacity(), d.get("dailyCapacityHrs").asInt(), s);
            assertTrue(d.get("regionalWeather").size() > 0);

            // 工班狀態：回傳內容與資料庫一致
            Set<String> indoor = indoorTeams(s, allTeams);
            for (JsonNode t : d.get("teams")) {
                assertEquals(indoor.contains(t.get("id").asText()) ? "hedged_indoor" : "active_field", t.get("status").asText(), s + " " + t.get("id"));
            }
            for (DispatchTeam t : teamRepository.findAll()) {
                assertEquals(indoor.contains(t.getId()) ? "hedged_indoor" : "active_field", t.getStatus(), s + " " + t.getId());
            }

            // 公開讀取的天候狀態
            JsonNode ws = weatherState();
            assertEquals(s, ws.get("scenarioId").asText());
            assertEquals(e.rain(), ws.get("isRainMode").asBoolean());
            assertEquals(e.alert(), ws.get("alertLevel").asText());
            assertEquals(d.get("weatherMessage").asText(), ws.get("weatherMessage").asText());

            // 持久化到 weather_states（WS-ACTIVE）
            WeatherState row = weatherStateRepository.findById("WS-ACTIVE").orElseThrow();
            assertEquals(s, row.getScenarioId());
            assertEquals(e.rain() ? "Y" : "N", row.getIsRainMode());
            assertEquals(e.alert(), row.getAlertLevel());
            assertEquals(e.revenue(), row.getSecuredRevenue());
            assertEquals(e.capacity(), row.getDailyCapacityHrs());
            assertNotNull(row.getRegionalJson());
        }
    }

    @Test
    void overviewHidesRevenueAndLeaderPhoneFromPublic() throws Exception {
        apply("typhoon");
        JsonNode pub = json(call("GET", "/api/dispatch/overview", null, null)).get("data");
        assertEquals("typhoon", pub.get("scenarioId").asText());
        assertTrue(pub.get("isRainMode").asBoolean());
        assertFalse(pub.has("securedRevenue"));
        for (JsonNode t : pub.get("teams")) {
            assertFalse(t.has("leaderPhone"), t.toString());
            assertFalse(t.has("driver"), t.toString());
            assertEquals("hedged_indoor", t.get("status").asText());
        }
        JsonNode farmerView = json(call("GET", "/api/dispatch/overview", login("farmer", "123456"), null)).get("data");
        assertFalse(farmerView.has("securedRevenue"));

        JsonNode adm = json(call("GET", "/api/dispatch/overview", admin, null)).get("data");
        assertEquals(85000, adm.get("securedRevenue").asInt());
        assertEquals(192, adm.get("dailyCapacityHrs").asInt());
        assertTrue(adm.get("teams").get(0).has("leaderPhone"));
        apply("cloudy");
    }

    @Test
    void emptyBodyMeansSunnyAndRainSwitchToggles() throws Exception {
        apply("typhoon");
        MvcResult r = call("POST", SCENARIO, admin, null);
        assertEquals(200, r.getResponse().getStatus());
        assertEquals("sunny", json(r).get("data").get("scenarioId").asText());

        String sw = "/api/dispatch/weather-switch";
        // 沒帶 isRainMode：切換晴雨
        JsonNode d = json(call("POST", sw, admin, null)).get("data");
        assertEquals("heavy_rain", d.get("scenarioId").asText());
        assertTrue(d.get("isRainMode").asBoolean());
        d = json(call("POST", sw, admin, Map.of())).get("data");
        assertEquals("sunny", d.get("scenarioId").asText());
        assertFalse(d.get("isRainMode").asBoolean());
        // 指定 isRainMode（布林或字串）
        d = json(call("POST", sw, admin, Map.of("isRainMode", true))).get("data");
        assertEquals("heavy_rain", d.get("scenarioId").asText());
        d = json(call("POST", sw, admin, Map.of("isRainMode", true))).get("data");
        assertEquals("heavy_rain", d.get("scenarioId").asText(), "重複指定雨天維持雨天");
        d = json(call("POST", sw, admin, Map.of("isRainMode", "false"))).get("data");
        assertEquals("sunny", d.get("scenarioId").asText());
        assertEquals("sunny", weatherStateRepository.findById("WS-ACTIVE").orElseThrow().getScenarioId());
    }

    @Test
    void customRegionsFollowLaterScenarioSwitches() throws Exception {
        List<Map<String, Object>> regions = List.of(
                Map.of("region", "屏東枋山", "county", "屏東縣", "district", "枋山鄉", "farmName", "陳家芒果園", "crop", "🥭 愛文芒果"),
                Map.of("region", "宜蘭三星", "county", "宜蘭縣", "district", "三星鄉", "farmName", "三星蔥田", "crop", "🌱 青蔥"));
        MvcResult r = call("PUT", "/api/dispatch/weather-regions", admin, regions);
        assertEquals(200, r.getResponse().getStatus(), r.getResponse().getContentAsString());
        assertEquals(2, json(r).get("data").size());
        assertEquals(2, weatherState().get("regionalWeather").size());
        assertTrue(weatherStateRepository.findById("WS-ACTIVE").orElseThrow().getRegionalJson().contains("陳家芒果園"));

        // 切換情境後，依自訂農場地點推估天氣（南部晴天 33°C、東北部陣雨 70%）
        JsonNode sunny = apply("sunny").get("regionalWeather");
        assertEquals(2, sunny.size());
        assertEquals("陳家芒果園", sunny.get(0).get("farmName").asText());
        assertEquals("33°C", sunny.get(0).get("temp").asText());
        assertEquals("南台灣艷陽晴朗", sunny.get(0).get("condition").asText());
        JsonNode shower = apply("shower").get("regionalWeather");
        assertEquals("70%", shower.get(1).get("rainChance").asText());
        assertEquals("局部短暫對流陣雨", shower.get(1).get("condition").asText());
        JsonNode rain = apply("heavy_rain").get("regionalWeather");
        assertEquals("24°C", rain.get(0).get("temp").asText());
        assertEquals("95%", rain.get(0).get("rainChance").asText());
        assertTrue(rain.get(1).get("advice").asText().contains("避險"));

        // 空清單不覆蓋；超過 50 筆 400
        MvcResult empty = call("PUT", "/api/dispatch/weather-regions", admin, List.of());
        assertEquals(2, json(empty).get("data").size());
        List<Map<String, Object>> tooMany = new ArrayList<>();
        for (int i = 0; i < 51; i++) tooMany.add(Map.of("region", "R" + i));
        assertEquals(400, call("PUT", "/api/dispatch/weather-regions", admin, tooMany).getResponse().getStatus());
        assertEquals(2, weatherState().get("regionalWeather").size());
        apply("cloudy");
    }

    /** 重啟時由 weather_states 讀回天候狀態（DataInitializer → restoreStateFromDb） */
    @Test
    void stateIsRestoredFromDatabase() throws Exception {
        apply("typhoon");
        WeatherState row = weatherStateRepository.findById("WS-ACTIVE").orElseThrow();
        row.setScenarioId("cold_wave");
        row.setIsRainMode("N");
        row.setAlertLevel("warning");
        row.setCurrentWeather("寒流測試");
        row.setMessage("由資料庫讀回");
        row.setRegionalJson("[{\"region\":\"新竹峨眉\",\"crop\":\"茶\",\"temp\":\"8°C\"}]");
        weatherStateRepository.save(row);

        dispatchService.restoreStateFromDb();
        JsonNode ws = weatherState();
        assertEquals("cold_wave", ws.get("scenarioId").asText());
        assertFalse(ws.get("isRainMode").asBoolean());
        assertEquals("warning", ws.get("alertLevel").asText());
        assertEquals("寒流測試", ws.get("currentWeather").asText());
        assertEquals("由資料庫讀回", ws.get("weatherMessage").asText());
        assertEquals(1, ws.get("regionalWeather").size());
        assertEquals("新竹峨眉", ws.get("regionalWeather").get(0).get("region").asText());

        // regional_json 壞掉時沿用原本的產區資料，其他欄位照常讀回
        row.setRegionalJson("{not json");
        row.setMessage("第二次讀回");
        weatherStateRepository.save(row);
        dispatchService.restoreStateFromDb();
        ws = weatherState();
        assertEquals("第二次讀回", ws.get("weatherMessage").asText());
        assertEquals(1, ws.get("regionalWeather").size());
        apply("cloudy");
    }

    /**
     * 切換後對訂單與派工的影響：切到警戒（warning／danger）時，今天施工中的訂單
     * 農場主收「訂單受影響」、出工名單依所屬工班是否改到室內收「集合／室內作業」；一般天候不通知。
     */
    @Test
    void alertSwitchNotifiesAffectedOrderWithIndoorFlagFromTeamStatus() throws Exception {
        lineUserRepository.deleteAll();
        assertEquals(200, call("POST", "/api/line/bind", login("farmer", "123456"),
                Map.of("channel", "farmer", "idToken", "mock:" + FARMER_UID)).getResponse().getStatus());
        assertEquals(200, call("POST", "/api/line/bind", login("W001", "123456"),
                Map.of("channel", "worker", "idToken", "mock:" + VN_UID)).getResponse().getStatus());

        // 只留 ORD-2026-001 一張今天施工中的訂單（工班 A、出工名單 W001）；其他進行中的訂單暫時改為已完成
        Map<String, String> original = new HashMap<>();
        for (Order o : orderRepository.findAll()) {
            if (!"ORD-2026-001".equals(o.getId()) && Set.of("confirmed", "in_progress").contains(o.getStatus())) {
                original.put(o.getId(), o.getStatus());
                o.setStatus("completed");
                orderRepository.save(o);
            }
        }
        Order o = orderRepository.findById("ORD-2026-001").orElseThrow();
        o.setFarmerId("F001");
        o.setStatus("in_progress");
        o.setStartDate(LocalDate.now());
        o.setEndDate(LocalDate.now());
        o.setAssignedTeamId("team_a");
        o.setCrewWorkerIds("W001");
        orderRepository.save(o);

        try {
            apply("sunny");
            bot.clear();

            // 寒流（warning）：工班 A 仍在田間 → indoor=false
            apply("cold_wave");
            List<RecordingLineBotClient.Call> farmerCalls = bot.of("farmer", "weather_farmer");
            assertEquals(1, farmerCalls.size());
            assertEquals(List.of("ORD-2026-001"), farmerCalls.get(0).params().get("orders"));
            assertEquals(Map.of(FARMER_UID, "zh"), bot.recipients("farmer", "weather_farmer"));
            List<RecordingLineBotClient.Call> crew = bot.of("worker", "weather_worker");
            assertEquals(1, crew.size());
            assertEquals(false, crew.get(0).params().get("indoor"));
            assertEquals("vi", bot.recipients("worker", "weather_worker").get(VN_UID));
            assertEquals(json(call("GET", "/api/dispatch/weather-state", null, null)).get("data").get("currentWeather").asText(),
                    crew.get(0).params().get("weather"));

            // 寒流 → 颱風（danger）：工班 A 改室內避險 → indoor=true
            bot.clear();
            apply("typhoon");
            crew = bot.of("worker", "weather_worker");
            assertEquals(1, crew.size());
            assertEquals(true, crew.get(0).params().get("indoor"));
            assertEquals(1, bot.of("farmer", "weather_farmer").size());

            // 切回一般天候（normal／info）不通知；雨天開關切到暴雨（warning）會通知
            bot.clear();
            apply("shower");
            apply("cloudy");
            assertTrue(bot.calls.isEmpty(), bot.calls.toString());
            call("POST", "/api/dispatch/weather-switch", admin, Map.of("isRainMode", true));
            assertEquals(1, bot.of("farmer", "weather_farmer").size());
            assertEquals(true, bot.of("worker", "weather_worker").get(0).params().get("indoor"));

            // 訂單不在今天（已排定但工期在明天）：警戒也不通知
            apply("sunny");
            o.setStatus("confirmed");
            o.setStartDate(LocalDate.now().plusDays(1));
            o.setEndDate(LocalDate.now().plusDays(1));
            orderRepository.save(o);
            bot.clear();
            apply("typhoon");
            assertTrue(bot.calls.isEmpty(), bot.calls.toString());
        } finally {
            apply("cloudy");
            original.forEach((id, st) -> orderRepository.findById(id).ifPresent(x -> {
                x.setStatus(st);
                orderRepository.save(x);
            }));
        }
    }
}
