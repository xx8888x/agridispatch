package com.agridispatch.entity;

import jakarta.persistence.*;

/**
 * 傳票明細（對應 DB: finance_voucher_lines 表）：一列只有借方或貸方其中一邊有金額（元，整數）。
 */
@Entity
@Table(name = "finance_voucher_lines")
public class FinanceVoucherLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "voucher_id", nullable = false)
    private Long voucherId;

    @Column(name = "line_no", nullable = false)
    private int lineNo;

    /** 會計科目代號（FinanceAccounts） */
    @Column(name = "account_code", nullable = false, length = 8)
    private String accountCode;

    @Column(nullable = false)
    private long debit;

    @Column(nullable = false)
    private long credit;

    @Column(length = 100)
    private String note;

    public FinanceVoucherLine() {}

    public FinanceVoucherLine(Long voucherId, int lineNo, String accountCode, long debit, long credit, String note) {
        this.voucherId = voucherId;
        this.lineNo = lineNo;
        this.accountCode = accountCode;
        this.debit = debit;
        this.credit = credit;
        this.note = note;
    }

    public Long getId() { return id; }
    public Long getVoucherId() { return voucherId; }
    public int getLineNo() { return lineNo; }
    public String getAccountCode() { return accountCode; }
    public long getDebit() { return debit; }
    public long getCredit() { return credit; }
    public String getNote() { return note; }
}
