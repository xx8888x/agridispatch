package com.agridispatch.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 派工工班實體 (hedgeLocation／hedgeTask 為已停用的舊欄位：暴雨避險功能已移除，資料庫 NOT NULL 故保留)
 */
@Entity
@Table(name = "dispatch_teams")
public class DispatchTeam {
    @Id
    private String id;
    private String name;
    private int workerCount;
    private String leader;
    private String leaderPhone;
    private String vehicle;
    private String driver;
    private String normalLocation;
    private String normalTask;
    private String hedgeLocation;
    private String hedgeTask;
    private String status; // active_field, standby

    public DispatchTeam() {}

    public DispatchTeam(String id, String name, int workerCount, String leader, String leaderPhone,
                        String vehicle, String driver, String normalLocation, String normalTask,
                        String hedgeLocation, String hedgeTask, String status) {
        this.id = id;
        this.name = name;
        this.workerCount = workerCount;
        this.leader = leader;
        this.leaderPhone = leaderPhone;
        this.vehicle = vehicle;
        this.driver = driver;
        this.normalLocation = normalLocation;
        this.normalTask = normalTask;
        this.hedgeLocation = hedgeLocation;
        this.hedgeTask = hedgeTask;
        this.status = status;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public int getWorkerCount() { return workerCount; }
    public void setWorkerCount(int workerCount) { this.workerCount = workerCount; }

    public String getLeader() { return leader; }
    public void setLeader(String leader) { this.leader = leader; }

    public String getLeaderPhone() { return leaderPhone; }
    public void setLeaderPhone(String leaderPhone) { this.leaderPhone = leaderPhone; }

    public String getVehicle() { return vehicle; }
    public void setVehicle(String vehicle) { this.vehicle = vehicle; }

    public String getDriver() { return driver; }
    public void setDriver(String driver) { this.driver = driver; }

    public String getNormalLocation() { return normalLocation; }
    public void setNormalLocation(String normalLocation) { this.normalLocation = normalLocation; }

    public String getNormalTask() { return normalTask; }
    public void setNormalTask(String normalTask) { this.normalTask = normalTask; }

    public String getHedgeLocation() { return hedgeLocation; }
    public void setHedgeLocation(String hedgeLocation) { this.hedgeLocation = hedgeLocation; }

    public String getHedgeTask() { return hedgeTask; }
    public void setHedgeTask(String hedgeTask) { this.hedgeTask = hedgeTask; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
