package com.agridispatch.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 作物工時與定價基準實體
 */
@Entity
@Table(name = "crops")
public class Crop {
    @Id
    private String id;
    private String name;
    private int baseHoursPerFen;
    private int workersPerFen;
    private int rate;
    private String season;
    private String taskDescription;
    private String indoorAlternative;

    public Crop() {}

    public Crop(String id, String name, int baseHoursPerFen, int workersPerFen,
                int rate, String season, String taskDescription, String indoorAlternative) {
        this.id = id;
        this.name = name;
        this.baseHoursPerFen = baseHoursPerFen;
        this.workersPerFen = workersPerFen;
        this.rate = rate;
        this.season = season;
        this.taskDescription = taskDescription;
        this.indoorAlternative = indoorAlternative;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public int getBaseHoursPerFen() { return baseHoursPerFen; }
    public void setBaseHoursPerFen(int baseHoursPerFen) { this.baseHoursPerFen = baseHoursPerFen; }

    public int getWorkersPerFen() { return workersPerFen; }
    public void setWorkersPerFen(int workersPerFen) { this.workersPerFen = workersPerFen; }

    public int getRate() { return rate; }
    public void setRate(int rate) { this.rate = rate; }

    public String getSeason() { return season; }
    public void setSeason(String season) { this.season = season; }

    public String getTaskDescription() { return taskDescription; }
    public void setTaskDescription(String taskDescription) { this.taskDescription = taskDescription; }

    public String getIndoorAlternative() { return indoorAlternative; }
    public void setIndoorAlternative(String indoorAlternative) { this.indoorAlternative = indoorAlternative; }
}
