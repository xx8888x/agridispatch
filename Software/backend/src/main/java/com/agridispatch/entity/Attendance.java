package com.agridispatch.entity;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * GPS 現場數位打卡出勤記錄 Entity
 * 對應 DB: clock_ins 資料表
 *
 * 三層農工模型均適用：
 *   tier1_foreign   → 外籍合法外展移工
 *   tier2_local_flex → 本地帶工領班 / 勞務勞工
 *   tier3_turnkey   → 專業機耕技師
 */
@Entity
@Table(name = "clock_ins")
public class Attendance {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 農工工號 (FK → workers.id) */
    @Column(name = "worker_id", nullable = false, length = 20)
    private String workerId;

    /** 出勤日期 */
    @Column(name = "work_date", nullable = false)
    private LocalDate workDate;

    /** 上班打卡時間 (含日期時間，可算遲到/早到) */
    @Column(name = "clock_in_time", nullable = false)
    private LocalDateTime clockInTime;

    /** 下班打卡時間（允許為 null：代表仍在出勤中） */
    @Column(name = "clock_out_time")
    private LocalDateTime clockOutTime;

    /** GPS 打卡位置描述 (例如：屏東枋山愛文芒果採收場 22.6123°N, 120.6145°E) */
    @Column(name = "location", length = 200)
    private String location;

    /** GPS 精確度 (公尺) */
    @Column(name = "gps_accuracy_meters")
    private Double gpsAccuracyMeters;

    /**
     * 出勤狀態
     *   working   → 已上班打卡，尚未下班
     *   completed → 今日出勤已完成
     *   absent    → 缺勤
     */
    @Column(name = "status", length = 20)
    private String status;

    /** 實際工作時數（下班後自動計算，null = 尚未計算） */
    @Column(name = "actual_hours")
    private Double actualHours;

    /** 正常工時（8小時內） */
    @Column(name = "normal_hours")
    private Double normalHours;

    /** 加班工時（超過8小時部分） */
    @Column(name = "overtime_hours")
    private Double overtimeHours;

    /** 小工頭簽核姓名（僅帶工領班端有此欄） */
    @Column(name = "foreman_signature", length = 50)
    private String foremanSignature;

    /** 本次出勤所屬訂單（合法用工證明依此彙整）；舊資料或無法判定時為 null */
    @Column(name = "order_id", length = 40)
    private String orderId;

    /** 領班逐人評核：excellent 優 / ok 可 / needs_improvement 需加強；未評核為 null */
    @Column(name = "foreman_rating", length = 20)
    private String foremanRating;

    /** 評核者（領班工號；admin 代評時為 admin 的 ref） */
    @Column(name = "foreman_rated_by", length = 32)
    private String foremanRatedBy;

    @Column(name = "foreman_rated_at")
    private LocalDateTime foremanRatedAt;

    /** 農場主逐人評核（該訂單下對這位工人的整體表現）：同 foreman_rating 三級；未評核為 null */
    @Column(name = "farmer_rating", length = 20)
    private String farmerRating;

    /** 評核者（農場主 ref；admin 代評時為 admin 的 ref） */
    @Column(name = "farmer_rated_by", length = 32)
    private String farmerRatedBy;

    @Column(name = "farmer_rated_at")
    private LocalDateTime farmerRatedAt;

    /** 記錄建立時間 */
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        if (workDate == null) workDate = LocalDate.now();
        if (status == null) status = "working";
    }

    // ─── Constructors ──────────────────────────────────────────────

    public Attendance() {}

    /** 上班打卡用建構子 */
    public Attendance(String workerId, LocalDateTime clockInTime, String location, Double gpsAccuracyMeters) {
        this.workerId = workerId;
        this.clockInTime = clockInTime;
        this.workDate = clockInTime.toLocalDate();
        this.location = location;
        this.gpsAccuracyMeters = gpsAccuracyMeters;
        this.status = "working";
    }

    // ─── Getters & Setters ────────────────────────────────────────

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getWorkerId() { return workerId; }
    public void setWorkerId(String workerId) { this.workerId = workerId; }

    public LocalDate getWorkDate() { return workDate; }
    public void setWorkDate(LocalDate workDate) { this.workDate = workDate; }

    public LocalDateTime getClockInTime() { return clockInTime; }
    public void setClockInTime(LocalDateTime clockInTime) { this.clockInTime = clockInTime; }

    public LocalDateTime getClockOutTime() { return clockOutTime; }
    public void setClockOutTime(LocalDateTime clockOutTime) { this.clockOutTime = clockOutTime; }

    public String getLocation() { return location; }
    public void setLocation(String location) { this.location = location; }

    public Double getGpsAccuracyMeters() { return gpsAccuracyMeters; }
    public void setGpsAccuracyMeters(Double gpsAccuracyMeters) { this.gpsAccuracyMeters = gpsAccuracyMeters; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Double getActualHours() { return actualHours; }
    public void setActualHours(Double actualHours) { this.actualHours = actualHours; }

    public Double getNormalHours() { return normalHours; }
    public void setNormalHours(Double normalHours) { this.normalHours = normalHours; }

    public Double getOvertimeHours() { return overtimeHours; }
    public void setOvertimeHours(Double overtimeHours) { this.overtimeHours = overtimeHours; }

    public String getForemanSignature() { return foremanSignature; }
    public void setForemanSignature(String foremanSignature) { this.foremanSignature = foremanSignature; }

    public String getOrderId() { return orderId; }
    public void setOrderId(String orderId) { this.orderId = orderId; }

    public String getForemanRating() { return foremanRating; }
    public void setForemanRating(String foremanRating) { this.foremanRating = foremanRating; }
    public String getForemanRatedBy() { return foremanRatedBy; }
    public void setForemanRatedBy(String foremanRatedBy) { this.foremanRatedBy = foremanRatedBy; }
    public LocalDateTime getForemanRatedAt() { return foremanRatedAt; }
    public void setForemanRatedAt(LocalDateTime foremanRatedAt) { this.foremanRatedAt = foremanRatedAt; }

    public String getFarmerRating() { return farmerRating; }
    public void setFarmerRating(String farmerRating) { this.farmerRating = farmerRating; }
    public String getFarmerRatedBy() { return farmerRatedBy; }
    public void setFarmerRatedBy(String farmerRatedBy) { this.farmerRatedBy = farmerRatedBy; }
    public LocalDateTime getFarmerRatedAt() { return farmerRatedAt; }
    public void setFarmerRatedAt(LocalDateTime farmerRatedAt) { this.farmerRatedAt = farmerRatedAt; }

    /** 技能標籤採計的評核：領班優先，沒有領班評核才用農場主評核 */
    public String effectiveRating() { return foremanRating != null ? foremanRating : farmerRating; }

    /** 對應 effectiveRating 的評核時間 */
    public LocalDateTime effectiveRatedAt() { return foremanRating != null ? foremanRatedAt : farmerRatedAt; }

    public LocalDateTime getCreatedAt() { return createdAt; }

    /**
     * 便利方法：下班後計算工時並更新狀態
     */
    public void completeShift(LocalDateTime clockOutTime, String foremanSignature) {
        this.clockOutTime = clockOutTime;
        this.foremanSignature = foremanSignature;
        this.status = "completed";

        // 計算工時（分鐘精度）
        long minutesWorked = java.time.Duration.between(this.clockInTime, clockOutTime).toMinutes();
        this.actualHours = Math.round(minutesWorked / 6.0) / 10.0; // 0.1h 精度
        this.normalHours = Math.min(this.actualHours, 8.0);
        this.overtimeHours = Math.max(0.0, this.actualHours - 8.0);
    }
}
