package com.agridispatch.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 科技公司會計科目表（固定，報表與傳票共用）。
 *
 * kind：asset 資產（借方餘額）、contra 資產減項（貸方餘額，累計折舊）、liability 負債、equity 權益、
 * revenue 營業收入、cogs 營業成本、opex 營業費用、dep 折舊與攤銷、interest 利息費用、nonop 營業外收入、tax 所得稅。
 * system＝由系統依訂單自動入帳，傳票不可使用（避免與訂單營收重複）。
 */
public final class FinanceAccounts {

    public record Account(String code, String name, String kind, boolean system) {
        /** 損益科目（4～8 開頭） */
        public boolean isPnl() { return code.charAt(0) >= '4'; }
    }

    public static final String CASH = "1101", AR = "1121", FIXED = "1501", ACC_DEP = "1509",
            AP = "2101", TAX_PAYABLE = "2102", LOAN = "2501", CAPITAL = "3101", RETAINED = "3351",
            FEE_REVENUE = "4101", OTHER_REVENUE = "4201", DEPRECIATION = "6201", INTEREST = "7101",
            NONOP_INCOME = "7201", INCOME_TAX = "8101";

    public static final List<Account> ALL = List.of(
            new Account("1101", "現金及銀行存款", "asset", false),
            new Account("1121", "應收帳款（代管費）", "asset", true),
            new Account("1501", "固定資產（伺服器與設備）", "asset", false),
            new Account("1509", "累計折舊", "contra", false),
            new Account("2101", "應付帳款", "liability", false),
            new Account("2102", "應付所得稅", "liability", false),
            new Account("2501", "長期借款（青創貸款）", "liability", false),
            new Account("3101", "股本", "equity", false),
            new Account("3351", "累積損益（開帳）", "equity", false),
            new Account("4101", "系統代管與調度服務費", "revenue", true),
            new Account("4201", "其他營業收入（價差、加值服務）", "revenue", false),
            new Account("5101", "雲端伺服器與 API", "cogs", false),
            new Account("5102", "承攬工班品管與保險補貼", "cogs", false),
            new Account("5103", "現場調度幹部差旅與安全耗材", "cogs", false),
            new Account("6101", "創辦團隊與後台研發薪資", "opex", false),
            new Account("6102", "雙語舍監與兼職通譯津貼", "opex", false),
            new Account("6103", "本土農工與兼職招募推廣費", "opex", false),
            new Account("6104", "法律顧問、會計與合規規費", "opex", false),
            new Account("6105", "辦公室租金與水電雜支", "opex", false),
            new Account("6106", "其他營業費用", "opex", false),
            new Account("6201", "折舊與攤銷", "dep", false),
            new Account("7101", "利息費用", "interest", false),
            new Account("7201", "營業外收入（補助、利息收入）", "nonop", false),
            new Account("8101", "營利事業所得稅", "tax", false));

    private static final Map<String, Account> BY_CODE = new LinkedHashMap<>();
    static { for (Account a : ALL) BY_CODE.put(a.code(), a); }

    private FinanceAccounts() {}

    public static Account get(String code) { return BY_CODE.get(code); }

    public static List<Account> ofKind(String kind) {
        return ALL.stream().filter(a -> a.kind().equals(kind)).toList();
    }
}
