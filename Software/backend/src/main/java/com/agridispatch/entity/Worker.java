package com.agridispatch.entity;

import com.agridispatch.security.Masking;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 農工作業人員實體 (三層勞工模型：外籍合法外展 / 本地彈性 / 專業機耕包商)
 */
@Entity
@Table(name = "workers")
public class Worker {
    @Id
    private String id;
    private String name;
    private String chineseName;
    private String nationality;
    private String teamId;
    @jakarta.persistence.Convert(converter = com.agridispatch.common.YesNoBooleanConverter.class)
    private boolean isLeader;
    private String tier; // tier1_foreign, tier2_local_flex, tier3_turnkey
    private int baseSalary;
    private int agriBonus;
    private int transportAllowance;

    // 外展移工法定與派遣名冊擴充欄位
    @JsonSerialize(using = Masking.MaskedIdSerializer.class)
    private String arcNumber;          // 居留證號 (ARC)，API 輸出一律遮罩
    @JsonSerialize(using = Masking.MaskedIdSerializer.class)
    private String passportNumber;     // 護照號碼，API 輸出一律遮罩
    private String permitExpiry;       // 聘僱許可效期 (例如 2027-10-31)
    private String insuranceStatus;    // 勞健保與職災險狀態 (例如 "已納保 (勞/健/職災)")
    private String currentTask;        // 目前派遣作業 (例如 "八德有機蔬菜採收")
    private String dispatchStatus;     // 派遣狀態 (dispatched_field, hedged_indoor, standby)
    private String phone;              // 聯絡電話

    /** 農工登入密碼雜湊（pbkdf2$...）；永不序列化輸出，也不接受由 JSON 寫入 */
    @JsonIgnore
    private String passwordHash;

    public Worker() {}

    public Worker(String id, String name, String chineseName, String nationality,
                  String teamId, boolean isLeader, String tier, int baseSalary,
                  int agriBonus, int transportAllowance) {
        this(id, name, chineseName, nationality, teamId, isLeader, tier, baseSalary, agriBonus, transportAllowance,
             "", "", "", "已納保 (勞/健/職災)", "田間出勤作業中", "dispatched_field", "");
    }

    public Worker(String id, String name, String chineseName, String nationality,
                  String teamId, boolean isLeader, String tier, int baseSalary,
                  int agriBonus, int transportAllowance, String arcNumber,
                  String passportNumber, String permitExpiry, String insuranceStatus,
                  String currentTask, String dispatchStatus, String phone) {
        this.id = id;
        this.name = name;
        this.chineseName = chineseName;
        this.nationality = nationality;
        this.teamId = teamId;
        this.isLeader = isLeader;
        this.tier = tier;
        this.baseSalary = baseSalary;
        this.agriBonus = agriBonus;
        this.transportAllowance = transportAllowance;
        this.arcNumber = arcNumber;
        this.passportNumber = passportNumber;
        this.permitExpiry = permitExpiry;
        this.insuranceStatus = insuranceStatus;
        this.currentTask = currentTask;
        this.dispatchStatus = dispatchStatus;
        this.phone = phone;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getChineseName() { return chineseName; }
    public void setChineseName(String chineseName) { this.chineseName = chineseName; }

    public String getNationality() { return nationality; }
    public void setNationality(String nationality) { this.nationality = nationality; }

    public String getTeamId() { return teamId; }
    public void setTeamId(String teamId) { this.teamId = teamId; }

    public boolean isLeader() { return isLeader; }
    public void setLeader(boolean leader) { isLeader = leader; }

    public String getTier() { return tier; }
    public void setTier(String tier) { this.tier = tier; }

    public int getBaseSalary() { return baseSalary; }
    public void setBaseSalary(int baseSalary) { this.baseSalary = baseSalary; }

    public int getAgriBonus() { return agriBonus; }
    public void setAgriBonus(int agriBonus) { this.agriBonus = agriBonus; }

    public int getTransportAllowance() { return transportAllowance; }
    public void setTransportAllowance(int transportAllowance) { this.transportAllowance = transportAllowance; }

    public String getArcNumber() { return arcNumber; }
    public void setArcNumber(String arcNumber) { this.arcNumber = arcNumber; }

    public String getPassportNumber() { return passportNumber; }
    public void setPassportNumber(String passportNumber) { this.passportNumber = passportNumber; }

    public String getPermitExpiry() { return permitExpiry; }
    public void setPermitExpiry(String permitExpiry) { this.permitExpiry = permitExpiry; }

    public String getInsuranceStatus() { return insuranceStatus; }
    public void setInsuranceStatus(String insuranceStatus) { this.insuranceStatus = insuranceStatus; }

    public String getCurrentTask() { return currentTask; }
    public void setCurrentTask(String currentTask) { this.currentTask = currentTask; }

    public String getDispatchStatus() { return dispatchStatus; }
    public void setDispatchStatus(String dispatchStatus) { this.dispatchStatus = dispatchStatus; }

    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }

    @JsonIgnore
    public String getPasswordHash() { return passwordHash; }
    @JsonIgnore
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
}
