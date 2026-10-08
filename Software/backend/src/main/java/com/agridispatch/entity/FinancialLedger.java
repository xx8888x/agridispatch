package com.agridispatch.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Column;
import jakarta.persistence.PrePersist;
import java.time.LocalDateTime;

/**
 * 雙軌利潤分流帳本實體 (對應 DB: financial_ledger 表)
 * 紀錄科技公司 16% 代管費與外展合作社 84% 人事成本分流結算（F2 v2.4；v2.3 以前為 18%／82%）
 */
@Entity
@Table(name = "financial_ledger")
public class FinancialLedger {
    @Id
    private String id;

    @Column(name = "order_id", nullable = false, length = 32)
    private String orderId;

    @Column(name = "total_gmv", nullable = false)
    private long totalGmv;

    @Column(name = "tech_take_rate", nullable = false)
    private long techTakeRate;

    @Column(name = "coop_pass_through", nullable = false)
    private long coopPassThrough;

    @Column(name = "total_agri_subsidy", nullable = false)
    private long totalAgriSubsidy;

    @Column(name = "settlement_date", nullable = false)
    private LocalDateTime settlementDate;

    @PrePersist
    protected void onCreate() {
        if (settlementDate == null) {
            settlementDate = LocalDateTime.now();
        }
    }

    public FinancialLedger() {}

    public FinancialLedger(String id, String orderId, long totalGmv, long techTakeRate,
                           long coopPassThrough, long totalAgriSubsidy) {
        this.id = id;
        this.orderId = orderId;
        this.totalGmv = totalGmv;
        this.techTakeRate = techTakeRate;
        this.coopPassThrough = coopPassThrough;
        this.totalAgriSubsidy = totalAgriSubsidy;
        this.settlementDate = LocalDateTime.now();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getOrderId() { return orderId; }
    public void setOrderId(String orderId) { this.orderId = orderId; }

    public long getTotalGmv() { return totalGmv; }
    public void setTotalGmv(long totalGmv) { this.totalGmv = totalGmv; }

    public long getTechTakeRate() { return techTakeRate; }
    public void setTechTakeRate(long techTakeRate) { this.techTakeRate = techTakeRate; }

    public long getCoopPassThrough() { return coopPassThrough; }
    public void setCoopPassThrough(long coopPassThrough) { this.coopPassThrough = coopPassThrough; }

    public long getTotalAgriSubsidy() { return totalAgriSubsidy; }
    public void setTotalAgriSubsidy(long totalAgriSubsidy) { this.totalAgriSubsidy = totalAgriSubsidy; }

    public LocalDateTime getSettlementDate() { return settlementDate; }
    public void setSettlementDate(LocalDateTime settlementDate) { this.settlementDate = settlementDate; }
}
