package com.agridispatch.entity;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 傳票表頭（對應 DB: finance_vouchers 表）：科技後台登錄的實際收支，是財務報表成本費用的來源。
 *
 * 借貸須平衡（FinanceVoucherService 檢查）；傳票不可修改，登錄錯誤以作廢（void）處理並保留紀錄，再重新登錄。
 * 明細見 {@link FinanceVoucherLine}。
 */
@Entity
@Table(name = "finance_vouchers")
public class FinanceVoucher {
    public static final String ACTIVE = "active";
    public static final String VOID = "void";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 傳票號碼 Vyyyymm-nnn（依傳票日期的月份流水） */
    @Column(name = "voucher_no", nullable = false, length = 16)
    private String voucherNo;

    @Column(name = "voucher_date", nullable = false)
    private LocalDate voucherDate;

    @Column(nullable = false, length = 200)
    private String memo;

    /** active／void */
    @Column(nullable = false, length = 10)
    private String status = ACTIVE;

    @Column(name = "created_by", nullable = false, length = 64)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "void_reason", length = 200)
    private String voidReason;

    @Column(name = "voided_by", length = 64)
    private String voidedBy;

    @Column(name = "voided_at")
    private LocalDateTime voidedAt;

    public FinanceVoucher() {}

    public boolean isActive() { return ACTIVE.equals(status); }

    public Long getId() { return id; }
    public String getVoucherNo() { return voucherNo; }
    public void setVoucherNo(String voucherNo) { this.voucherNo = voucherNo; }
    public LocalDate getVoucherDate() { return voucherDate; }
    public void setVoucherDate(LocalDate voucherDate) { this.voucherDate = voucherDate; }
    public String getMemo() { return memo; }
    public void setMemo(String memo) { this.memo = memo; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public String getVoidReason() { return voidReason; }
    public void setVoidReason(String voidReason) { this.voidReason = voidReason; }
    public String getVoidedBy() { return voidedBy; }
    public void setVoidedBy(String voidedBy) { this.voidedBy = voidedBy; }
    public LocalDateTime getVoidedAt() { return voidedAt; }
    public void setVoidedAt(LocalDateTime voidedAt) { this.voidedAt = voidedAt; }
}
