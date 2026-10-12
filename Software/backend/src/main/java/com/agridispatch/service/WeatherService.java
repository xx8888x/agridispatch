package com.agridispatch.service;

import com.agridispatch.common.ApiException;
import com.agridispatch.config.AgriSettings;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 全台 368 鄉鎮市區天氣（中央氣象署開放資料，鄉鎮天氣預報逐 3 小時）。
 *
 * - 資料集：F-D0047-093（跨縣市擷取，一次最多 5 個縣市資料集）→ 22 縣市分 5 批並行抓取
 *   縣市資料集代碼見 COUNTY_DATASETS（F-D0047-001～085 單號＝各縣市未來 3 天）
 * - 授權碼：環境變數 CWA_API_KEY（只在後端使用，不給前端）；未設定時 503
 * - 快取：agri.weather.cache-minutes（預設 30 分鐘）；更新失敗時沿用上一份資料並標 stale
 * - 輸出欄位與前端 js/agri-weather.js 一致：取「最接近現在」的預報時段當即時值，今日／明日彙整高低溫與最高降雨機率
 * - 紫外線：另抓一週預報（各縣市 3 天資料集代碼 +2，元素「紫外線指數」，每天白天一筆），併入今日／明日；
 *   一週預報失敗不影響主資料（紫外線留 null）
 *   氣象署鄉鎮預報沒有陣風、雨量，這兩項回 null
 */
@Service
public class WeatherService {

    private static final Logger log = LoggerFactory.getLogger(WeatherService.class);
    private static final String API = "https://opendata.cwa.gov.tw/api/v1/rest/datastore/F-D0047-093";
    private static final ZoneId TPE = ZoneId.of("Asia/Taipei");
    private static final DateTimeFormatter Q_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final String ELEMENTS = "溫度,體感溫度,相對濕度,風速,3小時降雨機率,天氣現象";
    private static final String WEEK_ELEMENTS = "紫外線指數";

    /** 縣市 → 鄉鎮未來 3 天預報資料集（依氣象署 API 文件 /v1/rest/datastore/F-D0047-0xx 摘要對照） */
    static final Map<String, String> COUNTY_DATASETS = new LinkedHashMap<>();
    static {
        String[][] m = {
                {"宜蘭縣", "001"}, {"桃園市", "005"}, {"新竹縣", "009"}, {"苗栗縣", "013"}, {"彰化縣", "017"},
                {"南投縣", "021"}, {"雲林縣", "025"}, {"嘉義縣", "029"}, {"屏東縣", "033"}, {"台東縣", "037"},
                {"花蓮縣", "041"}, {"澎湖縣", "045"}, {"基隆市", "049"}, {"新竹市", "053"}, {"嘉義市", "057"},
                {"台北市", "061"}, {"高雄市", "065"}, {"新北市", "069"}, {"台中市", "073"}, {"台南市", "077"},
                {"連江縣", "081"}, {"金門縣", "085"}
        };
        for (String[] e : m) COUNTY_DATASETS.put(e[0], "F-D0047-" + e[1]);
    }

    private final AgriSettings settings;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private volatile Map<String, Object> cache;
    private volatile long cachedAt;

    public WeatherService(AgriSettings settings, ObjectMapper mapper) {
        this.settings = settings;
        this.mapper = mapper;
    }

    public boolean isConfigured() { return settings.getCwaApiKey() != null; }

    /** 全台鄉鎮天氣；force=true 略過快取（仍受最短 1 分鐘間隔保護，避免被連點打爆配額） */
    public synchronized Map<String, Object> getTowns(boolean force) {
        String key = settings.getCwaApiKey();
        if (key == null) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "中央氣象署授權碼未設定（CWA_API_KEY）");
        }
        long age = System.currentTimeMillis() - cachedAt;
        long ttl = settings.getWeatherCacheMinutes() * 60_000L;
        if (cache != null && (age < ttl && !force || age < 60_000L)) return cache;
        try {
            Map<String, Object> fresh = fetchAll(key);
            cache = fresh;
            cachedAt = System.currentTimeMillis();
            return fresh;
        } catch (Exception e) {
            log.warn("中央氣象署資料抓取失敗：{}", e.toString());
            if (cache != null) {
                Map<String, Object> stale = new LinkedHashMap<>(cache);
                stale.put("stale", true);
                return stale;
            }
            throw new ApiException(HttpStatus.BAD_GATEWAY, "中央氣象署資料暫時無法取得，請稍後再試");
        }
    }

    private Map<String, Object> fetchAll(String key) throws Exception {
        LocalDateTime now = LocalDateTime.now(TPE);
        String from = now.toLocalDate().atStartOfDay().format(Q_TIME);
        String to = now.toLocalDate().plusDays(2).atStartOfDay().format(Q_TIME);
        List<String> ids = new ArrayList<>(COUNTY_DATASETS.values());
        List<String> weekIds = ids.stream().map(WeatherService::weekDataset).toList();
        // 主資料與紫外線同時發出，各 5 批
        List<CompletableFuture<String>> calls = batches(key, ids, ELEMENTS, from, to);
        List<CompletableFuture<String>> uvCalls = batches(key, weekIds, WEEK_ELEMENTS, from, to);
        List<Map<String, Object>> list = new ArrayList<>();
        for (CompletableFuture<String> c : calls) {
            list.addAll(parse(mapper.readTree(c.get()), now));
        }
        if (list.isEmpty()) throw new IllegalStateException("CWA 回傳 0 筆鄉鎮資料");
        try {
            Map<String, Map<LocalDate, String[]>> uv = new LinkedHashMap<>();
            for (CompletableFuture<String> c : uvCalls) uv.putAll(parseUv(mapper.readTree(c.get())));
            mergeUv(list, uv, now.toLocalDate());
        } catch (Exception e) {
            log.warn("中央氣象署一週預報（紫外線）抓取失敗，紫外線留空：{}", e.toString());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("provider", "cwa");
        out.put("source", "中央氣象署 鄉鎮天氣預報（F-D0047-093）");
        out.put("fetchedAt", now.withNano(0).toString());
        out.put("stale", false);
        out.put("list", list);
        return out;
    }

    private List<CompletableFuture<String>> batches(String key, List<String> ids, String elements, String from, String to) {
        List<CompletableFuture<String>> calls = new ArrayList<>();
        for (int i = 0; i < ids.size(); i += 5) {
            String url = API + "?Authorization=" + enc(key)
                    + "&format=JSON"
                    + "&locationId=" + String.join(",", ids.subList(i, Math.min(i + 5, ids.size())))
                    + "&ElementName=" + enc(elements)
                    + "&timeFrom=" + from + "&timeTo=" + to;
            HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20)).GET().build();
            calls.add(http.sendAsync(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).thenApply(res -> {
                if (res.statusCode() != 200) {
                    // 回應內容不記錄（可能含授權碼回顯）
                    throw new IllegalStateException("CWA HTTP " + res.statusCode());
                }
                return res.body();
            }));
        }
        return calls;
    }

    /** 3 天資料集 → 同縣市一週資料集（F-D0047-001 → 003） */
    static String weekDataset(String threeDay) {
        int n = Integer.parseInt(threeDay.substring(threeDay.lastIndexOf('-') + 1));
        return String.format("F-D0047-%03d", n + 2);
    }

    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }

    // ------------------------------------------------------------------
    // 解析（套件可見，供單元測試以範例 JSON 驗證）
    // ------------------------------------------------------------------

    static List<Map<String, Object>> parse(JsonNode root, LocalDateTime now) {
        List<Map<String, Object>> out = new ArrayList<>();
        JsonNode records = f(root, "records");
        for (JsonNode group : iter(f(records, "Locations"))) {
            String county = normalize(f(group, "LocationsName").asText(""));
            for (JsonNode loc : iter(f(group, "Location"))) {
                Map<String, Object> w = town(county, loc, now);
                if (w != null) out.add(w);
            }
        }
        return out;
    }

    /** 一週預報的紫外線：縣市+鄉鎮 → 日期 → [指數, 等級]（每天白天一筆，以開始時間的日期歸日） */
    static Map<String, Map<LocalDate, String[]>> parseUv(JsonNode root) {
        Map<String, Map<LocalDate, String[]>> out = new LinkedHashMap<>();
        for (JsonNode group : iter(f(f(root, "records"), "Locations"))) {
            String county = normalize(f(group, "LocationsName").asText(""));
            for (JsonNode loc : iter(f(group, "Location"))) {
                String town = normalize(f(loc, "LocationName").asText(""));
                Map<LocalDate, String[]> days = new LinkedHashMap<>();
                for (JsonNode we : iter(f(loc, "WeatherElement"))) {
                    if (!"紫外線指數".equals(f(we, "ElementName").asText(""))) continue;
                    for (JsonNode t : iter(f(we, "Time"))) {
                        LocalDateTime start = time(f(t, "StartTime"), f(t, "DataTime"));
                        JsonNode v = first(f(t, "ElementValue"));
                        String idx = str(v, "UVIndex");
                        if (start != null && idx != null) {
                            days.put(start.toLocalDate(), new String[]{idx, str(v, "UVExposureLevel")});
                        }
                    }
                }
                if (!town.isEmpty() && !days.isEmpty()) out.put(county + town, days);
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    static void mergeUv(List<Map<String, Object>> list, Map<String, Map<LocalDate, String[]>> uv, LocalDate today) {
        for (Map<String, Object> w : list) {
            Map<LocalDate, String[]> days = uv.get(w.get("county") + "" + w.get("town"));
            if (days == null) continue;
            String[] d0 = days.get(today), d1 = days.get(today.plusDays(1));
            if (d0 != null) { w.put("uv", toNum(d0[0])); w.put("uvLevel", d0[1]); }
            Map<String, Object> t = (Map<String, Object>) w.get("tomorrow");
            if (d1 != null && t != null) { t.put("uv", toNum(d1[0])); t.put("uvLevel", d1[1]); }
        }
    }

    private static Double toNum(String s) {
        try { return Double.parseDouble(s); } catch (NumberFormatException e) { return null; }
    }

    private static Map<String, Object> town(String county, JsonNode loc, LocalDateTime now) {
        String town = normalize(f(loc, "LocationName").asText(""));
        if (town.isEmpty()) return null;
        Map<String, List<Point>> el = new LinkedHashMap<>();
        for (JsonNode we : iter(f(loc, "WeatherElement"))) {
            String name = f(we, "ElementName").asText("");
            List<Point> raw = new ArrayList<>();
            for (JsonNode t : iter(f(we, "Time"))) {
                LocalDateTime start = time(f(t, "StartTime"), f(t, "DataTime"));
                LocalDateTime end = time(f(t, "EndTime"), null);
                JsonNode v = first(f(t, "ElementValue"));
                if (start != null && v != null) raw.add(new Point(start, end, v));
            }
            // 時間點資料（DataTime，無 EndTime）的有效區間到下一個時間點為止：溫度逐時、風速逐 3 小時，間隔不固定
            raw.sort((a, b) -> a.start.compareTo(b.start));
            List<Point> pts = new ArrayList<>();
            for (int i = 0; i < raw.size(); i++) {
                Point p = raw.get(i);
                LocalDateTime end = p.end != null ? p.end
                        : (i + 1 < raw.size() ? raw.get(i + 1).start : p.start.plusHours(3));
                pts.add(new Point(p.start, end, p.v));
            }
            el.put(name, pts);
        }

        LocalDate today = now.toLocalDate();
        Map<String, Object> w = new LinkedHashMap<>();
        w.put("county", county);
        w.put("town", town);
        w.put("lat", dbl(f(loc, "Latitude")));
        w.put("lon", dbl(f(loc, "Longitude")));
        Point tNow = nearest(el.get("溫度"), now);
        w.put("time", tNow == null ? null : tNow.start.toString());
        w.put("temp", tNow == null ? null : num(tNow.v, "Temperature"));
        Point feels = nearest(el.get("體感溫度"), now);
        w.put("feels", feels == null ? null : num(feels.v, "ApparentTemperature"));
        Point rh = nearest(el.get("相對濕度"), now);
        w.put("humidity", rh == null ? null : num(rh.v, "RelativeHumidity"));
        Point ws = nearest(el.get("風速"), now);
        w.put("wind", ws == null ? null : num(ws.v, "WindSpeed"));
        w.put("beaufort", ws == null ? null : num(ws.v, "BeaufortScale"));
        w.put("windMax", max(el.get("風速"), today, "WindSpeed"));
        Point wx = nearest(el.get("天氣現象"), now);
        String text = wx == null ? null : str(wx.v, "Weather");
        w.put("text", text == null ? "—" : text);
        w.put("cwaCode", wx == null ? null : str(wx.v, "WeatherCode"));
        // 雷雨：現在到今天結束之間任一時段預報有雷
        w.put("thunder", (text != null && text.contains("雷")) || any(el.get("天氣現象"), now, "雷"));
        w.put("precip", null);
        w.put("rainSum", null);
        w.put("gust", null);
        w.put("gustMax", null);
        w.put("uv", null);
        w.put("uvLevel", null);
        w.put("tMax", max(el.get("溫度"), today, "Temperature"));
        w.put("tMin", min(el.get("溫度"), today, "Temperature"));
        w.put("rainProb", max(el.get("3小時降雨機率"), today, "ProbabilityOfPrecipitation"));

        LocalDate tmr = today.plusDays(1);
        Map<String, Object> tomorrow = new LinkedHashMap<>();
        Point noon = nearest(onDay(el.get("天氣現象"), tmr), tmr.atTime(12, 0));
        String tText = noon == null ? null : str(noon.v, "Weather");
        tomorrow.put("text", tText == null ? "—" : tText);
        tomorrow.put("tMax", max(el.get("溫度"), tmr, "Temperature"));
        tomorrow.put("tMin", min(el.get("溫度"), tmr, "Temperature"));
        tomorrow.put("rainProb", max(el.get("3小時降雨機率"), tmr, "ProbabilityOfPrecipitation"));
        w.put("tomorrow", tomorrow);
        return w;
    }

    private record Point(LocalDateTime start, LocalDateTime end, JsonNode v) {}

    /** 涵蓋 now 的時段；沒有就取開始時間最接近 now 的 */
    private static Point nearest(List<Point> pts, LocalDateTime now) {
        if (pts == null || pts.isEmpty()) return null;
        Point best = null;
        long bestDiff = Long.MAX_VALUE;
        for (Point p : pts) {
            if (!now.isBefore(p.start) && now.isBefore(p.end)) return p;
            long d = Math.abs(Duration.between(p.start, now).toMinutes());
            if (d < bestDiff) { bestDiff = d; best = p; }
        }
        return best;
    }

    private static List<Point> onDay(List<Point> pts, LocalDate day) {
        List<Point> r = new ArrayList<>();
        if (pts != null) for (Point p : pts) if (p.start.toLocalDate().equals(day)) r.add(p);
        return r;
    }

    private static Double max(List<Point> pts, LocalDate day, String field) {
        Double m = null;
        for (Point p : onDay(pts, day)) { Double v = num(p.v, field); if (v != null && (m == null || v > m)) m = v; }
        return m;
    }

    private static Double min(List<Point> pts, LocalDate day, String field) {
        Double m = null;
        for (Point p : onDay(pts, day)) { Double v = num(p.v, field); if (v != null && (m == null || v < m)) m = v; }
        return m;
    }

    private static boolean any(List<Point> pts, LocalDateTime now, String keyword) {
        for (Point p : onDay(pts, now.toLocalDate())) {
            if (!p.end.isAfter(now)) continue;
            String s = str(p.v, "Weather");
            if (s != null && s.contains(keyword)) return true;
        }
        return false;
    }

    // ---- JSON 小工具：欄位名不分大小寫（氣象署新舊版大小寫不一） ----

    private static JsonNode f(JsonNode n, String name) {
        if (n == null || !n.isObject()) return null;
        JsonNode v = n.get(name);
        if (v != null) return v;
        Iterator<Map.Entry<String, JsonNode>> it = n.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            if (e.getKey().equalsIgnoreCase(name)) return e.getValue();
        }
        return null;
    }

    private static Iterable<JsonNode> iter(JsonNode n) {
        if (n == null) return List.of();
        if (n.isArray()) return n;
        return List.of(n);
    }

    private static JsonNode first(JsonNode n) {
        if (n == null) return null;
        if (n.isArray()) return n.size() > 0 ? n.get(0) : null;
        return n;
    }

    /** 取指定欄位，沒有該欄位時退回第一個欄位（ElementValue 多半只有一個值） */
    private static String str(JsonNode v, String field) {
        JsonNode x = f(v, field);
        if (x == null && v != null && v.isObject() && v.size() > 0) x = v.elements().next();
        if (x == null || x.isNull()) return null;
        String s = x.asText("").trim();
        return s.isEmpty() || "-".equals(s) ? null : s;
    }

    private static Double num(JsonNode v, String field) {
        String s = str(v, field);
        if (s == null) return null;
        try {
            return Double.parseDouble(s.replaceAll("[^0-9.\\-]", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Double dbl(JsonNode n) {
        if (n == null || n.isNull()) return null;
        try { return Double.parseDouble(n.asText()); } catch (NumberFormatException e) { return null; }
    }

    private static LocalDateTime time(JsonNode a, JsonNode b) {
        JsonNode n = a != null && !a.isNull() ? a : b;
        if (n == null || n.isNull() || n.asText("").isBlank()) return null;
        String s = n.asText().trim();
        try {
            return OffsetDateTime.parse(s).atZoneSameInstant(TPE).toLocalDateTime();
        } catch (Exception e) {
            try { return LocalDateTime.parse(s.replace(' ', 'T')); } catch (Exception ignored) { return null; }
        }
    }

    /** 統一用「台」：前端鄉鎮座標表與訂單地址都用台北市、台中市… */
    static String normalize(String s) { return s == null ? "" : s.trim().replace('臺', '台'); }
}
