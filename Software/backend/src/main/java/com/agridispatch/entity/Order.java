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
    private String dispatchCategory; // harvest_grading, tillage_planting, pruning_bagging, drone_pest_control, cold_chain_pack, typhoon_emergency
    private String categoryName;
    private String transportZone;
    private int transportSurcharge;
    private int totalHours;
    private int suggestedWorkers;
    private int unitRate;
    private int standardAmount;
    private int finalAmount;
    private int depositAmount;
    private boolean depositPaid;
    private String status; // pending, confirmed, in_progress, completed, cancelled
    private String assignedTeamId;
    private String foremanName; // 現場派工小工頭 / 帶隊對口姓名
    private String foremanPhone; // 小工頭電話 / LINE
    private String foremanNotes; // 現場交辦指示 / 報到須知
    private String createdAt;

    /** 出工名單（工號，逗號分隔）；只由派工媒合 API 設定，建立／修改訂單時不接受前端傳入 */
    @jakarta.persistence.Column(name = "crew_worker_ids", length = 400)
    private String crewWorkerIds;

    /** 預定開工日（含當日） */
    @jakarta.persistence.Column(name = "start_date")
    private java.time.LocalDate startDate;

    /** 預定完工日（含當日）；須不早於開工日 */
    @jakarta.persistence.Column(name = "end_date")
    private java.time.LocalDate endDate;

    public Order() {}

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

    /** 兩張訂單工期是否重疊（任一未排定工期視為不重疊） */
    public boolean overlaps(Order other) {
        return hasPeriod() && other.hasPeriod()
                && !startDate.isAfter(other.endDate) && !other.startDate.isAfter(endDate);
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
