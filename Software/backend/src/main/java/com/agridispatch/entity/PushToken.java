package com.agridispatch.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * App 推播權杖（對應 DB: push_tokens 表）
 *
 * 一筆 = 一台裝置的 FCM token，綁定最後一次上傳它的登入者（App 換人登入時改綁）。
 * id 為 token 的 SHA-256（token 本身可能很長，不適合當主鍵）。
 * 推播對象依 ref_id（F001、W001）、username 或 role 選取，見 FcmPushService。
 */
@Entity
@Table(name = "push_tokens")
public class PushToken {
    @Id
    @Column(length = 64)
    private String id;

    @Column(nullable = false, length = 1024)
    private String token;

    @Column(nullable = false, length = 64)
    private String username; // 登入者 sub

    @Column(name = "ref_id", length = 32)
    private String refId; // F001 / W001；admin 為 null

    @Column(length = 32)
    private String role; // farmer / worker_local / worker_foreign / admin_tech

    @Column(nullable = false, length = 16)
    private String platform; // android

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    @PreUpdate
    protected void touch() {
        updatedAt = LocalDateTime.now();
        if (platform == null || platform.isEmpty()) platform = "android";
    }

    public PushToken() {}

    public PushToken(String id, String token) {
        this.id = id;
        this.token = token;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getRefId() { return refId; }
    public void setRefId(String refId) { this.refId = refId; }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

    public String getPlatform() { return platform; }
    public void setPlatform(String platform) { this.platform = platform; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
