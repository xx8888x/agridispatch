package com.agridispatch.service;

import com.agridispatch.common.ApiException;
import com.agridispatch.entity.Order;
import com.agridispatch.repository.OrderRepository;
import com.agridispatch.service.FinanceVoucherService.Posting;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static com.agridispatch.service.FinanceAccounts.*;

/**
 * 科技公司財務報表：損益表、資產負債表、現金流量表與淨利（僅科技後台最高權限；FinanceController）。
 *
 * <p>總帳＝系統依訂單自動入帳的分錄 ＋ 後台登錄的傳票（FinanceVoucherService，實際收支）：
 * <ul>
 *   <li>營業收入（4101）：訂單進入履約（in_progress、completed）才認列，金額 × 16% 技術代管費，Dr 應收帳款 Cr 代管費收入；
 *       完工（completed）收現時 Dr 現金 Cr 應收帳款。已取消與尚未履約者不入帳，尚未履約者列為「在手訂單預估代管費」備註。
 *       月份依訂單工期起日（無則下單日）。合作社 84% 為法定人力成本，不是科技公司收入。</li>
 *   <li>其他一切（成本費用、設備、借款、股本、折舊、稅款、其他收入）由傳票登錄；系統不再用 F2 預算攤提，
 *       也不自動估所得稅與折舊（需自行開傳票）。F2 v2.4 的預算只用於示範資料的傳票。</li>
 *   <li>損益表與現金流量表為所選期間（年度累計或單月；未到的月份截至本月），資產負債表為期間結束時的累計餘額。
 *       現金流量表採間接法，由科目變動推得，與資產負債表現金勾稽。</li>
 * </ul>
 */
@Service
public class FinanceReportService {

    static final double TECH_RATE = 0.16;

    private static final ZoneId TPE = ZoneId.of("Asia/Taipei");
    private static final Pattern YEAR = Pattern.compile("\\d{4}");
    private static final Pattern MONTH = Pattern.compile("\\d{4}-(0[1-9]|1[0-2])");

    private final OrderRepository orderRepository;
    private final FinanceVoucherService voucherService;

    public FinanceReportService(OrderRepository orderRepository, FinanceVoucherService voucherService) {
        this.orderRepository = orderRepository;
        this.voucherService = voucherService;
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
        YearMonth asOf = to.isAfter(now) ? now : to;

        // ── 總帳：訂單自動分錄 ＋ 傳票 ──
        List<Posting> all = new ArrayList<>();
        long backlog = 0;   // 在手訂單（尚未履約）預估代管費
        List<Object[]> gmvRows = new ArrayList<>();
        for (Order o : orderRepository.findAll()) {
            String st = o.getStatus();
            long fee = Math.round(o.getFinalAmount() * TECH_RATE);
            if ("pending".equals(st) || "confirmed".equals(st)) { backlog += fee; continue; }
            if (!"in_progress".equals(st) && !"completed".equals(st)) continue;
            YearMonth ym = orderMonth(o);
            if (ym == null) continue;
            all.add(new Posting(ym, AR, fee, 0));
            all.add(new Posting(ym, FEE_REVENUE, 0, fee));
            if ("completed".equals(st)) {
                all.add(new Posting(ym, CASH, fee, 0));
                all.add(new Posting(ym, AR, 0, fee));
            }
            gmvRows.add(new Object[]{ym, (long) o.getFinalAmount()});
        }
        all.addAll(voucherService.postings());
        all.removeIf(p -> p.ym().isAfter(asOf));
        YearMonth first = all.stream().map(Posting::ym).min(YearMonth::compareTo).orElse(null);
        // 有資料的期間：起點不早於第一筆分錄；期間在截止月之後（未來月份）則為空
        YearMonth effFrom = first == null ? asOf.plusMonths(1) : (from.isBefore(first) ? first : from);
        boolean hasPeriod = first != null && !effFrom.isAfter(asOf);
        if (!hasPeriod) effFrom = asOf.plusMonths(1);
        final YearMonth pf = effFrom;
        long gmv = gmvRows.stream().filter(r -> !((YearMonth) r[0]).isBefore(pf) && !((YearMonth) r[0]).isAfter(asOf)).mapToLong(r -> (Long) r[1]).sum();

        Ledger opening = new Ledger(all, null, pf.minusMonths(1));   // 期間開始前
        Ledger period_ = new Ledger(all, pf, asOf);                  // 期間
        Ledger closing = new Ledger(all, null, asOf);                // 截至期間結束

        // ── 損益表（期間） ──
        List<Map<String, Object>> is = new ArrayList<>();
        is.add(row("營業收入", null, "head"));
        long rev = 0;
        for (Account a : ofKind("revenue")) { long v = period_.credit(a.code()); rev += v; is.add(row(a.name(), v, "line")); }
        is.add(row("營業收入合計", rev, "sub"));
        is.add(row("營業成本", null, "head"));
        long cogs = 0;
        for (Account a : ofKind("cogs")) { long v = period_.debit(a.code()); cogs += v; is.add(row(a.name(), v, "line")); }
        is.add(row("營業成本合計", cogs, "sub"));
        long gross = rev - cogs;
        is.add(row("營業毛利", gross, "total"));
        is.add(row("營業費用", null, "head"));
        long opex = 0;
        for (Account a : ofKind("opex")) { long v = period_.debit(a.code()); opex += v; is.add(row(a.name(), v, "line")); }
        is.add(row("營業費用合計", opex, "sub"));
        long ebitda = gross - opex;
        is.add(row("EBITDA", ebitda, "total"));
        long dep = period_.debit(DEPRECIATION);
        is.add(row("折舊與攤銷", -dep, "line"));
        long ebit = ebitda - dep;
        is.add(row("營業淨利（EBIT）", ebit, "total"));
        long interest = period_.debit(INTEREST), nonop = period_.credit(NONOP_INCOME);
        is.add(row("利息費用", -interest, "line"));
        is.add(row("營業外收入（補助、利息收入）", nonop, "line"));
        long preTax = ebit - interest + nonop;
        is.add(row("稅前淨利", preTax, "total"));
        long tax = period_.debit(INCOME_TAX);
        is.add(row("營利事業所得稅", -tax, "line"));
        long net = preTax - tax;
        is.add(row("稅後淨利", net, "total"));

        // ── 資產負債表（截至期間結束） ──
        long cash = closing.debit(CASH), ar = closing.debit(AR), fixed = closing.debit(FIXED), accDep = closing.credit(ACC_DEP);
        long assets = cash + ar + fixed - accDep;
        long ap = closing.credit(AP), taxPay = closing.credit(TAX_PAYABLE), loan = closing.credit(LOAN);
        long liab = ap + taxPay + loan;
        long capital = closing.credit(CAPITAL), retained = closing.credit(RETAINED), cumNet = closing.netIncome();
        long equity = capital + retained + cumNet;
        List<Map<String, Object>> bs = new ArrayList<>();
        bs.add(row("資產", null, "head"));
        bs.add(row("現金及銀行存款", cash, "line"));
        bs.add(row("應收帳款（代管費）", ar, "line"));
        bs.add(row("固定資產（伺服器與設備）", fixed, "line"));
        bs.add(row("減：累計折舊", -accDep, "line"));
        bs.add(row("資產總計", assets, "total"));
        bs.add(row("負債", null, "head"));
        bs.add(row("應付帳款", ap, "line"));
        bs.add(row("應付所得稅", taxPay, "line"));
        bs.add(row("長期借款（青創貸款）", loan, "line"));
        bs.add(row("負債合計", liab, "sub"));
        bs.add(row("權益", null, "head"));
        bs.add(row("股本", capital, "line"));
        bs.add(row("累積損益（開帳）", retained, "line"));
        bs.add(row("損益（開帳後累計）", cumNet, "line"));
        bs.add(row("權益合計", equity, "sub"));
        bs.add(row("負債及權益總計", liab + equity, "total"));

        // ── 現金流量表（期間，間接法） ──
        long depAddBack = period_.credit(ACC_DEP), dAr = period_.debit(AR), dAp = period_.credit(AP), dTax = period_.credit(TAX_PAYABLE);
        long cfo = net + depAddBack - dAr + dAp + dTax;
        long cfi = -period_.debit(FIXED);
        long dLoan = period_.credit(LOAN), dCapital = period_.credit(CAPITAL), dRetained = period_.credit(RETAINED);
        long cff = dLoan + dCapital + dRetained;
        long netCash = cfo + cfi + cff;
        long openCash = opening.debit(CASH);
        List<Map<String, Object>> cf = new ArrayList<>();
        cf.add(row("營業活動", null, "head"));
        cf.add(row("稅後淨利", net, "line"));
        cf.add(row("折舊與攤銷加回", depAddBack, "line"));
        cf.add(row("應收帳款（代管費）增加", -dAr, "line"));
        cf.add(row("應付帳款增加", dAp, "line"));
        cf.add(row("應付所得稅增加", dTax, "line"));
        cf.add(row("營業活動淨現金流", cfo, "sub"));
        cf.add(row("投資活動", null, "head"));
        cf.add(row("伺服器與辦公設備購置", cfi, "line"));
        cf.add(row("投資活動淨現金流", cfi, "sub"));
        cf.add(row("融資活動", null, "head"));
        cf.add(row("借款增加（償還為負）", dLoan, "line"));
        cf.add(row("股東出資", dCapital, "line"));
        cf.add(row("其他權益項目（含開帳累積損益）", dRetained, "line"));
        cf.add(row("融資活動淨現金流", cff, "sub"));
        cf.add(row("現金淨增加額", netCash, "total"));
        cf.add(row("期初現金餘額", openCash, "line"));
        cf.add(row("期末現金餘額", openCash + netCash, "total"));

        long vouchers = voucherService.activeCount();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("revenue", rev);
        summary.put("grossProfit", gross);
        summary.put("ebitda", ebitda);
        summary.put("preTaxProfit", preTax);
        summary.put("netProfit", net);
        summary.put("netMarginPct", rev == 0 ? null : Math.round(net * 1000.0 / rev) / 10.0);
        summary.put("gmv", gmv);
        summary.put("backlogFee", backlog);
        summary.put("cash", cash);
        summary.put("voucherCount", vouchers);

        List<String> notes = new ArrayList<>();
        notes.add("營業收入＝履約中與已完工訂單金額 × 16% 技術代管費（系統依訂單自動入帳）；合作社 84% 為法定人力成本，不計入科技公司收入。已取消與尚未履約的訂單不入帳。");
        notes.add("成本、費用、設備、借款、股本、折舊、所得稅與其他收入，一律依「傳票」登錄的實際收支；系統不自動估列折舊與所得稅，請於結帳時自行開傳票。");
        notes.add("不含政府補助（情境），補助核定入帳時請以傳票登錄於營業外收入【待會計師確認課稅方式】。");
        if (vouchers == 0) notes.add("尚未登錄任何傳票，成本費用與資產負債皆為 0；請至「傳票」分頁登錄開帳與實際收支。");
        if (!hasPeriod) notes.add("所選期間尚無資料，資產負債表為截至 " + asOf + " 的累計餘額。");
        else if (wholeYear && asOf.isBefore(to)) notes.add("年度尚未結束，累計至 " + asOf + "。");

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("period", period);
        out.put("label", wholeYear ? period + " 年度" : period);
        out.put("from", hasPeriod ? pf.toString() : null);
        out.put("to", first == null ? null : asOf.toString());
        out.put("months", hasPeriod ? (int) (asOf.getYear() * 12L + asOf.getMonthValue() - pf.getYear() * 12L - pf.getMonthValue() + 1) : 0);
        out.put("summary", summary);
        out.put("incomeStatement", is);
        out.put("balanceSheet", bs);
        out.put("balanceSheetBalanced", assets == liab + equity);
        out.put("cashFlow", cf);
        out.put("cashFlowReconciled", openCash + netCash == cash);
        out.put("notes", notes);
        return out;
    }

    /** 某個月份區間（null＝不設下限）的分錄彙總：借方／貸方淨額 */
    private static final class Ledger {
        private final Map<String, Long> net = new LinkedHashMap<>();   // 借方 − 貸方

        Ledger(List<Posting> all, YearMonth from, YearMonth to) {
            for (Posting p : all) {
                if (from != null && p.ym().isBefore(from)) continue;
                if (p.ym().isAfter(to)) continue;
                net.merge(p.account(), p.debit() - p.credit(), Long::sum);
            }
        }

        /** 借方餘額（借方科目的金額） */
        long debit(String code) { return net.getOrDefault(code, 0L); }

        /** 貸方餘額（貸方科目的金額） */
        long credit(String code) { return -net.getOrDefault(code, 0L); }

        /** 損益科目合計＝收入 − 費用 */
        long netIncome() {
            long s = 0;
            for (Map.Entry<String, Long> e : net.entrySet()) if (FinanceAccounts.get(e.getKey()).isPnl()) s -= e.getValue();
            return s;
        }
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
