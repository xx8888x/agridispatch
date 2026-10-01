package com.agridispatch.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 農場主對工班的評價（每張訂單一筆，可修改）
 * 只評到「工班」層級，不針對個別工人，避免個資與報復性評分疑慮
 */
@Entity
@Table(name = "team_ratings")
public class TeamRating {

    /** 評價編號，與訂單一對一：RAT-<訂單編號> */
    @Id
    @Column(length = 50)
    private String id;

    @Column(name = "order_id", nullable = false, unique = true, length = 40)
    private String orderId;

    @Column(name = "team_id", nullable = false, length = 32)
    private String teamId;

    /** 評分者（農場主 token.ref；admin 代評時為 admin 的 ref） */
    @Column(name = "farmer_id", length = 40)
    private String farmerId;

    /** 整體評分 1～5 */
    @Column(nullable = false)
    private int score;

    /** 標籤，逗號分隔（限 RatingService.ALLOWED_TAGS） */
    @Column(length = 200)
    private String tags;

    /** 欄位名避開 Oracle 保留字 COMMENT */
    @Column(name = "rating_comment", length = 300)
    private String comment;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public TeamRating() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getOrderId() { return orderId; }
    public void setOrderId(String orderId) { this.orderId = orderId; }
    public String getTeamId() { return teamId; }
    public void setTeamId(String teamId) { this.teamId = teamId; }
    public String getFarmerId() { return farmerId; }
    public void setFarmerId(String farmerId) { this.farmerId = farmerId; }
    public int getScore() { return score; }
    public void setScore(int score) { this.score = score; }
    public String getTags() { return tags; }
    public void setTags(String tags) { this.tags = tags; }
    public String getComment() { return comment; }
    public void setComment(String comment) { this.comment = comment; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
