package com.agridispatch.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 月結算狀態（對應 DB: payroll_months 表）
 *
 * 只有結算過的月份才有資料列：settled 已結算（該月出勤鎖定、薪資單以 payroll_slips 快照為準）、
 * reopened 已重新開放（快照刪除，可再補登修正後重新結算）。沒有資料列的月份視為未結算。
 */
@Entity
@Table(name = "payroll_months")
public class PayrollMonth {
    public static final String SETTLED = "settled";
    public static final String REOPENED = "reopened";

    /** YYYY-MM（MONTH 是 H2 保留字，欄位名稱用 pay_month） */
    @Id
    @Column(name = "pay_month", length = 7)
    private String month;

    @Column(nullable = false, length = 10)
    private String status;

    @Column(name = "settled_at")
    private LocalDateTime settledAt;

    @Column(name = "settled_by", length = 64)
    private String settledBy;

    @Column(name = "worker_count")
    private Integer workerCount;

    /** 結算時的實領合計（元） */
    @Column(name = "total_net")
    private Long totalNet;

    @Column(name = "reopened_at")
    private LocalDateTime reopenedAt;

    @Column(name = "reopened_by", length = 64)
    private String reopenedBy;

    @Column(name = "reopen_reason", length = 200)
    private String reopenReason;

    public PayrollMonth() {}

    public PayrollMonth(String month) {
        this.month = month;
    }

    public boolean isSettled() { return SETTLED.equals(status); }

    public String getMonth() { return month; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public LocalDateTime getSettledAt() { return settledAt; }
    public void setSettledAt(LocalDateTime settledAt) { this.settledAt = settledAt; }
    public String getSettledBy() { return settledBy; }
    public void setSettledBy(String settledBy) { this.settledBy = settledBy; }
    public Integer getWorkerCount() { return workerCount; }
    public void setWorkerCount(Integer workerCount) { this.workerCount = workerCount; }
    public Long getTotalNet() { return totalNet; }
    public void setTotalNet(Long totalNet) { this.totalNet = totalNet; }
    public LocalDateTime getReopenedAt() { return reopenedAt; }
    public void setReopenedAt(LocalDateTime reopenedAt) { this.reopenedAt = reopenedAt; }
    public String getReopenedBy() { return reopenedBy; }
    public void setReopenedBy(String reopenedBy) { this.reopenedBy = reopenedBy; }
    public String getReopenReason() { return reopenReason; }
    public void setReopenReason(String reopenReason) { this.reopenReason = reopenReason; }
}
