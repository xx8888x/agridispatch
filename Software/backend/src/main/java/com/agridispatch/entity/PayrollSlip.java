package com.agridispatch.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 月結算薪資單快照（對應 DB: payroll_slips 表）
 *
 * 月結算時依當月出勤與工人資料計算（WorkerService.slipFor）後存下，之後工人資料或出勤再變動也不影響；
 * 重新開放該月時刪除。勞健保自付額仍為概估（deductions_estimated='Y'），實際以合作社薪資系統為準。
 */
@Entity
@Table(name = "payroll_slips")
public class PayrollSlip {

    /** PS-YYYYMM-工號，例 PS-202609-W026 */
    @Id
    @Column(length = 40)
    private String id;

    /** YYYY-MM（MONTH 是 H2 保留字，欄位名稱用 pay_month） */
    @Column(name = "pay_month", nullable = false, length = 7)
    private String month;

    @Column(name = "worker_id", nullable = false, length = 32)
    private String workerId;

    @Column(name = "worker_name", length = 100)
    private String workerName;

    @Column(length = 30)
    private String tier;

    @Column(name = "work_days")
    private int workDays;

    @Column(name = "worked_hours")
    private double workedHours;

    @Column(name = "overtime_hours")
    private double overtimeHours;

    @Column(name = "base_salary")
    private int baseSalary;

    @Column(name = "agri_subsidy")
    private int agriSubsidy;

    @Column(name = "transport_allowance")
    private int transportAllowance;

    @Column(name = "overtime_pay")
    private int overtimePay;

    @Column(name = "labor_insurance")
    private int laborInsurance;

    @Column(name = "health_insurance")
    private int healthInsurance;

    @Column(name = "gross_pay")
    private int grossPay;

    @Column(name = "total_deductions")
    private int totalDeductions;

    @Column(name = "net_pay")
    private int netPay;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }

    public PayrollSlip() {}

    public static String idOf(String month, String workerId) {
        return "PS-" + month.replace("-", "") + "-" + workerId;
    }

    /** 與 WorkerService 試算薪資單相同的欄位名稱，前端與 LINE 不必分兩套 */
    public Map<String, Object> toSlipMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("slipId", id);
        m.put("period", month.substring(0, 4) + "年" + month.substring(5) + "月");
        m.put("month", month);
        m.put("workDays", workDays);
        m.put("workedHours", workedHours);
        m.put("overtimeHours", overtimeHours);
        m.put("baseSalary", baseSalary);
        m.put("agriSubsidy", agriSubsidy);
        m.put("transportHeatAllowance", transportAllowance);
        m.put("overtimePay", overtimePay);
        m.put("laborInsuranceDeduction", laborInsurance);
        m.put("healthInsuranceDeduction", healthInsurance);
        m.put("taxWithholding", 0);
        m.put("grossPay", grossPay);
        m.put("totalDeductions", totalDeductions);
        m.put("netPay", netPay);
        m.put("status", "已結算");
        m.put("settled", true);
        m.put("simulated", false);
        m.put("deductionsEstimated", true);
        return m;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getMonth() { return month; }
    public void setMonth(String month) { this.month = month; }
    public String getWorkerId() { return workerId; }
    public void setWorkerId(String workerId) { this.workerId = workerId; }
    public String getWorkerName() { return workerName; }
    public void setWorkerName(String workerName) { this.workerName = workerName; }
    public String getTier() { return tier; }
    public void setTier(String tier) { this.tier = tier; }
    public int getWorkDays() { return workDays; }
    public void setWorkDays(int workDays) { this.workDays = workDays; }
    public double getWorkedHours() { return workedHours; }
    public void setWorkedHours(double workedHours) { this.workedHours = workedHours; }
    public double getOvertimeHours() { return overtimeHours; }
    public void setOvertimeHours(double overtimeHours) { this.overtimeHours = overtimeHours; }
    public int getBaseSalary() { return baseSalary; }
    public void setBaseSalary(int baseSalary) { this.baseSalary = baseSalary; }
    public int getAgriSubsidy() { return agriSubsidy; }
    public void setAgriSubsidy(int agriSubsidy) { this.agriSubsidy = agriSubsidy; }
    public int getTransportAllowance() { return transportAllowance; }
    public void setTransportAllowance(int transportAllowance) { this.transportAllowance = transportAllowance; }
    public int getOvertimePay() { return overtimePay; }
    public void setOvertimePay(int overtimePay) { this.overtimePay = overtimePay; }
    public int getLaborInsurance() { return laborInsurance; }
    public void setLaborInsurance(int laborInsurance) { this.laborInsurance = laborInsurance; }
    public int getHealthInsurance() { return healthInsurance; }
    public void setHealthInsurance(int healthInsurance) { this.healthInsurance = healthInsurance; }
    public int getGrossPay() { return grossPay; }
    public void setGrossPay(int grossPay) { this.grossPay = grossPay; }
    public int getTotalDeductions() { return totalDeductions; }
    public void setTotalDeductions(int totalDeductions) { this.totalDeductions = totalDeductions; }
    public int getNetPay() { return netPay; }
    public void setNetPay(int netPay) { this.netPay = netPay; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
