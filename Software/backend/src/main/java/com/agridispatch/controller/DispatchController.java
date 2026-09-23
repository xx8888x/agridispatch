package com.agridispatch.controller;

import com.agridispatch.entity.DispatchTeam;
import com.agridispatch.service.DispatchService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/dispatch")
public class DispatchController {
    private final DispatchService dispatchService;

    public DispatchController(DispatchService dispatchService) {
        this.dispatchService = dispatchService;
    }

    @GetMapping("/overview")
    public ResponseEntity<?> getOverview() {
        return ResponseEntity.ok(Map.of("success", true, "data", dispatchService.getOverview()));
    }

    @GetMapping("/weather-state")
    public ResponseEntity<?> getWeatherState() {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("scenarioId", dispatchService.getCurrentScenarioId());
        state.put("isRainMode", dispatchService.isRainMode());
        state.put("currentWeather", dispatchService.getCurrentWeather());
        state.put("alertLevel", dispatchService.getAlertLevel());
        state.put("weatherMessage", dispatchService.getWeatherMessage());
        state.put("regionalWeather", dispatchService.getRegionalWeather());
        return ResponseEntity.ok(Map.of("success", true, "data", state));
    }

    @PostMapping("/weather-scenario")
    public ResponseEntity<?> applyWeatherScenario(@RequestBody(required = false) Map<String, Object> req) {
        String scenarioId = "sunny";
        if (req != null && req.containsKey("scenarioId")) {
            scenarioId = req.get("scenarioId").toString();
        }
        Map<String, Object> result = dispatchService.applyWeatherScenario(scenarioId);
        return ResponseEntity.ok(Map.of("success", true, "data", result, "message", "已成功切換天候情境: " + scenarioId));
    }

    @PutMapping("/weather-regions")
    public ResponseEntity<?> updateWeatherRegions(@RequestBody List<Map<String, Object>> regions) {
        List<Map<String, Object>> updated = dispatchService.updateRegionalWeather(regions);
        return ResponseEntity.ok(Map.of("success", true, "data", updated, "message", "產區微氣候資料已更新"));
    }

    @GetMapping("/teams")
    public ResponseEntity<?> getAllTeams() {
        return ResponseEntity.ok(Map.of("success", true, "data", dispatchService.getAllTeams()));
    }

    @GetMapping("/teams/{id}")
    public ResponseEntity<?> getTeamById(@PathVariable String id) {
        return dispatchService.getTeamById(id)
                .map(team -> ResponseEntity.ok(Map.of("success", true, "data", team)))
                .orElse(ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("success", false, "error", "找不到該工班代號: " + id)));
    }

    @PutMapping("/teams/{id}")
    public ResponseEntity<?> updateTeam(@PathVariable String id, @RequestBody DispatchTeam team) {
        try {
            DispatchTeam updated = dispatchService.updateTeam(id, team);
            return ResponseEntity.ok(Map.of("success", true, "data", updated, "message", "成功更新工班資訊"));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    @PostMapping("/weather-switch")
    public ResponseEntity<?> toggleWeatherSwitch(@RequestBody(required = false) Map<String, Object> req) {
        boolean rain = true;
        if (req != null && req.containsKey("isRainMode")) {
            rain = Boolean.parseBoolean(req.get("isRainMode").toString());
        } else {
            rain = !dispatchService.isRainMode();
        }
        Map<String, Object> result = dispatchService.setRainMode(rain);
        return ResponseEntity.ok(Map.of("success", true, "data", result, "message", "天候避險狀態已切換"));
    }
}
