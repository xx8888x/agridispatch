package com.agridispatch.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 派工訂單實體
 */
@Entity
@Table(name = "orders")
public class Order {
    @Id
    private String id;
    private String farmerId;   // 下單農場主編號（token.ref），用於擁有者權限檢查
    private String farmerName;
    private String phone;
    private String farmLocation;
    private String cropId;
    private String cropName;
    private double area;
    private String plan;
    private String planName;
    private String dispatchCategory; // harvest_grading, tillage_planting, pruning_bagging, drone_pest_control, typhoon_emergency
    private String categoryName;
    private String transportZone;
    private int transportSurcharge;
    private int totalHours;
    private int suggestedWorkers;
    private int unitRate;
    private int standardAmount;
    private int finalAmount;
    private int depositAmount;
    @jakarta.persistence.Convert(converter = com.agridispatch.common.YesNoBooleanConverter.class)
    private boolean depositPaid;
    private String status; // pending, confirmed, in_progress, completed, cancelled
    private String assignedTeamId;
    private String foremanName; // 現場派工小工頭 / 帶隊對口姓名
    private String foremanPhone; // 小工頭電話 / LINE
    private String foremanNotes; // 現場交辦指示 / 報到須知
    @jakarta.persistence.Convert(converter = com.agridispatch.common.DateTimeStringConverter.class)
    private String createdAt;

    /** 出工名單（工號，逗號分隔）；只由派工媒合 API 設定，建立／修改訂單時不接受前端傳入 */
    @jakarta.persistence.Column(name = "crew_worker_ids", length = 400)
    private String crewWorkerIds;

    /** 工時包（文宣 DM）編號；有值表示金額、工時依該 DM 固定價計，null 表示依作物與面積計價 */
    @jakarta.persistence.Column(name = "package_id", length = 40)
    private String packageId;

    /** 下單時選的農場（farms.id）；舊訂單或未選農場時為 null。只在建立訂單時設定，須為下單農場主自己的農場 */
    @jakarta.persistence.Column(name = "farm_id", length = 32)
    private String farmId;

    /** 下單當時的農場名稱（快照）：農場之後改名或刪除，訂單仍顯示當時的名稱；由後端依 farmId 帶入，不接受前端傳入 */
    @jakarta.persistence.Column(name = "farm_name", length = 100)
    private String farmName;

    /** 農場主指定的每日工人數；null 表示由系統依面積估算（suggestedWorkers）。不影響金額，只決定編制人數與預估天數 */
    @jakarta.persistence.Column(name = "requested_workers")
    private Integer requestedWorkers;

    /** 預定開工日（含當日） */
    @jakarta.persistence.Column(name = "start_date")
    private java.time.LocalDate startDate;

    /** 預定完工日（含當日）；須不早於開工日 */
    @jakarta.persistence.Column(name = "end_date")
    private java.time.LocalDate endDate;

    /**
     * 農場主指名的工人（工號）；只在建立訂單時設定，須為曾到該農場主訂單出工的工人（PreferredWorkerService 驗證）。
     * 指名是「優先排入」而非保證：媒合時排在出工名單第一位，不合格或撞期時由調度改派並告知農場主
     */
    @jakarta.persistence.Column(name = "preferred_worker_id", length = 32)
    private String preferredWorkerId;

    /** 指名工人姓名（快照）；由後端依 preferredWorkerId 帶入，不接受前端傳入 */
    @jakarta.persistence.Column(name = "preferred_worker_name", length = 100)
    private String preferredWorkerName;

    /**
     * 每日時段：full_day 整天（預設；舊資料 null 視同整天）、morning 上午 07:00–11:00、afternoon 下午 13:00–17:00。
     * 半日時段讓同一位工人一天可以跑上午、下午兩個農場；中間兩小時為午休與移動
     * （移動時間是否計入工時、交通由誰負擔【待確認】）
     */
    @jakarta.persistence.Column(name = "time_slot", length = 16)
    private String timeSlot;

    /** 社區共享班表（shared_rosters.id）；有值表示這張訂單是農戶認領班表時段而成立，規格與時段不可修改，只能取消後重新認領 */
    @jakarta.persistence.Column(name = "shared_roster_id", length = 32)
    private String sharedRosterId;

    public String getSharedRosterId() { return sharedRosterId; }
    public void setSharedRosterId(String sharedRosterId) { this.sharedRosterId = sharedRosterId; }

    public static final String FULL_DAY = "full_day";
    public static final String MORNING = "morning";
    public static final String AFTERNOON = "afternoon";
    public static final java.util.List<String> TIME_SLOTS = java.util.List.of(FULL_DAY, MORNING, AFTERNOON);

    public Order() {}

    public String getTimeSlot() { return timeSlot; }
    public void setTimeSlot(String timeSlot) { this.timeSlot = timeSlot; }

    /** 時段（null 視同整天） */
    public String slot() { return timeSlot == null || timeSlot.isBlank() ? FULL_DAY : timeSlot; }

    public boolean isHalfDay() { return !FULL_DAY.equals(slot()); }

    /** 每人每日工時：整天 8、半日 4 */
    public static int hoursPerDay(String slot) { return slot == null || FULL_DAY.equals(slot) ? 8 : 4; }

    public static String slotLabel(String slot) {
        if (MORNING.equals(slot)) return "上午 07:00–11:00";
        if (AFTERNOON.equals(slot)) return "下午 13:00–17:00";
        return "整天";
    }

    /** 兩個時段是否重疊：整天與任何時段重疊；上午與下午不重疊 */
    public static boolean slotsOverlap(String a, String b) {
        String x = a == null ? FULL_DAY : a, y = b == null ? FULL_DAY : b;
        return FULL_DAY.equals(x) || FULL_DAY.equals(y) || x.equals(y);
    }

    /** 某個時刻是否落在此訂單的時段（打卡判斷所屬訂單用；以中午 12:00 區分上午、下午） */
    public boolean slotCovers(java.time.LocalTime t) {
        String s = slot();
        if (MORNING.equals(s)) return t.isBefore(java.time.LocalTime.NOON);
        if (AFTERNOON.equals(s)) return !t.isBefore(java.time.LocalTime.NOON);
        return true;
    }

    public String getPreferredWorkerId() { return preferredWorkerId; }
    public void setPreferredWorkerId(String preferredWorkerId) { this.preferredWorkerId = preferredWorkerId; }
    public String getPreferredWorkerName() { return preferredWorkerName; }
    public void setPreferredWorkerName(String preferredWorkerName) { this.preferredWorkerName = preferredWorkerName; }

    public String getPackageId() { return packageId; }
    public void setPackageId(String packageId) { this.packageId = packageId; }

    public String getFarmId() { return farmId; }
    public void setFarmId(String farmId) { this.farmId = farmId; }

    public String getFarmName() { return farmName; }
    public void setFarmName(String farmName) { this.farmName = farmName; }
    public Integer getRequestedWorkers() { return requestedWorkers; }
    public void setRequestedWorkers(Integer requestedWorkers) { this.requestedWorkers = requestedWorkers; }
    public java.time.LocalDate getStartDate() { return startDate; }
    public void setStartDate(java.time.LocalDate startDate) { this.startDate = startDate; }
    public java.time.LocalDate getEndDate() { return endDate; }
    public void setEndDate(java.time.LocalDate endDate) { this.endDate = endDate; }

    /** 是否已排定工期 */
    public boolean hasPeriod() { return startDate != null && endDate != null; }

    /** 指定日期是否在工期內 */
    public boolean coversDate(java.time.LocalDate day) {
        return hasPeriod() && !day.isBefore(startDate) && !day.isAfter(endDate);
    }

    /** 兩張訂單工期是否重疊（任一未排定工期視為不重疊）；同一天上午、下午兩個半日時段不算重疊 */
    public boolean overlaps(Order other) {
        return hasPeriod() && other.hasPeriod()
                && !startDate.isAfter(other.endDate) && !other.startDate.isAfter(endDate)
                && slotsOverlap(slot(), other.slot());
    }

    public String getCrewWorkerIds() { return crewWorkerIds; }
    public void setCrewWorkerIds(String crewWorkerIds) { this.crewWorkerIds = crewWorkerIds; }

    /** 出工名單（空名單回傳空集合） */
    public java.util.List<String> crewList() {
        if (crewWorkerIds == null || crewWorkerIds.isBlank()) return java.util.List.of();
        return java.util.Arrays.stream(crewWorkerIds.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getFarmerId() { return farmerId; }
    public void setFarmerId(String farmerId) { this.farmerId = farmerId; }

    public String getFarmerName() { return farmerName; }
    public void setFarmerName(String farmerName) { this.farmerName = farmerName; }

    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }

    public String getFarmLocation() { return farmLocation; }
    public void setFarmLocation(String farmLocation) { this.farmLocation = farmLocation; }

    public String getCropId() { return cropId; }
    public void setCropId(String cropId) { this.cropId = cropId; }

    public String getCropName() { return cropName; }
    public void setCropName(String cropName) { this.cropName = cropName; }

    public double getArea() { return area; }
    public void setArea(double area) { this.area = area; }

    public String getPlan() { return plan; }
    public void setPlan(String plan) { this.plan = plan; }

    public String getPlanName() { return planName; }
    public void setPlanName(String planName) { this.planName = planName; }

    public String getDispatchCategory() { return dispatchCategory; }
    public void setDispatchCategory(String dispatchCategory) { this.dispatchCategory = dispatchCategory; }

    public String getCategoryName() { return categoryName; }
    public void setCategoryName(String categoryName) { this.categoryName = categoryName; }

    public String getTransportZone() { return transportZone; }
    public void setTransportZone(String transportZone) { this.transportZone = transportZone; }

    public int getTransportSurcharge() { return transportSurcharge; }
    public void setTransportSurcharge(int transportSurcharge) { this.transportSurcharge = transportSurcharge; }

    public int getTotalHours() { return totalHours; }
    public void setTotalHours(int totalHours) { this.totalHours = totalHours; }

    public int getSuggestedWorkers() { return suggestedWorkers; }
    public void setSuggestedWorkers(int suggestedWorkers) { this.suggestedWorkers = suggestedWorkers; }

    public int getUnitRate() { return unitRate; }
    public void setUnitRate(int unitRate) { this.unitRate = unitRate; }

    public int getStandardAmount() { return standardAmount; }
    public void setStandardAmount(int standardAmount) { this.standardAmount = standardAmount; }

    public int getFinalAmount() { return finalAmount; }
    public void setFinalAmount(int finalAmount) { this.finalAmount = finalAmount; }

    public int getDepositAmount() { return depositAmount; }
    public void setDepositAmount(int depositAmount) { this.depositAmount = depositAmount; }

    public boolean isDepositPaid() { return depositPaid; }
    public void setDepositPaid(boolean depositPaid) { this.depositPaid = depositPaid; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getAssignedTeamId() { return assignedTeamId; }
    public void setAssignedTeamId(String assignedTeamId) { this.assignedTeamId = assignedTeamId; }

    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }

    public String getForemanName() { return foremanName; }
    public void setForemanName(String foremanName) { this.foremanName = foremanName; }

    public String getForemanPhone() { return foremanPhone; }
    public void setForemanPhone(String foremanPhone) { this.foremanPhone = foremanPhone; }

    public String getForemanNotes() { return foremanNotes; }
    public void setForemanNotes(String foremanNotes) { this.foremanNotes = foremanNotes; }
}
