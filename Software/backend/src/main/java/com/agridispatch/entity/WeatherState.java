package com.agridispatch.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * 智慧天候避險狀態實體 (WEATHER_STATES)
 */
@Entity
@Table(name = "weather_states")
public class WeatherState {

    @Id
    @Column(length = 64)
    private String id;

    @Column(name = "scenario_id", length = 32, nullable = false)
    private String scenarioId;

    @Column(name = "scenario_name", length = 64)
    private String scenarioName;

    @Column(name = "is_rain_mode", length = 1, nullable = false)
    private String isRainMode; // "Y" or "N"

    @Column(name = "current_weather", length = 100, nullable = false)
    private String currentWeather;

    @Column(name = "alert_level", length = 50, nullable = false)
    private String alertLevel; // normal, info, warning, danger

    @Column(name = "message", length = 500, nullable = false)
    private String message;

    @Column(name = "secured_revenue")
    private Integer securedRevenue;

    @Column(name = "daily_capacity_hrs")
    private Integer dailyCapacityHrs;

    @Lob
    @Column(name = "regional_json", columnDefinition = "TEXT")
    private String regionalJson;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public WeatherState() {}

    public WeatherState(String id, String scenarioId, String scenarioName, String isRainMode,
                        String currentWeather, String alertLevel, String message,
                        Integer securedRevenue, Integer dailyCapacityHrs,
                        String regionalJson, LocalDateTime updatedAt) {
        this.id = id;
        this.scenarioId = scenarioId;
        this.scenarioName = scenarioName;
        this.isRainMode = isRainMode;
        this.currentWeather = currentWeather;
        this.alertLevel = alertLevel;
        this.message = message;
        this.securedRevenue = securedRevenue;
        this.dailyCapacityHrs = dailyCapacityHrs;
        this.regionalJson = regionalJson;
        this.updatedAt = updatedAt;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getScenarioId() { return scenarioId; }
    public void setScenarioId(String scenarioId) { this.scenarioId = scenarioId; }

    public String getScenarioName() { return scenarioName; }
    public void setScenarioName(String scenarioName) { this.scenarioName = scenarioName; }

    public String getIsRainMode() { return isRainMode; }
    public void setIsRainMode(String isRainMode) { this.isRainMode = isRainMode; }

    public String getCurrentWeather() { return currentWeather; }
    public void setCurrentWeather(String currentWeather) { this.currentWeather = currentWeather; }

    public String getAlertLevel() { return alertLevel; }
    public void setAlertLevel(String alertLevel) { this.alertLevel = alertLevel; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public Integer getSecuredRevenue() { return securedRevenue; }
    public void setSecuredRevenue(Integer securedRevenue) { this.securedRevenue = securedRevenue; }

    public Integer getDailyCapacityHrs() { return dailyCapacityHrs; }
    public void setDailyCapacityHrs(Integer dailyCapacityHrs) { this.dailyCapacityHrs = dailyCapacityHrs; }

    public String getRegionalJson() { return regionalJson; }
    public void setRegionalJson(String regionalJson) { this.regionalJson = regionalJson; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
