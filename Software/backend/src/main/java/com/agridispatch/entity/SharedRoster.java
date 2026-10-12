package com.agridispatch.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 社區共享班表（對應 DB: shared_rosters 表）：合作社把一組工班（固定出工名單）在某段期間派駐同一村／產銷班，
 * 每天切成上午、下午兩個半日時段由當地農戶認領；每次認領就是一張訂單（orders.shared_roster_id）。
 *
 * 認領門檻：截止日（claimDeadline）時已認領時段比例未達 minClaimPercent 就取消整個班表；
 * 達標則開班（confirmed），之後剩下的空檔仍可認領，由合作社改派其他工作消化。
 * 拼團不打折，每個時段照一般計價（工班人數 × 時段工時 × 方案時薪）。
 */
@Entity
@Table(name = "shared_rosters")
public class SharedRoster {
    public static final String OPEN = "open";
    public static final String CONFIRMED = "confirmed";
    public static final String CANCELLED = "cancelled";
    public static final String COMPLETED = "completed";

    @Id
    @Column(length = 32)
    private String id;

    @Column(nullable = false, length = 100)
    private String title;

    /** 派駐地區（村里／產銷班），給農戶判斷是不是自己這一區 */
    @Column(nullable = false, length = 100)
    private String region;

    @Column(length = 500)
    private String description;

    @Column(name = "team_id", nullable = false, length = 32)
    private String teamId;

    /** 固定出工名單（工號逗號分隔）；每張認領訂單沿用這份名單 */
    @Column(name = "crew_worker_ids", nullable = false, length = 400)
    private String crewWorkerIds;

    /** 派工類別（決定作業內容與技能需求），同 orders.dispatch_category */
    @Column(name = "dispatch_category", nullable = false, length = 50)
    private String dispatchCategory;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    /** 認領門檻（%）：截止日時已認領時段比例須達此值才開班 */
    @Column(name = "min_claim_percent", nullable = false)
    private int minClaimPercent;

    /** 認領截止日（含當日）；須早於開工日 */
    @Column(name = "claim_deadline", nullable = false)
    private LocalDate claimDeadline;

    @Column(nullable = false, length = 20)
    private String status;

    /** 取消原因（未達門檻自動取消或合作社手動取消） */
    @Column(name = "cancel_reason", length = 200)
    private String cancelReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (status == null) status = OPEN;
    }

    public java.util.List<String> crewList() {
        if (crewWorkerIds == null || crewWorkerIds.isBlank()) return java.util.List.of();
        return java.util.Arrays.stream(crewWorkerIds.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    /** 期間天數（含頭尾） */
    public int days() {
        return (int) java.time.temporal.ChronoUnit.DAYS.between(startDate, endDate) + 1;
    }

    /** 是否仍佔用工班（開放認領或已開班） */
    public boolean isActive() {
        return OPEN.equals(status) || CONFIRMED.equals(status);
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getTeamId() { return teamId; }
    public void setTeamId(String teamId) { this.teamId = teamId; }
    public String getCrewWorkerIds() { return crewWorkerIds; }
    public void setCrewWorkerIds(String crewWorkerIds) { this.crewWorkerIds = crewWorkerIds; }
    public String getDispatchCategory() { return dispatchCategory; }
    public void setDispatchCategory(String dispatchCategory) { this.dispatchCategory = dispatchCategory; }
    public LocalDate getStartDate() { return startDate; }
    public void setStartDate(LocalDate startDate) { this.startDate = startDate; }
    public LocalDate getEndDate() { return endDate; }
    public void setEndDate(LocalDate endDate) { this.endDate = endDate; }
    public int getMinClaimPercent() { return minClaimPercent; }
    public void setMinClaimPercent(int minClaimPercent) { this.minClaimPercent = minClaimPercent; }
    public LocalDate getClaimDeadline() { return claimDeadline; }
    public void setClaimDeadline(LocalDate claimDeadline) { this.claimDeadline = claimDeadline; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getCancelReason() { return cancelReason; }
    public void setCancelReason(String cancelReason) { this.cancelReason = cancelReason; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
