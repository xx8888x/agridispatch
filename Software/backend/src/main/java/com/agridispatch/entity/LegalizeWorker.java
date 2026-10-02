package com.agridispatch.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 工班合法化（黑轉白）清冊：農場主登錄目前在園區工作、尚未合法聘僱或投保的人員，
 * 系統依身分類型分流（可直接轉白／有條件／不可就地轉、改派補位），逐步完成轉白或停止使用。
 *
 * 失聯移工與借用他人移工（replace 路徑）依法不能就地合法化，本表對這兩類只記「需補位的人力」，
 * 不存姓名、證件或聯絡方式（nickname 強制為 null）。
 */
@Entity
@Table(name = "legalize_workers")
public class LegalizeWorker {

    /** LGW-000001 */
    @Id
    @Column(length = 32)
    private String id;

    /** 登錄的農場主（token.ref，例 F001） */
    @Column(name = "farmer_id", nullable = false, length = 40)
    private String farmerId;

    /** 身分類型：local / spouse / student / borrowed / runaway（見 LegalizeService.TYPES） */
    @Column(name = "worker_type", nullable = false, length = 16)
    private String workerType;

    /** 稱呼（可用暱稱）；replace 路徑一律 null */
    @Column(length = 60)
    private String nickname;

    /** 目前工作內容 */
    @Column(length = 60)
    private String job;

    /** 每週約幾天（1～7） */
    @Column(name = "days_per_week", nullable = false)
    private int daysPerWeek;

    /** 各步驟完成狀態，逗號分隔的 0/1，長度 = 該類型的步驟數，例 "1,1,0,0,0" */
    @Column(name = "steps_done", nullable = false, length = 40)
    private String stepsDone;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public LegalizeWorker() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getFarmerId() { return farmerId; }
    public void setFarmerId(String farmerId) { this.farmerId = farmerId; }
    public String getWorkerType() { return workerType; }
    public void setWorkerType(String workerType) { this.workerType = workerType; }
    public String getNickname() { return nickname; }
    public void setNickname(String nickname) { this.nickname = nickname; }
    public String getJob() { return job; }
    public void setJob(String job) { this.job = job; }
    public int getDaysPerWeek() { return daysPerWeek; }
    public void setDaysPerWeek(int daysPerWeek) { this.daysPerWeek = daysPerWeek; }
    public String getStepsDone() { return stepsDone; }
    public void setStepsDone(String stepsDone) { this.stepsDone = stepsDone; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
