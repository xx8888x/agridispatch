package com.agridispatch.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Column;
import jakarta.persistence.PrePersist;
import java.time.LocalDateTime;

/**
 * LINE 官方帳號使用者綁定實體 (對應 DB: line_users 表)
 */
@Entity
@Table(name = "line_users")
public class LineUser {
    @Id
    private String id;

    @Column(name = "line_user_id", nullable = false, unique = true, length = 64)
    private String lineUserId;

    @Column(nullable = false, length = 32)
    private String username;

    @Column(name = "display_name", nullable = false, length = 100)
    private String displayName;

    @Column(nullable = false, length = 32)
    private String role; // farmer, worker_foreign, worker_local, admin

    @Column(nullable = false, length = 10)
    private String lang; // zh, vi, id, th, en

    @Column(name = "is_bound", nullable = false, length = 1)
    private String isBound; // Y, N

    @Column(name = "bound_at", nullable = false)
    private LocalDateTime boundAt;

    @PrePersist
    protected void onCreate() {
        if (boundAt == null) boundAt = LocalDateTime.now();
        if (lang == null || lang.isEmpty()) lang = "zh";
        if (isBound == null || isBound.isEmpty()) isBound = "Y";
    }

    public LineUser() {}

    public LineUser(String id, String lineUserId, String username, String displayName, String role, String lang) {
        this.id = id;
        this.lineUserId = lineUserId;
        this.username = username;
        this.displayName = displayName;
        this.role = role;
        this.lang = (lang != null && !lang.isEmpty()) ? lang : "zh";
        this.isBound = "Y";
        this.boundAt = LocalDateTime.now();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getLineUserId() { return lineUserId; }
    public void setLineUserId(String lineUserId) { this.lineUserId = lineUserId; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

    public String getLang() { return lang; }
    public void setLang(String lang) { this.lang = lang; }

    public String getIsBound() { return isBound; }
    public void setIsBound(String isBound) { this.isBound = isBound; }

    public LocalDateTime getBoundAt() { return boundAt; }
    public void setBoundAt(LocalDateTime boundAt) { this.boundAt = boundAt; }
}
