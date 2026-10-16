package com.agridispatch.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 工人出勤補登／修正申請（對應 DB: attendance_requests 表）
 *
 * 工人本人發現漏打卡或忘記下班時送出申請（補登一筆新出勤，或修正自己某筆出勤的上下班時間），
 * 合作社（科技後台）審核後才寫入出勤：核准時由 PayrollService 補登／修正並留 attendance_corrections 紀錄，
 * 駁回須填原因。申請本身不改任何出勤與薪資。時間以 HH:mm 文字存放，下班早於上班視為隔天。
 */
@Entity
@Table(name = "attendance_requests")
public class AttendanceRequest {
    public static final String CREATE = "create";
    public static final String UPDATE = "update";

    public static final String PENDING = "pending";
    public static final String APPROVED = "approved";
    public static final String REJECTED = "rejected";
    public static final String CANCELLED = "cancelled";

    @Id
    @Column(length = 32)
    private String id; // AR-2026-001

    @Column(name = "worker_id", nullable = false, length = 32)
    private String workerId;

    /** create 補登新出勤 / update 修正既有出勤 */
    @Column(nullable = false, length = 10)
    private String kind;

    /** kind = update 時，要修正的出勤紀錄（clock_ins.id） */
    @Column(name = "attendance_id")
    private Long attendanceId;

    @Column(name = "work_date", nullable = false)
    private LocalDate workDate;

    /** 申請的上班時間 HH:mm；修正時可空（維持原值） */
    @Column(name = "clock_in", length = 5)
    private String clockIn;

    /** 申請的下班時間 HH:mm；修正時可空（維持原值） */
    @Column(name = "clock_out", length = 5)
    private String clockOut;

    @Column(name = "order_id", length = 32)
    private String orderId;

    @Column(nullable = false, length = 200)
    private String reason;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "requested_by", length = 64)
    private String requestedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** 核准／駁回／取消的操作人與時間 */
    @Column(name = "decided_by", length = 64)
    private String decidedBy;

    @Column(name = "decided_at")
    private LocalDateTime decidedAt;

    @Column(name = "decision_note", length = 200)
    private String decisionNote;

    /** 核准後寫入或修正的出勤紀錄 */
    @Column(name = "result_attendance_id")
    private Long resultAttendanceId;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }

    public AttendanceRequest() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getWorkerId() { return workerId; }
    public void setWorkerId(String workerId) { this.workerId = workerId; }
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public Long getAttendanceId() { return attendanceId; }
    public void setAttendanceId(Long attendanceId) { this.attendanceId = attendanceId; }
    public LocalDate getWorkDate() { return workDate; }
    public void setWorkDate(LocalDate workDate) { this.workDate = workDate; }
    public String getClockIn() { return clockIn; }
    public void setClockIn(String clockIn) { this.clockIn = clockIn; }
    public String getClockOut() { return clockOut; }
    public void setClockOut(String clockOut) { this.clockOut = clockOut; }
    public String getOrderId() { return orderId; }
    public void setOrderId(String orderId) { this.orderId = orderId; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getRequestedBy() { return requestedBy; }
    public void setRequestedBy(String requestedBy) { this.requestedBy = requestedBy; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public String getDecidedBy() { return decidedBy; }
    public void setDecidedBy(String decidedBy) { this.decidedBy = decidedBy; }
    public LocalDateTime getDecidedAt() { return decidedAt; }
    public void setDecidedAt(LocalDateTime decidedAt) { this.decidedAt = decidedAt; }
    public String getDecisionNote() { return decisionNote; }
    public void setDecisionNote(String decisionNote) { this.decisionNote = decisionNote; }
    public Long getResultAttendanceId() { return resultAttendanceId; }
    public void setResultAttendanceId(Long resultAttendanceId) { this.resultAttendanceId = resultAttendanceId; }
}
