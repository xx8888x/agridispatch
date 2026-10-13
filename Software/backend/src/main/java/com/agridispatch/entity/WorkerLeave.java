package com.agridispatch.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 工人請假（對應 DB: worker_leaves 表）
 *
 * 工人（或合作社代為）申請，合作社核准後才生效；核准的請假在派工媒合時視同撞期，
 * 該期間不列入出工名單、指名與共享班表。半日假（上午／下午）只佔用該時段。
 * 假別與給薪、天數上限依勞動基準法與勞工請假規則【待確認】，系統只記錄、不計薪。
 */
@Entity
@Table(name = "worker_leaves")
public class WorkerLeave {
    public static final String PENDING = "pending";
    public static final String APPROVED = "approved";
    public static final String REJECTED = "rejected";
    public static final String CANCELLED = "cancelled";

    @Id
    @Column(length = 32)
    private String id; // LV-2026-001

    @Column(name = "worker_id", nullable = false, length = 32)
    private String workerId;

    @Column(name = "leave_type", nullable = false, length = 20)
    private String leaveType;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    /** full_day / morning / afternoon（同 orders.time_slot）；半日假須單日 */
    @Column(name = "time_slot", nullable = false, length = 16)
    private String timeSlot;

    @Column(length = 200)
    private String reason;

    @Column(nullable = false, length = 20)
    private String status;

    /** 申請人帳號（工人本人或代為申請的管理員） */
    @Column(name = "requested_by", length = 64)
    private String requestedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** 核准／駁回／取消的操作人與時間 */
    @Column(name = "decided_by", length = 64)
    private String decidedBy;

    @Column(name = "decided_at")
    private LocalDateTime decidedAt;

    /** 駁回原因或核准備註 */
    @Column(name = "decision_note", length = 200)
    private String decisionNote;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (status == null) status = PENDING;
        if (timeSlot == null || timeSlot.isBlank()) timeSlot = Order.FULL_DAY;
    }

    public WorkerLeave() {}

    /** 以虛擬訂單表示請假佔用的期間與時段，供派工撞期檢查（Order.overlaps）使用 */
    public Order asHold() {
        Order o = new Order();
        o.setId(id);
        o.setStartDate(startDate);
        o.setEndDate(endDate);
        o.setTimeSlot(timeSlot);
        return o;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getWorkerId() { return workerId; }
    public void setWorkerId(String workerId) { this.workerId = workerId; }

    public String getLeaveType() { return leaveType; }
    public void setLeaveType(String leaveType) { this.leaveType = leaveType; }

    public LocalDate getStartDate() { return startDate; }
    public void setStartDate(LocalDate startDate) { this.startDate = startDate; }

    public LocalDate getEndDate() { return endDate; }
    public void setEndDate(LocalDate endDate) { this.endDate = endDate; }

    public String getTimeSlot() { return timeSlot; }
    public void setTimeSlot(String timeSlot) { this.timeSlot = timeSlot; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getRequestedBy() { return requestedBy; }
    public void setRequestedBy(String requestedBy) { this.requestedBy = requestedBy; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public String getDecidedBy() { return decidedBy; }
    public void setDecidedBy(String decidedBy) { this.decidedBy = decidedBy; }

    public LocalDateTime getDecidedAt() { return decidedAt; }
    public void setDecidedAt(LocalDateTime decidedAt) { this.decidedAt = decidedAt; }

    public String getDecisionNote() { return decisionNote; }
    public void setDecisionNote(String decisionNote) { this.decisionNote = decisionNote; }
}
