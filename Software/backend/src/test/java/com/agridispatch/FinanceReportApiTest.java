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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 財務報表（損益表、資產負債表、現金流量表、淨利）：僅科技後台最高權限；數字須與訂單、F2 預算對得上，資產負債表須平衡。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:agri_finance_test;DB_CLOSE_DELAY=-1",
        "agri.demo.enabled=false",
        "agri.security.token-secret=unit-test-token-secret-0123456789abcdef",
        "agri.security.admin-password=Admin#Pass2026"
})
@AutoConfigureMockMvc
class FinanceReportApiTest extends ApiTestSupport {

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

    @Test
    void onlyTopAdminCanReadReports() throws Exception {
        assertEquals(200, call("GET", "/api/finance/report", admin, null).getResponse().getStatus());
        assertEquals(403, call("GET", "/api/finance/report", coop, null).getResponse().getStatus(), "合作社管理員不得看財報");
        assertEquals(401, call("GET", "/api/finance/report", null, null).getResponse().getStatus(), "未登入");
        assertEquals(400, call("GET", "/api/finance/report?period=2026-13", admin, null).getResponse().getStatus(), "期間格式錯誤");
        // 天候戰情的保全營收數字同樣只給最高權限，合作社看不到
        assertTrue(json(call("GET", "/api/dispatch/overview", admin, null)).get("data").has("securedRevenue"));
        assertFalse(json(call("GET", "/api/dispatch/overview", coop, null)).get("data").has("securedRevenue"));
    }

    @Test
    void revenueMatchesFulfilledOrdersAndStatementsBalance() throws Exception {
        long expectedFee = 0, backlog = 0;
        for (Order o : orderRepository.findAll()) {
            long fee = Math.round(o.getFinalAmount() * 0.16);
            String st = o.getStatus();
            YearMonth ym = o.getStartDate() != null ? YearMonth.from(o.getStartDate()) : YearMonth.parse(o.getCreatedAt().substring(0, 7));
            if (("in_progress".equals(st) || "completed".equals(st)) && !ym.isBefore(FinanceReportService.OPERATION_START)
                    && !ym.isAfter(YearMonth.of(2026, 10))) expectedFee += fee;
            if ("pending".equals(st) || "confirmed".equals(st)) backlog += fee;
        }
        assertTrue(expectedFee > 0, "種子資料應有履約訂單");

        Map<String, Object> r = service.report("2026", YearMonth.of(2026, 10));
        @SuppressWarnings("unchecked") Map<String, Object> s = (Map<String, Object>) r.get("summary");
        assertEquals(expectedFee, ((Number) s.get("revenue")).longValue());
        assertEquals(backlog, ((Number) s.get("backlogFee")).longValue());
        assertEquals("2026-09", r.get("from"));
        assertEquals("2026-10", r.get("to"));
        assertEquals(2, r.get("months"));
        assertEquals(Boolean.TRUE, r.get("balanceSheetBalanced"), "資產 = 負債 + 權益");

        // 兩個月：成本 = F2 預算 2/12；稅前＝收入 − 成本 − 費用 − 折舊；淨利 = 稅前 − 稅
        long cogs = Math.round(72000 * 2 / 12.0) + Math.round(55000 * 2 / 12.0) + Math.round(96000 * 2 / 12.0);
        long opex = Math.round(720000 * 2 / 12.0) + Math.round(180000 * 2 / 12.0) + Math.round(180000 * 2 / 12.0)
                + Math.round(120000 * 2 / 12.0) + Math.round(96000 * 2 / 12.0);
        long pretax = expectedFee - cogs - opex - 10_000;
        long tax = pretax > 0 ? Math.round(pretax * 0.2) : 0;
        assertEquals(pretax, ((Number) s.get("preTaxProfit")).longValue());
        assertEquals(pretax - tax, ((Number) s.get("netProfit")).longValue());
    }

    @Test
    void monthAndYearAreConsistentAndFutureOrPastIsSafe() {
        YearMonth now = YearMonth.of(2026, 10);
        long sepNet = net(service.report("2026-09", now)), octNet = net(service.report("2026-10", now));
        long yearNet = net(service.report("2026", now));
        // 虧損年度不課稅，單月加總 = 年度累計
        assertEquals(yearNet, sepNet + octNet);

        // 營運起算前：只有期初資產負債（現金 73 萬 = 負債 + 權益）
        Map<String, Object> before = service.report("2026-01", now);
        assertEquals(0, before.get("months"));
        assertEquals(Boolean.TRUE, before.get("balanceSheetBalanced"));
        assertEquals(0L, net(before));

        // 未來月份：沒有期間資料，資產負債表仍平衡
        Map<String, Object> future = service.report("2027-05", now);
        assertEquals(0, future.get("months"));
        assertEquals(Boolean.TRUE, future.get("balanceSheetBalanced"));
    }

    @Test
    void apiShape() throws Exception {
        JsonNode d = json(call("GET", "/api/finance/report?period=2026-10", admin, null)).get("data");
        assertTrue(d.get("incomeStatement").isArray() && d.get("balanceSheet").isArray() && d.get("cashFlow").isArray());
        assertTrue(d.get("summary").has("netProfit"));
        assertTrue(d.get("notes").size() >= 2);
    }

    @SuppressWarnings("unchecked")
    private static long net(Map<String, Object> r) {
        return ((Number) ((Map<String, Object>) r.get("summary")).get("netProfit")).longValue();
    }
}
