package com.agridispatch.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.LocalDateTime;

/**
 * 推薦碼（對應 DB: referral_codes 表）：每個農場主／工人一組，邀請連結帶 ?ref=code。
 * clickCount 為邀請連結被開啟的次數（轉換漏斗最上層）。
 */
@Entity
@Table(name = "referral_codes", uniqueConstraints = @UniqueConstraint(name = "uk_referral_codes_owner", columnNames = "owner_username"))
public class ReferralCode {
    @Id
    @Column(length = 12)
    private String code;

    @Column(name = "owner_username", nullable = false, length = 64)
    private String ownerUsername;

    @Column(name = "owner_ref_id", length = 32)
    private String ownerRefId;

    @Column(name = "owner_role", nullable = false, length = 32)
    private String ownerRole;

    @Column(name = "click_count", nullable = false)
    private Integer clickCount = 0;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (clickCount == null) clickCount = 0;
    }

    public ReferralCode() {}

    public ReferralCode(String code, String ownerUsername, String ownerRefId, String ownerRole) {
        this.code = code;
        this.ownerUsername = ownerUsername;
        this.ownerRefId = ownerRefId;
        this.ownerRole = ownerRole;
    }

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }

    public String getOwnerUsername() { return ownerUsername; }
    public void setOwnerUsername(String ownerUsername) { this.ownerUsername = ownerUsername; }

    public String getOwnerRefId() { return ownerRefId; }
    public void setOwnerRefId(String ownerRefId) { this.ownerRefId = ownerRefId; }

    public String getOwnerRole() { return ownerRole; }
    public void setOwnerRole(String ownerRole) { this.ownerRole = ownerRole; }

    public int getClickCount() { return clickCount == null ? 0 : clickCount; }
    public void setClickCount(int clickCount) { this.clickCount = clickCount; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
