package com.agridispatch.service;

import com.agridispatch.common.ApiException;
import com.agridispatch.entity.FinanceVoucher;
import com.agridispatch.entity.FinanceVoucherLine;
import com.agridispatch.repository.FinanceVoucherLineRepository;
import com.agridispatch.repository.FinanceVoucherRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 傳票（實際收支）：登錄、作廢、查詢，並供財務報表取得已生效的分錄。
 *
 * 規則：借貸須平衡、每列只填借方或貸方、科目須在科目表內且不得為系統自動入帳科目（訂單營收與應收）；
 * 傳票不可修改，錯誤以作廢處理（保留紀錄與原因），再重新登錄。
 */
@Service
public class FinanceVoucherService {

    /** 單列金額上限（元）：擋掉誤植位數 */
    static final long MAX_AMOUNT = 1_000_000_000L;

    public record Line(String account, long debit, long credit, String note) {}

    /** 一筆生效中的分錄（月份粒度） */
    public record Posting(YearMonth ym, String account, long debit, long credit) {}

    private final FinanceVoucherRepository voucherRepository;
    private final FinanceVoucherLineRepository lineRepository;

    public FinanceVoucherService(FinanceVoucherRepository voucherRepository, FinanceVoucherLineRepository lineRepository) {
        this.voucherRepository = voucherRepository;
        this.lineRepository = lineRepository;
    }

    public List<Map<String, Object>> accounts() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (FinanceAccounts.Account a : FinanceAccounts.ALL) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", a.code());
            m.put("name", a.name());
            m.put("kind", a.kind());
            m.put("system", a.system());
            out.add(m);
        }
        return out;
    }

    /** 傳票清單（新到舊）；month = YYYY-MM 只列該月 */
    public List<Map<String, Object>> list(String month) {
        YearMonth ym = null;
        if (month != null && !month.isBlank()) {
            try { ym = YearMonth.parse(month); } catch (DateTimeParseException e) { throw ApiException.badRequest("month 格式須為 YYYY-MM"); }
        }
        final YearMonth filter = ym;
        List<FinanceVoucher> vs = voucherRepository.findAll().stream()
                .filter(v -> filter == null || YearMonth.from(v.getVoucherDate()).equals(filter))
                .sorted((a, b) -> {
                    int c = b.getVoucherDate().compareTo(a.getVoucherDate());
                    return c != 0 ? c : Long.compare(b.getId(), a.getId());
                }).toList();
        return vs.stream().map(this::view).toList();
    }

    @Transactional
    public Map<String, Object> create(String operator, Map<String, Object> body) {
        LocalDate date;
        try { date = LocalDate.parse(String.valueOf(body.get("date"))); } catch (DateTimeParseException e) { throw ApiException.badRequest("傳票日期格式須為 YYYY-MM-DD"); }
        if (date.getYear() < 2000 || date.getYear() > 2100) throw ApiException.badRequest("傳票日期不合理");
        String memo = body.get("memo") == null ? "" : String.valueOf(body.get("memo")).trim();
        if (memo.isEmpty()) throw ApiException.badRequest("請填寫傳票摘要");
        if (memo.length() > 200) throw ApiException.badRequest("摘要最多 200 字");
        if (!(body.get("lines") instanceof List<?> raw)) throw ApiException.badRequest("缺少傳票明細");
        List<Line> lines = new ArrayList<>();
        for (Object o : raw) {
            if (!(o instanceof Map<?, ?> m)) throw ApiException.badRequest("傳票明細格式錯誤");
            String note = m.get("note") == null ? null : String.valueOf(m.get("note")).trim();
            if (note != null && note.length() > 100) throw ApiException.badRequest("明細備註最多 100 字");
            lines.add(new Line(String.valueOf(m.get("account")), amount(m.get("debit")), amount(m.get("credit")), note == null || note.isEmpty() ? null : note));
        }
        return view(save(operator, date, memo, lines));
    }

    @Transactional
    public Map<String, Object> voidVoucher(String operator, Long id, String reason) {
        FinanceVoucher v = voucherRepository.findById(id).orElseThrow(() -> ApiException.notFound("找不到傳票：" + id));
        if (!v.isActive()) throw ApiException.conflict("傳票已作廢");
        String r = reason == null ? "" : reason.trim();
        if (r.isEmpty()) throw ApiException.badRequest("請填寫作廢原因");
        if (r.length() > 200) throw ApiException.badRequest("作廢原因最多 200 字");
        v.setStatus(FinanceVoucher.VOID);
        v.setVoidReason(r);
        v.setVoidedBy(operator);
        v.setVoidedAt(LocalDateTime.now());
        return view(voucherRepository.save(v));
    }

    /** 已生效傳票的分錄（報表用） */
    public List<Posting> postings() {
        Set<Long> active = voucherRepository.findAll().stream().filter(FinanceVoucher::isActive)
                .map(FinanceVoucher::getId).collect(Collectors.toSet());
        Map<Long, YearMonth> month = voucherRepository.findAll().stream()
                .collect(Collectors.toMap(FinanceVoucher::getId, v -> YearMonth.from(v.getVoucherDate())));
        List<Posting> out = new ArrayList<>();
        for (FinanceVoucherLine l : lineRepository.findAll()) {
            if (active.contains(l.getVoucherId())) out.add(new Posting(month.get(l.getVoucherId()), l.getAccountCode(), l.getDebit(), l.getCredit()));
        }
        return out;
    }

    public long activeCount() {
        return voucherRepository.findAll().stream().filter(FinanceVoucher::isActive).count();
    }

    /** 重灌示範資料用：清空傳票（明細先刪） */
    @Transactional
    public void deleteAll() {
        lineRepository.deleteAll();
        voucherRepository.deleteAll();
    }

    /** 示範資料：籌備期結束的開帳、設備購置，與 2026-09、2026-10 依 F2 v2.4 第一年預算月額列帳的營業成本費用（已有傳票就不動） */
    @Transactional
    public void seedDemo() {
        if (voucherRepository.count() > 0) return;
        String by = "demo-seed";
        save(by, LocalDate.of(2026, 9, 1), "期初開帳：青創貸款 100 萬、股本 10 萬、開辦費 37 萬", List.of(
                new Line("1101", 730_000, 0, null), new Line("3351", 370_000, 0, "開辦費（費用化）"),
                new Line("2501", 0, 1_000_000, null), new Line("3101", 0, 100_000, null)));
        save(by, LocalDate.of(2026, 9, 1), "購置伺服器與辦公設備", List.of(
                new Line("1501", 280_000, 0, null), new Line("1101", 0, 280_000, null)));
        for (LocalDate d : new LocalDate[]{LocalDate.of(2026, 9, 30), LocalDate.of(2026, 10, 15)}) {
            String m = d.getYear() + " 年 " + d.getMonthValue() + " 月";
            save(by, d, m + "營業成本與費用（F2 預算月額，示範）", List.of(
                    new Line("5101", 6_000, 0, null), new Line("5102", 4_583, 0, null), new Line("5103", 8_000, 0, null),
                    new Line("6101", 60_000, 0, null), new Line("6102", 15_000, 0, null), new Line("6103", 15_000, 0, null),
                    new Line("6104", 10_000, 0, null), new Line("6105", 8_000, 0, null),
                    new Line("1101", 0, 126_583, "現金付款")));
            save(by, d, m + "折舊", List.of(new Line("6201", 5_000, 0, null), new Line("1509", 0, 5_000, null)));
        }
    }

    // ------------------------------------------------------------------

    private FinanceVoucher save(String operator, LocalDate date, String memo, List<Line> lines) {
        if (lines.size() < 2 || lines.size() > 30) throw ApiException.badRequest("傳票明細須有 2～30 列");
        long debit = 0, credit = 0;
        for (Line l : lines) {
            FinanceAccounts.Account a = FinanceAccounts.get(l.account());
            if (a == null) throw ApiException.badRequest("不存在的會計科目：" + l.account());
            if (a.system()) throw ApiException.badRequest("科目 " + a.code() + " " + a.name() + " 由訂單自動入帳，不可用傳票登錄");
            if ((l.debit() > 0) == (l.credit() > 0)) throw ApiException.badRequest("每列只能填借方或貸方其中一邊（科目 " + a.code() + "）");
            debit += l.debit();
            credit += l.credit();
        }
        if (debit != credit) throw ApiException.badRequest("借貸不平衡：借方 " + debit + "，貸方 " + credit);
        FinanceVoucher v = new FinanceVoucher();
        v.setVoucherDate(date);
        v.setMemo(memo);
        v.setCreatedBy(operator);
        v.setVoucherNo(nextNo(YearMonth.from(date)));
        v = voucherRepository.save(v);
        int no = 1;
        for (Line l : lines) lineRepository.save(new FinanceVoucherLine(v.getId(), no++, l.account(), l.debit(), l.credit(), l.note()));
        return v;
    }

    private String nextNo(YearMonth ym) {
        String prefix = "V" + ym.getYear() + String.format("%02d", ym.getMonthValue()) + "-";
        int max = 0;
        for (String no : voucherRepository.findNosByPrefix(prefix)) {
            try { max = Math.max(max, Integer.parseInt(no.substring(prefix.length()))); } catch (RuntimeException ignored) { }
        }
        return prefix + String.format("%03d", max + 1);
    }

    /** 金額欄：空白視為 0；只收 0～10 億的整數（元） */
    private static long amount(Object o) {
        if (o == null || String.valueOf(o).isBlank()) return 0;
        try {
            java.math.BigDecimal d = new java.math.BigDecimal(String.valueOf(o).trim());
            if (d.signum() < 0 || d.stripTrailingZeros().scale() > 0) throw ApiException.badRequest("金額須為 0 以上的整數（元）");
            if (d.compareTo(java.math.BigDecimal.valueOf(MAX_AMOUNT)) > 0) throw ApiException.badRequest("單列金額不可超過 10 億元");
            return d.longValueExact();
        } catch (NumberFormatException e) {
            throw ApiException.badRequest("金額格式錯誤");
        }
    }

    private Map<String, Object> view(FinanceVoucher v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", v.getId());
        m.put("voucherNo", v.getVoucherNo());
        m.put("date", v.getVoucherDate().toString());
        m.put("memo", v.getMemo());
        m.put("status", v.getStatus());
        m.put("createdBy", v.getCreatedBy());
        m.put("createdAt", v.getCreatedAt().toString());
        m.put("voidReason", v.getVoidReason());
        m.put("voidedBy", v.getVoidedBy());
        m.put("voidedAt", v.getVoidedAt() == null ? null : v.getVoidedAt().toString());
        List<Map<String, Object>> ls = new ArrayList<>();
        long total = 0;
        for (FinanceVoucherLine l : lineRepository.findByVoucherIdOrderByLineNo(v.getId())) {
            Map<String, Object> lm = new LinkedHashMap<>();
            lm.put("lineNo", l.getLineNo());
            lm.put("account", l.getAccountCode());
            FinanceAccounts.Account a = FinanceAccounts.get(l.getAccountCode());
            lm.put("accountName", a == null ? l.getAccountCode() : a.name());
            lm.put("debit", l.getDebit());
            lm.put("credit", l.getCredit());
            lm.put("note", l.getNote());
            ls.add(lm);
            total += l.getDebit();
        }
        m.put("lines", ls);
        m.put("total", total);
        return m;
    }
}
