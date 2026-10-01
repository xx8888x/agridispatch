package com.agridispatch.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * 熟手標籤暫停後的管理員複核紀錄：clearedAt 之前的評價／評核不再觸發暫停
 */
@Entity
@Table(name = "skill_reviews")
public class SkillReview {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "worker_id", nullable = false, length = 32)
    private String workerId;

    /** harvest / pruning / tillage / packing */
    @Column(name = "skill_key", nullable = false, length = 16)
    private String skillKey;

    @Column(name = "cleared_at", nullable = false)
    private LocalDateTime clearedAt;

    @Column(name = "cleared_by", length = 32)
    private String clearedBy;

    @Column(length = 200)
    private String note;

    public SkillReview() {}

    public SkillReview(String workerId, String skillKey, LocalDateTime clearedAt, String clearedBy, String note) {
        this.workerId = workerId;
        this.skillKey = skillKey;
        this.clearedAt = clearedAt;
        this.clearedBy = clearedBy;
        this.note = note;
    }

    public Long getId() { return id; }
    public String getWorkerId() { return workerId; }
    public String getSkillKey() { return skillKey; }
    public LocalDateTime getClearedAt() { return clearedAt; }
    public String getClearedBy() { return clearedBy; }
    public String getNote() { return note; }
}
