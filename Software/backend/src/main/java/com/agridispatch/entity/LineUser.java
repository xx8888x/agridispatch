package com.agridispatch.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;

/**
 * LINE 官方帳號使用者（對應 DB: line_users 表）
 * 規格：07_系統分析及文件/05_LINE官方帳號拆分規格.md 6.5
 *
 * 一筆 = 一個 LINE userId 在一個官方帳號（channel）的狀態：追蹤、語言、綁定的平台帳號。
 * 加好友即建立（未綁定），綁定後寫入 username／role／refId；解除綁定時清空這三欄並設 is_bound='N'。
 * (username, channel) 在 username 非空時唯一，由 LineBindingService 檢查（Oracle 另以函數索引保證）。
 */
@Entity
@Table(name = "line_users", uniqueConstraints = {
        @UniqueConstraint(name = "uk_line_users_user_channel", columnNames = {"line_user_id", "channel"})
})
public class LineUser {
    public static final String CHANNEL_FARMER = "farmer";
    public static final String CHANNEL_WORKER = "worker";

    @Id
    @Column(length = 32)
    private String id;

    @Column(nullable = false, length = 10)
    private String channel; // farmer / worker

    @Column(name = "line_user_id", nullable = false, length = 64)
    private String lineUserId;

    @Column(length = 32)
    private String username; // 綁定的平台帳號（users.username 或農工工號）；未綁定為 null

    @Column(name = "ref_id", length = 32)
    private String refId; // 綁定時的 token.ref（農場主 F001、農工 W001）

    @Column(name = "display_name", length = 100)
    private String displayName; // LINE 顯示名稱，僅供後台辨識

    @Column(length = 32)
    private String role; // farmer / worker_foreign / worker_local；未綁定為 null

    @Column(nullable = false, length = 10)
    private String lang; // zh / vi / id（保留 th、tl）

    @Column(name = "lang_locked", nullable = false, length = 1)
    private String langLocked; // Y＝使用者主動選過語言

    @Column(name = "is_bound", nullable = false, length = 1)
    private String isBound; // Y / N

    @Column(name = "bound_at")
    private LocalDateTime boundAt;

    @Column(nullable = false, length = 1)
    private String followed; // Y 追蹤中 / N 已封鎖

    @Column(name = "follow_changed_at")
    private LocalDateTime followChangedAt;

    @PrePersist
    protected void onCreate() {
        if (lang == null || lang.isEmpty()) lang = "zh";
        if (langLocked == null || langLocked.isEmpty()) langLocked = "N";
        if (isBound == null || isBound.isEmpty()) isBound = "N";
        if (followed == null || followed.isEmpty()) followed = "Y";
    }

    public LineUser() {}

    public LineUser(String id, String channel, String lineUserId) {
        this.id = id;
        this.channel = channel;
        this.lineUserId = lineUserId;
    }

    public boolean bound() { return "Y".equals(isBound); }
    public boolean isLangLocked() { return "Y".equals(langLocked); }
    public boolean isFollowed() { return !"N".equals(followed); }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getChannel() { return channel; }
    public void setChannel(String channel) { this.channel = channel; }

    public String getLineUserId() { return lineUserId; }
    public void setLineUserId(String lineUserId) { this.lineUserId = lineUserId; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getRefId() { return refId; }
    public void setRefId(String refId) { this.refId = refId; }

    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

    public String getLang() { return lang; }
    public void setLang(String lang) { this.lang = lang; }

    public String getLangLocked() { return langLocked; }
    public void setLangLocked(String langLocked) { this.langLocked = langLocked; }

    public String getIsBound() { return isBound; }
    public void setIsBound(String isBound) { this.isBound = isBound; }

    public LocalDateTime getBoundAt() { return boundAt; }
    public void setBoundAt(LocalDateTime boundAt) { this.boundAt = boundAt; }

    public String getFollowed() { return followed; }
    public void setFollowed(String followed) { this.followed = followed; }

    public LocalDateTime getFollowChangedAt() { return followChangedAt; }
    public void setFollowChangedAt(LocalDateTime followChangedAt) { this.followChangedAt = followChangedAt; }
}
