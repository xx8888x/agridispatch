package com.agridispatch.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * 刪除帳號申請（對應 DB: account_deletion_requests 表；Google Play 刪除帳號規定）
 *
 * 兩種來源：
 *   app  已登入的使用者在「我的帳號」申請，username／refId／role 取自登入身分
 *   web  免登入的網頁申請（不用裝 App 也能申請），只有使用者自填的姓名、電話、帳號，由合作社核對身分後指定要刪的帳號
 * 狀態：pending 待處理 → completed 已刪除／rejected 不受理；使用者可撤回 pending（withdrawn）。
 * 刪除時保留依法須保存的出勤、工資、訂單與付款紀錄（AccountDeletionService）。
 */
@Entity
@Table(name = "account_deletion_requests")
public class AccountDeletionRequest {
    public static final String PENDING = "pending";
    public static final String COMPLETED = "completed";
    public static final String REJECTED = "rejected";
    public static final String WITHDRAWN = "withdrawn";

    @Id
    @Column(length = 32)
    private String id; // DR-2026-001

    @Column(nullable = false, length = 8)
    private String source; // app / web

    @Column(length = 64)
    private String username; // app：登入帳號；web：完成時填入實際刪除的帳號

    @Column(name = "ref_id", length = 32)
    private String refId;

    @Column(length = 32)
    private String role;

    @Column(name = "contact_name", length = 50)
    private String contactName;

    @Column(name = "contact_phone", length = 30)
    private String contactPhone;

    @Column(name = "account_hint", length = 64)
    private String accountHint; // web：使用者自填的帳號／工號／手機

    @Column(length = 500)
    private String reason;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "decided_at")
    private LocalDateTime decidedAt;

    @Column(name = "decided_by", length = 64)
    private String decidedBy;

    @Column(name = "decision_note", length = 500)
    private String decisionNote;

    @Column(name = "result_summary", length = 500)
    private String resultSummary; // 完成時實際做了什麼（供日後查核）

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (status == null || status.isEmpty()) status = PENDING;
    }

    public AccountDeletionRequest() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getRefId() { return refId; }
    public void setRefId(String refId) { this.refId = refId; }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

    public String getContactName() { return contactName; }
    public void setContactName(String contactName) { this.contactName = contactName; }

    public String getContactPhone() { return contactPhone; }
    public void setContactPhone(String contactPhone) { this.contactPhone = contactPhone; }

    public String getAccountHint() { return accountHint; }
    public void setAccountHint(String accountHint) { this.accountHint = accountHint; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getDecidedAt() { return decidedAt; }
    public void setDecidedAt(LocalDateTime decidedAt) { this.decidedAt = decidedAt; }

    public String getDecidedBy() { return decidedBy; }
    public void setDecidedBy(String decidedBy) { this.decidedBy = decidedBy; }

    public String getDecisionNote() { return decisionNote; }
    public void setDecisionNote(String decisionNote) { this.decisionNote = decisionNote; }

    public String getResultSummary() { return resultSummary; }
    public void setResultSummary(String resultSummary) { this.resultSummary = resultSummary; }
}
