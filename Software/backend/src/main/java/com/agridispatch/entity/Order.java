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

    public Order() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

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
