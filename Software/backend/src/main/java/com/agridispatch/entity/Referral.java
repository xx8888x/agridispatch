package com.agridispatch.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 推薦紀錄（對應 DB: referrals 表）：一個被邀請的人一筆。
 *
 * 兩種來源：
 *   farmer 農場主透過邀請連結自助註冊 → 直接 registered（帶 inviteeRefId）
 *   worker 工人意願登記（工人須經合作社查驗證件才能建檔，不能自助註冊）→ pending，由合作社聯絡後改 contacted／onboarded／closed
 * 本表只記錄推薦關係與跟進狀態，不含任何獎勵金額（推廣獎勵的稅務與法規認定尚待確認）。
 */
@Entity
@Table(name = "referrals")
public class Referral {
    public static final String REGISTERED = "registered";
    public static final String PENDING = "pending";
    public static final String CONTACTED = "contacted";
    public static final String ONBOARDED = "onboarded";
    public static final String CLOSED = "closed";

    @Id
    @Column(length = 32)
    private String id; // RF-2026-001

    @Column(nullable = false, length = 12)
    private String code;

    @Column(name = "referrer_username", nullable = false, length = 64)
    private String referrerUsername;

    @Column(name = "referrer_role", nullable = false, length = 32)
    private String referrerRole;

    @Column(name = "invitee_role", nullable = false, length = 16)
    private String inviteeRole; // farmer / worker

    @Column(name = "invitee_name", nullable = false, length = 50)
    private String inviteeName;

    @Column(name = "invitee_phone", length = 30)
    private String inviteePhone;

    @Column(name = "invitee_ref_id", length = 32)
    private String inviteeRefId; // 農場主註冊成功後的 farmers.id

    @Column(length = 16)
    private String channel; // fb / line / copy / share / other

    @Column(nullable = false, length = 16)
    private String status;

    @Column(length = 500)
    private String note; // 合作社跟進備註（只有後台看得到）

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (status == null || status.isEmpty()) status = PENDING;
    }

    public Referral() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }

    public String getReferrerUsername() { return referrerUsername; }
    public void setReferrerUsername(String referrerUsername) { this.referrerUsername = referrerUsername; }

    public String getReferrerRole() { return referrerRole; }
    public void setReferrerRole(String referrerRole) { this.referrerRole = referrerRole; }

    public String getInviteeRole() { return inviteeRole; }
    public void setInviteeRole(String inviteeRole) { this.inviteeRole = inviteeRole; }

    public String getInviteeName() { return inviteeName; }
    public void setInviteeName(String inviteeName) { this.inviteeName = inviteeName; }

    public String getInviteePhone() { return inviteePhone; }
    public void setInviteePhone(String inviteePhone) { this.inviteePhone = inviteePhone; }

    public String getInviteeRefId() { return inviteeRefId; }
    public void setInviteeRefId(String inviteeRefId) { this.inviteeRefId = inviteeRefId; }

    public String getChannel() { return channel; }
    public void setChannel(String channel) { this.channel = channel; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
