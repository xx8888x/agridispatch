package com.agridispatch;

import com.agridispatch.entity.Order;
import com.agridispatch.entity.User;
import com.agridispatch.repository.OrderRepository;
import com.agridispatch.repository.UserRepository;
import com.agridispatch.security.PasswordHasher;
import com.agridispatch.service.FinanceReportService;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.YearMonth;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 財務報表（損益表、資產負債表、現金流量表、淨利）與傳票：僅科技後台最高權限；
 * 營收對得上訂單、成本費用對得上傳票、資產負債表平衡、現金流量表與資產負債表現金勾稽。
 * 示範種子傳票：2026-09 開帳（現金 73 萬、借款 100 萬、股本 10 萬、開辦費 37 萬）、設備 28 萬，
 * 9、10 月各付成本費用 126,583（成本 18,583＋費用 108,000）與折舊 5,000。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:agri_finance_test;DB_CLOSE_DELAY=-1",
        "agri.demo.enabled=false",
        "agri.security.token-secret=unit-test-token-secret-0123456789abcdef",
        "agri.security.admin-password=Admin#Pass2026"
})
@AutoConfigureMockMvc
class FinanceReportApiTest extends ApiTestSupport {

    static final YearMonth OCT = YearMonth.of(2026, 10);

    @Autowired UserRepository userRepository;
    @Autowired PasswordHasher passwordHasher;
    @Autowired OrderRepository orderRepository;
    @Autowired FinanceReportService service;

    private String admin;
    private String coop;

    @BeforeEach
    void setUp() throws Exception {
        if (!userRepository.existsById("coop_fin")) {
            userRepository.save(new User("coop_fin", passwordHasher.hash("Coop#2026pw"), "合作社測試員", "coop_admin", "COOP01", "合作社後台人員"));
        }
        admin = login("admin_tech", "Admin#Pass2026");
        coop = login("coop_fin", "Coop#2026pw");
    }

    private int status(String method, String url, String token, Object body) throws Exception {
        return call(method, url, token, body).getResponse().getStatus();
    }

    @Test
    void onlyTopAdminCanUseFinance() throws Exception {
        assertEquals(200, status("GET", "/api/finance/report", admin, null));
        assertEquals(200, status("GET", "/api/finance/vouchers", admin, null));
        assertEquals(200, status("GET", "/api/finance/accounts", admin, null));
        for (String url : List.of("/api/finance/report", "/api/finance/vouchers", "/api/finance/accounts")) {
            assertEquals(403, status("GET", url, coop, null), "合作社管理員不得看 " + url);
            assertEquals(401, status("GET", url, null, null), "未登入 " + url);
        }
        Map<String, Object> v = Map.of("date", "2026-12-01", "memo", "x", "lines", List.of(
                Map.of("account", "1101", "debit", 1), Map.of("account", "3101", "credit", 1)));
        assertEquals(403, status("POST", "/api/finance/vouchers", coop, v), "合作社不得登錄傳票");
        assertEquals(403, status("POST", "/api/finance/vouchers/1/void", coop, Map.of("reason", "x")));
        assertEquals(400, status("GET", "/api/finance/report?period=2026-13", admin, null), "期間格式錯誤");
        // 天候戰情的保全營收數字同樣只給最高權限，合作社看不到
        assertTrue(json(call("GET", "/api/dispatch/overview", admin, null)).get("data").has("securedRevenue"));
        assertFalse(json(call("GET", "/api/dispatch/overview", coop, null)).get("data").has("securedRevenue"));
    }

    @Test
    void revenueFromOrdersCostsFromVouchersAndStatementsTieOut() {
        long fee = 0, collected = 0, backlog = 0, gmv = 0;
        for (Order o : orderRepository.findAll()) {
            long f = Math.round(o.getFinalAmount() * 0.16);
            String st = o.getStatus();
            YearMonth ym = o.getStartDate() != null ? YearMonth.from(o.getStartDate()) : YearMonth.parse(o.getCreatedAt().substring(0, 7));
            if (("in_progress".equals(st) || "completed".equals(st)) && !ym.isAfter(OCT)) {
                fee += f;
                gmv += o.getFinalAmount();
                if ("completed".equals(st)) collected += f;
            }
            if ("pending".equals(st) || "confirmed".equals(st)) backlog += f;
        }
        assertTrue(fee > 0, "種子資料應有履約訂單");

        Map<String, Object> r = service.report("2026", OCT);
        Map<String, Object> s = map(r.get("summary"));
        assertEquals(fee, num(s.get("revenue")));
        assertEquals(gmv, num(s.get("gmv")));
        assertEquals(backlog, num(s.get("backlogFee")));
        long pretax = fee - 2 * 18_583 - 2 * 108_000 - 2 * 5_000;   // 傳票：成本、費用、折舊
        assertEquals(pretax, num(s.get("preTaxProfit")));
        assertEquals(pretax, num(s.get("netProfit")), "未開所得稅傳票 → 稅後 = 稅前");
        assertEquals("2026-09", r.get("from"));
        assertEquals("2026-10", r.get("to"));
        assertEquals(2, r.get("months"));
        // 現金 = 開帳 73 萬 − 設備 28 萬 − 兩個月付現 + 已收代管費
        long cash = 730_000 - 280_000 - 2 * 126_583 + collected;
        assertEquals(cash, num(s.get("cash")));
        assertEquals(Boolean.TRUE, r.get("balanceSheetBalanced"), "資產 = 負債 + 權益");
        assertEquals(Boolean.TRUE, r.get("cashFlowReconciled"), "現金流量表期末現金 = 資產負債表現金");
        assertEquals(6, num(s.get("voucherCount")), "示範傳票：開帳、設備、9／10 月成本與折舊各 2 張");
    }

    @Test
    void vouchersChangeTheReportAndCanBeVoided() throws Exception {
        YearMonth dec = YearMonth.of(2026, 12);
        long before = num(map(service.report("2026-12", dec).get("summary")).get("netProfit"));

        // 驗證：借貸不平、系統科目、兩邊都填、科目不存在、金額小數
        assertEquals(400, status("POST", "/api/finance/vouchers", admin, voucher("2026-12-05", "不平",
                line("1101", 100, 0), line("7201", 0, 90))));
        assertEquals(400, status("POST", "/api/finance/vouchers", admin, voucher("2026-12-05", "系統科目",
                line("1101", 100, 0), line("4101", 0, 100))), "4101 由訂單自動入帳");
        assertEquals(400, status("POST", "/api/finance/vouchers", admin, voucher("2026-12-05", "兩邊",
                line("1101", 100, 100), line("7201", 0, 0))));
        assertEquals(400, status("POST", "/api/finance/vouchers", admin, voucher("2026-12-05", "無此科目",
                line("9999", 100, 0), line("7201", 0, 100))));
        assertEquals(400, status("POST", "/api/finance/vouchers", admin, voucher("2026-12-05", "小數",
                line("1101", 10.5, 0), line("7201", 0, 10.5))));
        assertEquals(400, status("POST", "/api/finance/vouchers", admin, voucher("2026-12-05", "",
                line("1101", 100, 0), line("7201", 0, 100))), "摘要必填");

        // 登錄補助款入帳 10 萬 → 營業外收入、現金增加、淨利增加
        var res = call("POST", "/api/finance/vouchers", admin, voucher("2026-12-05", "補助款入帳",
                line("1101", 100_000, 0), line("7201", 0, 100_000)));
        assertEquals(201, res.getResponse().getStatus());
        JsonNode created = json(res).get("data");
        assertTrue(created.get("voucherNo").asText().startsWith("V202612-"));
        Map<String, Object> r = service.report("2026-12", dec);
        assertEquals(before + 100_000, num(map(r.get("summary")).get("netProfit")));
        assertEquals(Boolean.TRUE, r.get("balanceSheetBalanced"));
        assertEquals(Boolean.TRUE, r.get("cashFlowReconciled"));

        // 作廢：需原因，作廢後不再入帳，重複作廢 409，紀錄仍在清單
        long id = created.get("id").asLong();
        assertEquals(400, status("POST", "/api/finance/vouchers/" + id + "/void", admin, Map.of()));
        assertEquals(200, status("POST", "/api/finance/vouchers/" + id + "/void", admin, Map.of("reason", "日期登錄錯誤")));
        assertEquals(409, status("POST", "/api/finance/vouchers/" + id + "/void", admin, Map.of("reason", "again")));
        assertEquals(404, status("POST", "/api/finance/vouchers/999999/void", admin, Map.of("reason", "x")));
        assertEquals(before, num(map(service.report("2026-12", dec).get("summary")).get("netProfit")));
        JsonNode listed = json(call("GET", "/api/finance/vouchers?month=2026-12", admin, null)).get("data");
        assertEquals(1, listed.size());
        assertEquals("void", listed.get(0).get("status").asText());
        assertEquals("日期登錄錯誤", listed.get(0).get("voidReason").asText());
    }

    @Test
    void monthsAddUpToYearAndEdgePeriodsStayBalanced() {
        long sep = net(service.report("2026-09", OCT)), oct = net(service.report("2026-10", OCT));
        assertEquals(net(service.report("2026", OCT)), sep + oct, "虧損年度不課稅：單月加總 = 年度累計");

        Map<String, Object> before = service.report("2026-01", OCT);   // 第一筆分錄之前
        assertEquals(0, before.get("months"));
        assertEquals(Boolean.TRUE, before.get("balanceSheetBalanced"));
        assertEquals(0L, net(before));

        Map<String, Object> future = service.report("2027-05", OCT);   // 未來月份：沒有期間資料，資產負債仍為累計餘額
        assertEquals(0, future.get("months"));
        assertEquals(Boolean.TRUE, future.get("balanceSheetBalanced"));
        assertEquals(Boolean.TRUE, future.get("cashFlowReconciled"));
    }

    @Test
    void apiShape() throws Exception {
        JsonNode d = json(call("GET", "/api/finance/report?period=2026-10", admin, null)).get("data");
        assertTrue(d.get("incomeStatement").isArray() && d.get("balanceSheet").isArray() && d.get("cashFlow").isArray());
        assertTrue(d.get("summary").has("netProfit"));
        assertTrue(d.get("notes").size() >= 2);
        assertTrue(json(call("GET", "/api/finance/accounts", admin, null)).get("data").size() >= 20);
    }

    private static Map<String, Object> voucher(String date, String memo, Map<String, Object>... lines) {
        return Map.of("date", date, "memo", memo, "lines", List.of(lines));
    }

    private static Map<String, Object> line(String account, Number debit, Number credit) {
        return Map.of("account", account, "debit", debit, "credit", credit);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o) { return (Map<String, Object>) o; }

    private static long num(Object o) { return ((Number) o).longValue(); }

    private static long net(Map<String, Object> r) { return num(map(r.get("summary")).get("netProfit")); }
}
