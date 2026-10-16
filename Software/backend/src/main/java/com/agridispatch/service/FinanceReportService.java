package com.agridispatch.service;

import com.agridispatch.common.ApiException;
import com.agridispatch.entity.Order;
import com.agridispatch.repository.OrderRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 科技公司財務報表：損益表、資產負債表、現金流量表與淨利（僅科技後台最高權限；FinanceController）。
 *
 * <p>資料來源與假設（F2 v2.4 為定版數字）：
 * <ul>
 *   <li>營業收入＝訂單金額 × 16% 技術代管費；訂單進入履約（in_progress、completed）才認列，已取消與尚未履約者不認列，
 *       尚未履約者列為「在手訂單預估代管費」備註。月份依訂單工期起日（無則下單日）。</li>
 *   <li>代管費於訂單完工（completed）收現，履約中（in_progress）為應收帳款。合作社 84% 為法定人力成本，不是科技公司收入，不入帳。</li>
 *   <li>成本與費用：系統尚無會計傳票，暫以 F2 第一年預算按月攤提（年額 ÷ 12），營運起算月見 {@link #OPERATION_START}。
 *       預算年度更新、實際發生數接帳務系統後再替換【待確認】。</li>
 *   <li>期初（籌備期結束）：青創貸款 100 萬、股本 10 萬、開辦費 37 萬已費用化，現金 73 萬；營運首月購入伺服器與設備 28 萬，
 *       每年折舊 6 萬。所得稅依日曆年度累計稅前淨利 × 20%（簡化，不含虧損扣抵）【待會計師確認】，當期繳納。</li>
 *   <li>不含政府補助（情境）、第三層統包價差與加值收入（系統尚未紀錄）、貸款本金償還（F2 第二年起）。</li>
 * </ul>
 */
@Service
public class FinanceReportService {

    /** 營運起算月（F2 第一年第 1 個月；示範資料自 2026-09 起） */
    public static final YearMonth OPERATION_START = YearMonth.of(2026, 9);
    static final double TECH_RATE = 0.16;
    static final double TAX_RATE = 0.20;
    static final long OPENING_CASH = 730_000;
    static final long LOAN = 1_000_000;
    static final long SHARE_CAPITAL = 100_000;
    static final long STARTUP_EXPENSE = 370_000;
    static final long CAPEX = 280_000;
    static final long DEPRECIATION_YEAR = 60_000;

    /** F2 第一年預算（年額）：營業成本 */
    static final String[][] COGS = {
            {"雲端伺服器與 API", "72000"},
            {"承攬工班品管與保險補貼", "55000"},
            {"現場調度幹部差旅與安全耗材", "96000"},
    };
    /** F2 第一年預算（年額）：營業費用 */
    static final String[][] OPEX = {
            {"創辦團隊與後台研發薪資", "720000"},
            {"雙語舍監與兼職通譯津貼", "180000"},
            {"本土農工與兼職招募推廣費", "180000"},
            {"法律顧問、會計與合規規費", "120000"},
            {"辦公室租金與水電雜支", "96000"},
    };

    private static final ZoneId TPE = ZoneId.of("Asia/Taipei");
    private static final Pattern YEAR = Pattern.compile("\\d{4}");
    private static final Pattern MONTH = Pattern.compile("\\d{4}-(0[1-9]|1[0-2])");

    private final OrderRepository orderRepository;

    public FinanceReportService(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    public Map<String, Object> report(String period) {
        return report(period, YearMonth.now(TPE));
    }

    /** now 可注入（測試用）：未到的月份沒有資料，截至該月 */
    public Map<String, Object> report(String period, YearMonth now) {
        boolean wholeYear;
        YearMonth from, to;
        if (period == null || period.isBlank()) period = String.valueOf(now.getYear());
        if (YEAR.matcher(period).matches()) {
            wholeYear = true;
            from = YearMonth.of(Integer.parseInt(period), 1);
            to = YearMonth.of(Integer.parseInt(period), 12);
        } else if (MONTH.matcher(period).matches()) {
            wholeYear = false;
            from = to = YearMonth.parse(period);
        } else {
            throw ApiException.badRequest("period 格式須為 YYYY 或 YYYY-MM");
        }
        YearMonth asOf = to.isAfter(now) ? now : to;     // 未到的月份沒有資料：截至本月
        int n = asOf.isBefore(OPERATION_START) ? 0 : (int) (monthIndex(asOf) + 1);

        // ── 逐月彙總（索引 0 ＝ 營運起算月） ──
        long[] rev = new long[n], col = new long[n], gmv = new long[n];
        long backlogFee = 0;
        for (Order o : orderRepository.findAll()) {
            String st = o.getStatus();
            long fee = Math.round(o.getFinalAmount() * TECH_RATE);
            if ("pending".equals(st) || "confirmed".equals(st)) { backlogFee += fee; continue; }
            if (!"in_progress".equals(st) && !"completed".equals(st)) continue;
            YearMonth ym = orderMonth(o);
            if (ym == null || ym.isBefore(OPERATION_START)) continue;
            int i = (int) monthIndex(ym);
            if (i >= n) continue;
            rev[i] += fee;
            gmv[i] += o.getFinalAmount();
            if ("completed".equals(st)) col[i] += fee;
        }
        long[] cogs = new long[n], opex = new long[n], dep = new long[n], pretax = new long[n], tax = new long[n];
        long[][] cogsLine = new long[COGS.length][n], opexLine = new long[OPEX.length][n];
        long ytd = 0;
        for (int i = 0; i < n; i++) {
            for (int k = 0; k < COGS.length; k++) { cogsLine[k][i] = accrue(COGS[k][1], i + 1) - accrue(COGS[k][1], i); cogs[i] += cogsLine[k][i]; }
            for (int k = 0; k < OPEX.length; k++) { opexLine[k][i] = accrue(OPEX[k][1], i + 1) - accrue(OPEX[k][1], i); opex[i] += opexLine[k][i]; }
            dep[i] = accrue(String.valueOf(DEPRECIATION_YEAR), i + 1) - accrue(String.valueOf(DEPRECIATION_YEAR), i);
            pretax[i] = rev[i] - cogs[i] - opex[i] - dep[i];
            boolean newYear = i == 0 || OPERATION_START.plusMonths(i).getMonthValue() == 1;
            long prev = newYear ? 0 : ytd;
            ytd = (newYear ? 0 : ytd) + pretax[i];
            tax[i] = taxOf(ytd) - taxOf(prev);
        }

        int p0 = n == 0 ? 0 : Math.max(0, (int) monthIndex(from.isBefore(OPERATION_START) ? OPERATION_START : from));
        int p1 = n - 1;
        boolean hasPeriod = n > 0 && p0 <= p1 && !from.isAfter(asOf);

        // 期間合計與累計（起算月至截止月）
        long pRev = sum(rev, p0, p1, hasPeriod), pGmv = sum(gmv, p0, p1, hasPeriod);
        long pCogs = sum(cogs, p0, p1, hasPeriod), pOpex = sum(opex, p0, p1, hasPeriod), pDep = sum(dep, p0, p1, hasPeriod);
        long pTax = sum(tax, p0, p1, hasPeriod);
        long gross = pRev - pCogs;
        long ebitda = gross - pOpex;
        long ebit = ebitda - pDep;
        long preTax = ebit;                       // 利息費用：F2 前兩年由政府補貼，公司負擔 0
        long net = preTax - pTax;

        long pAr = sum(rev, p0, p1, hasPeriod) - sum(col, p0, p1, hasPeriod);   // 期間應收增加
        long cfo = net + pDep - pAr;
        boolean capexInPeriod = hasPeriod && p0 == 0;
        long cfi = capexInPeriod ? -CAPEX : 0;
        long netCash = cfo + cfi;
        long cumRev = sum(rev, 0, n - 1, n > 0), cumCol = sum(col, 0, n - 1, n > 0);
        long cumNet = 0, cumDep = sum(dep, 0, n - 1, n > 0);
        for (int i = 0; i < n; i++) cumNet += pretax[i] - tax[i];
        long cumCapex = n > 0 ? CAPEX : 0;
        long closingCash = OPENING_CASH + cumNet + cumDep - (cumRev - cumCol) - cumCapex;
        long openingCash = closingCash - (hasPeriod ? netCash : 0);
        long ar = cumRev - cumCol;
        long fixedAssets = cumCapex - cumDep;
        long assets = closingCash + ar + fixedAssets;
        long retained = -STARTUP_EXPENSE + cumNet;
        long equity = SHARE_CAPITAL + retained;
        long liabEquity = LOAN + equity;

        List<Map<String, Object>> is = new ArrayList<>();
        is.add(row("營業收入", null, "head"));
        is.add(row("系統代管與調度服務費（" + (int) Math.round(TECH_RATE * 100) + "%）", pRev, "line"));
        is.add(row("營業收入合計", pRev, "sub"));
        is.add(row("營業成本", null, "head"));
        for (int k = 0; k < COGS.length; k++) is.add(row(COGS[k][0], sum(cogsLine[k], p0, p1, hasPeriod), "line"));
        is.add(row("營業成本合計", pCogs, "sub"));
        is.add(row("營業毛利", gross, "total"));
        is.add(row("營業費用", null, "head"));
        for (int k = 0; k < OPEX.length; k++) is.add(row(OPEX[k][0], sum(opexLine[k], p0, p1, hasPeriod), "line"));
        is.add(row("營業費用合計", pOpex, "sub"));
        is.add(row("EBITDA", ebitda, "total"));
        is.add(row("折舊與攤銷", -pDep, "line"));
        is.add(row("營業淨利（EBIT）", ebit, "total"));
        is.add(row("利息費用", 0L, "line"));
        is.add(row("稅前淨利", preTax, "total"));
        is.add(row("營利事業所得稅（20%）", -pTax, "line"));
        is.add(row("稅後淨利", net, "total"));

        List<Map<String, Object>> cf = new ArrayList<>();
        cf.add(row("營業活動", null, "head"));
        cf.add(row("稅後淨利", net, "line"));
        cf.add(row("折舊與攤銷加回", pDep, "line"));
        cf.add(row("應收帳款（代管費）增加", -pAr, "line"));
        cf.add(row("營業活動淨現金流", cfo, "sub"));
        cf.add(row("投資活動", null, "head"));
        cf.add(row("伺服器與辦公設備購置", cfi, "line"));
        cf.add(row("投資活動淨現金流", cfi, "sub"));
        cf.add(row("融資活動", null, "head"));
        cf.add(row("借款本金償還（F2 第二年起）", 0L, "line"));
        cf.add(row("融資活動淨現金流", 0L, "sub"));
        cf.add(row("現金淨增加額", netCash, "total"));
        cf.add(row("期初現金餘額", openingCash, "line"));
        cf.add(row("期末現金餘額", closingCash, "total"));

        List<Map<String, Object>> bs = new ArrayList<>();
        bs.add(row("資產", null, "head"));
        bs.add(row("現金", closingCash, "line"));
        bs.add(row("應收帳款（代管費）", ar, "line"));
        bs.add(row("固定資產淨額", fixedAssets, "line"));
        bs.add(row("資產總計", assets, "total"));
        bs.add(row("負債", null, "head"));
        bs.add(row("長期借款（青創貸款）", LOAN, "line"));
        bs.add(row("負債合計", LOAN, "sub"));
        bs.add(row("權益", null, "head"));
        bs.add(row("股本", SHARE_CAPITAL, "line"));
        bs.add(row("累積損益（含開辦費）", retained, "line"));
        bs.add(row("權益合計", equity, "sub"));
        bs.add(row("負債及權益總計", liabEquity, "total"));

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("revenue", pRev);
        summary.put("grossProfit", gross);
        summary.put("ebitda", ebitda);
        summary.put("preTaxProfit", preTax);
        summary.put("netProfit", net);
        summary.put("netMarginPct", pRev == 0 ? null : Math.round(net * 1000.0 / pRev) / 10.0);
        summary.put("gmv", pGmv);
        summary.put("backlogFee", backlogFee);
        summary.put("cash", closingCash);

        List<String> notes = new ArrayList<>();
        notes.add("營業收入＝履約中與已完工訂單金額 × 16% 技術代管費（開立發票）；合作社 84% 為法定人力成本，不計入科技公司收入。已取消與尚未履約的訂單不認列。");
        notes.add("營業成本與費用暫依 F2 v2.4 第一年預算按月攤提（年額 ÷ 12），尚未接會計傳票；折舊、所得稅（20% 簡化）亦為估算【待會計師確認】。");
        notes.add("不含政府補助（情境）、第三層統包價差與加值收入、貸款本金償還（F2 第二年起）。");
        if (n == 0) notes.add("尚未到營運起算月（" + OPERATION_START + "），目前顯示期初資產負債。");
        else if (!hasPeriod) notes.add("所選期間尚無資料，資產負債表為截至 " + asOf + " 的累計餘額。");
        else if (wholeYear && asOf.isBefore(to)) notes.add("年度尚未結束，累計至 " + asOf + "。");

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("period", period);
        out.put("label", wholeYear ? period + " 年度" : period);
        out.put("from", hasPeriod ? OPERATION_START.plusMonths(p0).toString() : null);
        out.put("to", n == 0 ? null : asOf.toString());
        out.put("months", hasPeriod ? p1 - p0 + 1 : 0);
        out.put("operationStart", OPERATION_START.toString());
        out.put("summary", summary);
        out.put("incomeStatement", is);
        out.put("balanceSheet", bs);
        out.put("balanceSheetBalanced", assets == liabEquity);
        out.put("cashFlow", cf);
        out.put("notes", notes);
        return out;
    }

    /** 第 months 個月累計應攤提金額（年額 ÷ 12 四捨五入，期間金額取累計差，加總不漂移） */
    private static long accrue(String annual, int months) {
        return Math.round(Long.parseLong(annual) * months / 12.0);
    }

    private static long taxOf(long ytdPreTax) {
        return ytdPreTax <= 0 ? 0 : Math.round(ytdPreTax * TAX_RATE);
    }

    private static long monthIndex(YearMonth ym) {
        return (ym.getYear() - OPERATION_START.getYear()) * 12L + ym.getMonthValue() - OPERATION_START.getMonthValue();
    }

    private static long sum(long[] a, int from, int to, boolean on) {
        long s = 0;
        if (!on) return 0;
        for (int i = from; i <= to && i < a.length; i++) s += a[i];
        return s;
    }

    /** 認列月份：工期起日；沒有工期者用下單日（yyyy-MM-dd HH:mm:ss） */
    private static YearMonth orderMonth(Order o) {
        LocalDate d = o.getStartDate();
        if (d != null) return YearMonth.from(d);
        String c = o.getCreatedAt();
        if (c != null && c.length() >= 7) {
            try { return YearMonth.parse(c.substring(0, 7)); } catch (RuntimeException ignored) { }
        }
        return null;
    }

    private static Map<String, Object> row(String label, Long amount, String style) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("label", label);
        m.put("amount", amount);
        m.put("style", style);
        return m;
    }
}
