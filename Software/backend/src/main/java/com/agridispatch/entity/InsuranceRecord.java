package com.agridispatch.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 工人加退保紀錄：每一段投保期間一筆（加保 → 退保），歷史紀錄保留不覆蓋
 * 實際申報由合作社於勞保局／健保署系統辦理，本表記錄申報結果與受理編號
 */
@Entity
@Table(name = "insurance_records")
public class InsuranceRecord {

    /** INS-<工人編號>-<險種>-<加保日 yyyyMMdd> */
    @Id
    @Column(length = 60)
    private String id;

    @Column(name = "worker_id", nullable = false, length = 32)
    private String workerId;

    /** labor 勞保 / employment 就保 / occupational 職保 / health 健保 */
    @Column(name = "insurance_type", nullable = false, length = 16)
    private String type;

    /** 月投保薪資（健保為投保金額），新台幣元 */
    @Column(name = "insured_salary", nullable = false)
    private int insuredSalary;

    @Column(name = "enroll_date", nullable = false)
    private LocalDate enrollDate;

    /** 退保日：自該日起不再承保；null 表示仍在保 */
    @Column(name = "withdraw_date")
    private LocalDate withdrawDate;

    @Column(name = "withdraw_reason", length = 100)
    private String withdrawReason;

    /** 勞保局／健保署申報受理編號（選填） */
    @Column(name = "filing_ref", length = 40)
    private String filingRef;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public InsuranceRecord() {}

    /** 指定日期是否在保（加保日當天起、退保日當天止不再承保） */
    public boolean isActiveOn(LocalDate date) {
        return !enrollDate.isAfter(date) && (withdrawDate == null || date.isBefore(withdrawDate));
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getWorkerId() { return workerId; }
    public void setWorkerId(String workerId) { this.workerId = workerId; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public int getInsuredSalary() { return insuredSalary; }
    public void setInsuredSalary(int insuredSalary) { this.insuredSalary = insuredSalary; }
    public LocalDate getEnrollDate() { return enrollDate; }
    public void setEnrollDate(LocalDate enrollDate) { this.enrollDate = enrollDate; }
    public LocalDate getWithdrawDate() { return withdrawDate; }
    public void setWithdrawDate(LocalDate withdrawDate) { this.withdrawDate = withdrawDate; }
    public String getWithdrawReason() { return withdrawReason; }
    public void setWithdrawReason(String withdrawReason) { this.withdrawReason = withdrawReason; }
    public String getFilingRef() { return filingRef; }
    public void setFilingRef(String filingRef) { this.filingRef = filingRef; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
