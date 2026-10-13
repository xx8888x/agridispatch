package com.agridispatch.entity;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 出勤補登／修正紀錄（對應 DB: attendance_corrections 表）
 *
 * 科技後台每次補登（create）或修正（update）一筆 clock_ins，都留一筆改前、改後時間、原因與操作人；
 * 不可修改、不可刪除，供薪資爭議與勞檢查核。已月結算的月份不可再補登或修正（PayrollService）。
 */
@Entity
@Table(name = "attendance_corrections")
public class AttendanceCorrection {
    public static final String CREATE = "create";
    public static final String UPDATE = "update";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 被補登／修正的出勤紀錄（clock_ins.id） */
    @Column(name = "clock_in_id", nullable = false)
    private Long clockInId;

    @Column(name = "worker_id", nullable = false, length = 32)
    private String workerId;

    @Column(name = "work_date", nullable = false)
    private LocalDate workDate;

    /** create 補登 / update 修正 */
    @Column(nullable = false, length = 10)
    private String action;

    @Column(name = "before_clock_in")
    private LocalDateTime beforeClockIn;

    @Column(name = "before_clock_out")
    private LocalDateTime beforeClockOut;

    @Column(name = "after_clock_in", nullable = false)
    private LocalDateTime afterClockIn;

    @Column(name = "after_clock_out", nullable = false)
    private LocalDateTime afterClockOut;

    @Column(nullable = false, length = 200)
    private String reason;

    /** 操作人帳號（token sub） */
    @Column(nullable = false, length = 64)
    private String operator;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }

    public AttendanceCorrection() {}

    public Long getId() { return id; }
    public Long getClockInId() { return clockInId; }
    public void setClockInId(Long clockInId) { this.clockInId = clockInId; }
    public String getWorkerId() { return workerId; }
    public void setWorkerId(String workerId) { this.workerId = workerId; }
    public LocalDate getWorkDate() { return workDate; }
    public void setWorkDate(LocalDate workDate) { this.workDate = workDate; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public LocalDateTime getBeforeClockIn() { return beforeClockIn; }
    public void setBeforeClockIn(LocalDateTime beforeClockIn) { this.beforeClockIn = beforeClockIn; }
    public LocalDateTime getBeforeClockOut() { return beforeClockOut; }
    public void setBeforeClockOut(LocalDateTime beforeClockOut) { this.beforeClockOut = beforeClockOut; }
    public LocalDateTime getAfterClockIn() { return afterClockIn; }
    public void setAfterClockIn(LocalDateTime afterClockIn) { this.afterClockIn = afterClockIn; }
    public LocalDateTime getAfterClockOut() { return afterClockOut; }
    public void setAfterClockOut(LocalDateTime afterClockOut) { this.afterClockOut = afterClockOut; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getOperator() { return operator; }
    public void setOperator(String operator) { this.operator = operator; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
