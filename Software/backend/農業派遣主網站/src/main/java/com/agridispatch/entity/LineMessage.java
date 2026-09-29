package com.agridispatch.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Column;
import jakarta.persistence.PrePersist;
import java.time.LocalDateTime;

/**
 * LINE 訊息與推播歷程實體 (對應 DB: line_messages 表)
 */
@Entity
@Table(name = "line_messages")
public class LineMessage {
    @Id
    private String id;

    @Column(name = "line_user_id", length = 64)
    private String lineUserId;

    @Column(nullable = false, length = 64)
    private String sender; // SYSTEM_BROADCAST, ADMIN, FOREMAN

    @Column(name = "target_role", nullable = false, length = 32)
    private String targetRole; // all, farmer, worker_foreign, worker_local

    @Column(name = "message_type", nullable = false, length = 32)
    private String messageType; // text, flex_weather_alert, flex_dispatch, audio_broadcast

    @Column(nullable = false, length = 2000)
    private String content;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (sender == null || sender.isEmpty()) sender = "SYSTEM_BROADCAST";
        if (targetRole == null || targetRole.isEmpty()) targetRole = "all";
        if (messageType == null || messageType.isEmpty()) messageType = "text";
    }

    public LineMessage() {}

    public LineMessage(String id, String lineUserId, String sender, String targetRole, String messageType, String content) {
        this.id = id;
        this.lineUserId = lineUserId;
        this.sender = (sender != null && !sender.isEmpty()) ? sender : "SYSTEM_BROADCAST";
        this.targetRole = (targetRole != null && !targetRole.isEmpty()) ? targetRole : "all";
        this.messageType = (messageType != null && !messageType.isEmpty()) ? messageType : "text";
        this.content = content;
        this.createdAt = LocalDateTime.now();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getLineUserId() { return lineUserId; }
    public void setLineUserId(String lineUserId) { this.lineUserId = lineUserId; }

    public String getSender() { return sender; }
    public void setSender(String sender) { this.sender = sender; }

    public String getTargetRole() { return targetRole; }
    public void setTargetRole(String targetRole) { this.targetRole = targetRole; }

    public String getMessageType() { return messageType; }
    public void setMessageType(String messageType) { this.messageType = messageType; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
