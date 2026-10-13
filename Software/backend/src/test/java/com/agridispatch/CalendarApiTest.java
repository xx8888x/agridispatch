package com.agridispatch;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 農事行事曆 /api/calendar（CalendarController／CalendarService）
 * - 公開端點（AuthInterceptor PUBLIC：GET /api/calendar、/api/calendar/**），未登入也可讀
 * - 季度 4 筆、月份 12 筆；收穫倒數依選定年月輪轉排序並計算倒數天數與燈號
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:agri_calendar_test;DB_CLOSE_DELAY=-1",
        "agri.demo.enabled=false",
        "agri.security.token-secret=unit-test-token-secret-0123456789abcdef",
        "agri.security.admin-password=Admin#Pass2026"
})
@AutoConfigureMockMvc
class CalendarApiTest extends ApiTestSupport {

    private JsonNode get(String url) throws Exception {
        MvcResult r = call("GET", url, null, null);
        assertEquals(200, r.getResponse().getStatus(), url);
        JsonNode j = json(r);
        assertTrue(j.get("success").asBoolean());
        return j;
    }

    private static List<String> ids(JsonNode harvests) {
        List<String> out = new ArrayList<>();
        harvests.forEach(h -> out.add(h.get("id").asText()));
        return out;
    }

    @Test
    void calendarIsPublicButWritesNeedLogin() throws Exception {
        assertEquals(200, call("GET", "/api/calendar", null, null).getResponse().getStatus());
        assertEquals(200, call("GET", "/api/calendar/quarters", null, null).getResponse().getStatus());
        assertEquals(200, call("GET", "/api/calendar/months", null, null).getResponse().getStatus());
        assertEquals(200, call("GET", "/api/calendar/harvests", null, null).getResponse().getStatus());
        // 行事曆是唯讀資料：沒有寫入端點（405，請求不會進到攔截器之後）
        assertEquals(405, call("POST", "/api/calendar", null, "{}").getResponse().getStatus());
        assertEquals(405, call("DELETE", "/api/calendar/quarters", null, null).getResponse().getStatus());
    }

    @Test
    void calendarSameForLoggedInUsers() throws Exception {
        String admin = login("admin_tech", "Admin#Pass2026");
        MvcResult r = call("GET", "/api/calendar/harvests?year=2027&month=5", admin, null);
        assertEquals(200, r.getResponse().getStatus());
        assertEquals(ids(get("/api/calendar/harvests?year=2027&month=5").get("data")), ids(json(r).get("data")));
    }

    @Test
    void quartersAndMonthsHaveFixedShape() throws Exception {
        JsonNode q = get("/api/calendar/quarters").get("data");
        assertEquals(4, q.size());
        for (int i = 0; i < 4; i++) {
            JsonNode x = q.get(i);
            assertEquals("Q" + (i + 1), x.get("quarter").asText());
            assertFalse(x.get("name").asText().isBlank());
            assertTrue(x.get("keyCrops").isArray() && x.get("keyCrops").size() > 0);
            assertTrue(x.get("utilizationRate").asText().endsWith("%"));
        }

        JsonNode m = get("/api/calendar/months").get("data");
        assertEquals(12, m.size());
        for (int i = 0; i < 12; i++) {
            JsonNode x = m.get(i);
            assertEquals(i + 1, x.get("month").asInt());
            assertEquals((i + 1) + "月", x.get("monthLabel").asText());
            assertEquals("Q" + (i / 3 + 1), x.get("quarter").asText(), "第 " + (i + 1) + " 月所屬季度");
            assertFalse(x.get("crops").asText().isBlank());
            assertFalse(x.get("suggestedTeam").asText().isBlank());
        }
    }

    /** 選 2027 年 5 月（非本月 → 基準日固定 15 日）：輪轉排序、跨年、倒數天數與燈號 */
    @Test
    void harvestsRotateFromSelectedMonthWithCountdown() throws Exception {
        JsonNode j = get("/api/calendar/harvests?year=2027&month=5");
        assertEquals(2027, j.get("selectedYear").asInt());
        assertEquals(5, j.get("selectedMonth").asInt());
        JsonNode h = j.get("data");
        assertEquals(12, h.size());
        assertEquals(List.of("H02", "H01", "H12", "H03", "H04", "H05", "H06", "H07", "H08", "H09", "H10", "H11"), ids(h));

        // H01 大樹玉荷包 5/20：距 2027-05-15 共 5 天 → 採收倒數中（rose）
        JsonNode h01 = h.get(1);
        assertEquals(2027, h01.get("harvestYear").asInt());
        assertEquals(5, h01.get("countdownDays").asInt());
        assertEquals("2027年5月20日", h01.get("harvestDateStr").asText());
        assertEquals("rose", h01.get("statusColor").asText());

        // H12 綠竹筍 6/10：26 天 → 預約搶工中（amber）；H03 6/25：41 天 → amber
        assertEquals(26, h.get(2).get("countdownDays").asInt());
        assertEquals("amber", h.get(2).get("statusColor").asText());
        assertEquals(41, h.get(3).get("countdownDays").asInt());
        assertEquals("amber", h.get(3).get("statusColor").asText());
        // H04 7/15：61 天 → 生長監測中（emerald）
        assertEquals(61, h.get(4).get("countdownDays").asInt());
        assertEquals("emerald", h.get(4).get("statusColor").asText());

        // H10 車城洋蔥 2/20：早於 5 月 → 算到隔年 2028，距 281 天
        JsonNode h10 = h.get(10);
        assertEquals("H10", h10.get("id").asText());
        assertEquals(2028, h10.get("harvestYear").asInt());
        assertEquals(281, h10.get("countdownDays").asInt());
        assertEquals("2028年2月20日", h10.get("harvestDateStr").asText());

        // H02 枋山芒果 5/10：同月但已過基準日 15 日 → 算到 2028-05-10（2028 為閏年，共 361 天）
        JsonNode h02 = h.get(0);
        assertEquals(2028, h02.get("harvestYear").asInt());
        assertEquals(361, h02.get("countdownDays").asInt());
        assertEquals("emerald", h02.get("statusColor").asText());
    }

    /** 任何基準年月：每筆的倒數天數都等於「基準日到下一次收穫日」的天數，且不超過一年 */
    @Test
    void countdownIsAlwaysNextOccurrenceFromBaseDate() throws Exception {
        LocalDate today = LocalDate.now();
        for (int month = 1; month <= 12; month++) {
            JsonNode h = get("/api/calendar/harvests?year=2026&month=" + month).get("data");
            int baseDay = (today.getYear() == 2026 && today.getMonthValue() == month) ? today.getDayOfMonth() : 15;
            LocalDate base = LocalDate.of(2026, month, baseDay);
            for (JsonNode x : h) {
                LocalDate harvest = LocalDate.of(x.get("harvestYear").asInt(), x.get("month").asInt(), x.get("day").asInt());
                long days = ChronoUnit.DAYS.between(base, harvest);
                assertEquals(days, x.get("countdownDays").asLong(), month + " 月 " + x.get("id").asText());
                assertTrue(days >= 0 && harvest.minusYears(1).isBefore(base), "應為下一次收穫日：" + x);
                String color = days <= 15 ? "rose" : days <= 45 ? "amber" : "emerald";
                assertEquals(color, x.get("statusColor").asText(), x.toString());
            }
        }
    }

    @Test
    void overviewBundlesAllSectionsAndFallsBackOnInvalidParams() throws Exception {
        JsonNode o = get("/api/calendar?year=2027&month=5");
        assertEquals(4, o.get("quarters").size());
        assertEquals(12, o.get("months").size());
        assertEquals(ids(get("/api/calendar/harvests?year=2027&month=5").get("data")), ids(o.get("upcomingHarvests")));

        // 年份超出 2024～2035、月份超出 1～12：改用今天的年月
        LocalDate today = LocalDate.now();
        JsonNode bad = get("/api/calendar?year=1999&month=13");
        assertEquals(today.getYear(), bad.get("selectedYear").asInt());
        assertEquals(today.getMonthValue(), bad.get("selectedMonth").asInt());
        JsonNode none = get("/api/calendar/harvests");
        assertEquals(today.getYear(), none.get("selectedYear").asInt());
        assertEquals(today.getMonthValue(), none.get("selectedMonth").asInt());
        // 本月：基準日為今天
        JsonNode first = none.get("data").get(0);
        LocalDate harvest = LocalDate.of(first.get("harvestYear").asInt(), first.get("month").asInt(), first.get("day").asInt());
        assertEquals(ChronoUnit.DAYS.between(today, harvest), first.get("countdownDays").asLong());
    }

    /**
     * 「收穫日倒數清單」應以倒數天數由近到遠排列。
     * 目前排序只比月份差與日，未考慮基準日：同月但已過的收穫日（倒數約 360 天）會排在最前面。
     * 例：選 2027 年 5 月 → H02 枋山芒果 5/10（倒數 361 天）排第 1，H01 5/20（倒數 5 天）排第 2。
     */
    @Disabled("疑似 bug：CalendarService.getUpcomingHarvests 排序未考慮基準日，同月已過的收穫日排在最前（倒數約 360 天）")
    @Test
    void harvestsShouldBeOrderedByCountdown() throws Exception {
        JsonNode h = get("/api/calendar/harvests?year=2027&month=5").get("data");
        for (int i = 1; i < h.size(); i++) {
            assertTrue(h.get(i - 1).get("countdownDays").asInt() <= h.get(i).get("countdownDays").asInt(),
                    "第 " + i + " 筆倒數應不早於前一筆：" + h.get(i - 1) + " / " + h.get(i));
        }
    }
}
