package com.agridispatch.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 中央氣象署 F-D0047-093 回應解析（範例依氣象署 2024 年後新版欄位：Locations／Location／WeatherElement／Time／ElementValue）。
 * 【待確認】取得授權碼後以實際回應再核對一次欄位名稱。
 */
class WeatherServiceTest {

    static final String SAMPLE = """
            {"success":"true","records":{"Locations":[{
              "DatasetDescription":"臺灣各鄉鎮市區預報資料","LocationsName":"宜蘭縣","Dataid":"D0047-001",
              "Location":[{"LocationName":"三星鄉","Geocode":"10002080","Latitude":"24.664","Longitude":"121.645",
                "WeatherElement":[
                  {"ElementName":"溫度","Time":[
                    {"DataTime":"2026-10-12T09:00:00+08:00","ElementValue":[{"Temperature":"26"}]},
                    {"DataTime":"2026-10-12T12:00:00+08:00","ElementValue":[{"Temperature":"29"}]},
                    {"DataTime":"2026-10-12T21:00:00+08:00","ElementValue":[{"Temperature":"21"}]},
                    {"DataTime":"2026-10-13T12:00:00+08:00","ElementValue":[{"Temperature":"27"}]},
                    {"DataTime":"2026-10-13T03:00:00+08:00","ElementValue":[{"Temperature":"19"}]}]},
                  {"ElementName":"體感溫度","Time":[
                    {"DataTime":"2026-10-12T09:00:00+08:00","ElementValue":[{"ApparentTemperature":"28"}]}]},
                  {"ElementName":"相對濕度","Time":[
                    {"DataTime":"2026-10-12T09:00:00+08:00","ElementValue":[{"RelativeHumidity":"75"}]}]},
                  {"ElementName":"風速","Time":[
                    {"DataTime":"2026-10-12T09:00:00+08:00","ElementValue":[{"WindSpeed":"3","BeaufortScale":"2"}]},
                    {"DataTime":"2026-10-12T15:00:00+08:00","ElementValue":[{"WindSpeed":"9","BeaufortScale":"5"}]}]},
                  {"ElementName":"3小時降雨機率","Time":[
                    {"StartTime":"2026-10-12T09:00:00+08:00","EndTime":"2026-10-12T12:00:00+08:00","ElementValue":[{"ProbabilityOfPrecipitation":"20"}]},
                    {"StartTime":"2026-10-12T15:00:00+08:00","EndTime":"2026-10-12T18:00:00+08:00","ElementValue":[{"ProbabilityOfPrecipitation":"70"}]},
                    {"StartTime":"2026-10-13T12:00:00+08:00","EndTime":"2026-10-13T15:00:00+08:00","ElementValue":[{"ProbabilityOfPrecipitation":"-"}]},
                    {"StartTime":"2026-10-13T15:00:00+08:00","EndTime":"2026-10-13T18:00:00+08:00","ElementValue":[{"ProbabilityOfPrecipitation":"40"}]}]},
                  {"ElementName":"天氣現象","Time":[
                    {"StartTime":"2026-10-12T09:00:00+08:00","EndTime":"2026-10-12T12:00:00+08:00","ElementValue":[{"Weather":"多雲","WeatherCode":"04"}]},
                    {"StartTime":"2026-10-12T15:00:00+08:00","EndTime":"2026-10-12T18:00:00+08:00","ElementValue":[{"Weather":"午後短暫雷陣雨","WeatherCode":"15"}]},
                    {"StartTime":"2026-10-13T12:00:00+08:00","EndTime":"2026-10-13T15:00:00+08:00","ElementValue":[{"Weather":"晴時多雲","WeatherCode":"02"}]}]}
                ]}]}]}}
            """;

    @Test
    void parsesTownForecastIntoDashboardShape() throws Exception {
        List<Map<String, Object>> list = WeatherService.parse(new ObjectMapper().readTree(SAMPLE),
                LocalDateTime.of(2026, 10, 12, 10, 30));
        assertEquals(1, list.size());
        Map<String, Object> w = list.get(0);
        assertEquals("宜蘭縣", w.get("county"));
        assertEquals("三星鄉", w.get("town"));
        assertEquals(24.664, (Double) w.get("lat"), 1e-9);
        assertEquals(26.0, w.get("temp"));            // 涵蓋 10:30 的是 09:00 那筆
        assertEquals(28.0, w.get("feels"));
        assertEquals(75.0, w.get("humidity"));
        assertEquals(3.0, w.get("wind"));
        assertEquals(9.0, w.get("windMax"));
        assertEquals("多雲", w.get("text"));
        assertEquals(true, w.get("thunder"));          // 今天下午預報雷陣雨
        assertEquals(29.0, w.get("tMax"));
        assertEquals(21.0, w.get("tMin"));
        assertEquals(70.0, w.get("rainProb"));
        assertNull(w.get("gust"));
        assertNull(w.get("uv"));
        @SuppressWarnings("unchecked")
        Map<String, Object> t = (Map<String, Object>) w.get("tomorrow");
        assertEquals("晴時多雲", t.get("text"));
        assertEquals(27.0, t.get("tMax"));
        assertEquals(19.0, t.get("tMin"));
        assertEquals(40.0, t.get("rainProb"));        // "-" 視為無資料
    }

    /** 2026-10-12 實際打 F-D0047-093（宜蘭縣宜蘭市）的回應：溫度／濕度逐時、風速逐 3 小時，即時值要取當下那一小時 */
    @Test
    void parsesRealCwaResponseWithHourlySeries() throws Exception {
        String real = new String(getClass().getResourceAsStream("/cwa-f-d0047-093-sample.json").readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        List<Map<String, Object>> list = WeatherService.parse(new ObjectMapper().readTree(real),
                LocalDateTime.of(2026, 10, 12, 13, 30));
        assertEquals(1, list.size());
        Map<String, Object> w = list.get(0);
        assertEquals("宜蘭縣", w.get("county"));
        assertEquals("宜蘭市", w.get("town"));
        assertEquals("2026-10-12T13:00", w.get("time"));
        assertEquals(72.0, w.get("humidity"));       // 13:00 那一筆，不是 12:00 的 71
        assertEquals(3.0, w.get("wind"));
        assertNotNull(w.get("tMax"));
        assertNotNull(w.get("rainProb"));
        assertNotEquals("—", w.get("text"));
        @SuppressWarnings("unchecked")
        Map<String, Object> t = (Map<String, Object>) w.get("tomorrow");
        assertNotNull(t.get("tMax"));
        assertNotEquals("—", t.get("text"));
    }

    /** 2026-10-12 實際打一週預報 F-D0047-003（宜蘭市）紫外線指數：今日 12–18 時 7 高量級、明日 8 過量級 */
    @Test
    void mergesUvIndexFromWeeklyForecast() throws Exception {
        String main = new String(getClass().getResourceAsStream("/cwa-f-d0047-093-sample.json").readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        String week = new String(getClass().getResourceAsStream("/cwa-f-d0047-093-uv-sample.json").readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        ObjectMapper om = new ObjectMapper();
        List<Map<String, Object>> list = WeatherService.parse(om.readTree(main), LocalDateTime.of(2026, 10, 12, 13, 30));
        WeatherService.mergeUv(list, WeatherService.parseUv(om.readTree(week)), java.time.LocalDate.of(2026, 10, 12));
        Map<String, Object> w = list.get(0);
        assertEquals(7.0, w.get("uv"));
        assertEquals("高量級", w.get("uvLevel"));
        @SuppressWarnings("unchecked")
        Map<String, Object> t = (Map<String, Object>) w.get("tomorrow");
        assertEquals(8.0, t.get("uv"));
        assertEquals("過量級", t.get("uvLevel"));
    }

    /** 2026-10-12 實際打 O-A0001-001／O-A0002-001 裁切：陣風 -99 為缺測要略過；雨量三個累積值都讀得到 */
    @Test
    void parsesRealStationResponses() throws Exception {
        ObjectMapper om = new ObjectMapper();
        String a = new String(getClass().getResourceAsStream("/cwa-o-a0001-001-sample.json").readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        String r = new String(getClass().getResourceAsStream("/cwa-o-a0002-001-sample.json").readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        List<WeatherService.Station> gust = WeatherService.parseStations(om.readTree(a), "gust");
        assertEquals(2, gust.size());                       // 宜蘭站 -99 被略過
        assertEquals("綠水", gust.get(0).name());
        assertEquals("花蓮縣", gust.get(0).county());
        assertEquals(9.5, gust.get(0).v().get("gust"));
        assertNotNull(gust.get(0).lat());
        List<WeatherService.Station> rain = WeatherService.parseStations(om.readTree(r), "rain");
        assertEquals(1, rain.size());
        assertEquals("宜蘭市", rain.get(0).town());
        assertEquals(0.0, rain.get(0).v().get("h24"));
        assertEquals(0.0, rain.get(0).v().get("h1"));
        assertEquals(0.0, rain.get(0).v().get("today"));
    }

    private static Map<String, Object> town(String county, String town, double lat, double lon) {
        Map<String, Object> w = new java.util.LinkedHashMap<>();
        w.put("county", county);
        w.put("town", town);
        w.put("lat", lat);
        w.put("lon", lon);
        return w;
    }

    private static WeatherService.Station st(String name, String county, String town, double lat, double lon, Map<String, Double> v) {
        return new WeatherService.Station(name, county, town, lat, lon, "2026-10-12T14:00:00+08:00", v);
    }

    @Test
    void mergesObservationsByTownThenNearestWithinRadius() {
        Map<String, Object> own = town("宜蘭縣", "三星鄉", 24.66, 121.65);
        Map<String, Object> near = town("宜蘭縣", "員山鄉", 24.74, 121.60);   // 鄉內無站，約 5 km 有站
        Map<String, Object> far = town("台東縣", "蘭嶼鄉", 22.04, 121.55);    // 10 km 內沒有站
        List<Map<String, Object>> list = List.of(own, near, far);
        List<WeatherService.Station> rain = List.of(
                st("三星A", "宜蘭縣", "三星鄉", 24.67, 121.66, Map.of("today", 12.0, "h1", 3.0, "h24", 30.0)),
                st("三星B", "宜蘭縣", "三星鄉", 24.65, 121.62, Map.of("today", 20.0, "h1", 1.0, "h24", 25.0)),
                st("大湖", "宜蘭縣", "宜蘭市", 24.75, 121.65, Map.of("today", 5.0, "h1", 0.5, "h24", 8.0)));
        WeatherService.mergeRain(list, rain);
        assertEquals(30.0, own.get("rainSum"));        // 鄉內各站 24 小時取最大
        assertEquals(20.0, own.get("rainToday"));      // 本日、1 小時各自取最大
        assertEquals(3.0, own.get("rain1h"));
        assertEquals("三星A", own.get("rainStation"));
        assertEquals(2, own.get("rainStations"));
        assertEquals(8.0, near.get("rainSum"));        // 鄰近站
        assertEquals("大湖", near.get("rainStation"));
        assertNull(far.get("rainSum"));                // 太遠不採用

        WeatherService.mergeGust(list, List.of(st("三星", "宜蘭縣", "三星鄉", 24.67, 121.66, Map.of("gust", 12.3))));
        assertEquals(12.3, own.get("gust"));
        assertNull(far.get("gust"));
    }

    @Test
    void weekDatasetIsThreeDayPlusTwo() {
        assertEquals("F-D0047-003", WeatherService.weekDataset("F-D0047-001"));
        assertEquals("F-D0047-087", WeatherService.weekDataset("F-D0047-085"));
    }

    @Test
    void acceptsLowercaseFieldNamesAndNormalizesTai() throws Exception {
        String lower = SAMPLE.replace("\"Locations\"", "\"locations\"").replace("\"LocationsName\":\"宜蘭縣\"", "\"locationsName\":\"臺東縣\"")
                .replace("\"LocationName\":\"三星鄉\"", "\"locationName\":\"臺東市\"");
        List<Map<String, Object>> list = WeatherService.parse(new ObjectMapper().readTree(lower),
                LocalDateTime.of(2026, 10, 12, 10, 30));
        assertEquals("台東縣", list.get(0).get("county"));
        assertEquals("台東市", list.get(0).get("town"));
    }

    @Test
    void countyDatasetsCoverAll22Counties() {
        assertEquals(22, WeatherService.COUNTY_DATASETS.size());
        assertEquals(22, WeatherService.COUNTY_DATASETS.values().stream().distinct().count());
    }
}
