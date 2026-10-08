package com.agridispatch.controller.common;

import com.agridispatch.entity.DispatchTeam;
import com.agridispatch.security.AuthContext;
import com.agridispatch.service.DispatchService;
import com.agridispatch.service.LineNotifyService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/dispatch")
public class DispatchController {
    private final DispatchService dispatchService;
    private final LineNotifyService lineNotifyService;

    public DispatchController(DispatchService dispatchService, LineNotifyService lineNotifyService) {
        this.dispatchService = dispatchService;
        this.lineNotifyService = lineNotifyService;
    }

    /** 公開端點：未登入或非 admin 只回天候與工班動態，不含領班電話、司機與營收（規格 03 §5） */
    @GetMapping("/overview")
    public ResponseEntity<?> getOverview() {
        Map<String, Object> overview = dispatchService.getOverview();
        AuthContext ctx = AuthContext.current();
        if (ctx == null || !ctx.isAdmin()) {
            overview.remove("securedRevenue");
            List<Map<String, Object>> teams = new ArrayList<>();
            for (Object o : (List<?>) overview.get("teams")) {
                DispatchTeam t = (DispatchTeam) o;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", t.getId());
                m.put("name", t.getName());
                m.put("workerCount", t.getWorkerCount());
                m.put("normalLocation", t.getNormalLocation());
                m.put("normalTask", t.getNormalTask());
                m.put("hedgeLocation", t.getHedgeLocation());
                m.put("hedgeTask", t.getHedgeTask());
                m.put("status", t.getStatus());
                teams.add(m);
            }
            overview.put("teams", teams);
        }
        return ResponseEntity.ok(Map.of("success", true, "data", overview));
    }

    @GetMapping("/weather-state")
    public ResponseEntity<?> getWeatherState() {
        return ResponseEntity.ok(Map.of("success", true, "data", dispatchService.getWeatherStateSnapshot()));
    }

    @PostMapping("/weather-scenario")
    public ResponseEntity<?> applyWeatherScenario(@RequestBody(required = false) Map<String, Object> req) {
        String scenarioId = "sunny";
        if (req != null && req.get("scenarioId") != null) {
            scenarioId = req.get("scenarioId").toString();
        }
        String previous = dispatchService.getCurrentScenarioId();
        Map<String, Object> result = dispatchService.applyWeatherScenario(scenarioId);
        lineNotifyService.weatherChanged(previous, result); // 切到警戒時 LINE 通知受影響訂單的農場主與出工名單（背景執行）
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
    public ResponseEntity<?> updateTeam(@PathVariable String id, @RequestBody Map<String, Object> body) {
        DispatchTeam updated = dispatchService.updateTeam(id, body);
        return ResponseEntity.ok(Map.of("success", true, "data", updated, "message", "成功更新工班資訊"));
    }

    @PostMapping("/weather-switch")
    public ResponseEntity<?> toggleWeatherSwitch(@RequestBody(required = false) Map<String, Object> req) {
        boolean rain = true;
        if (req != null && req.containsKey("isRainMode")) {
            rain = Boolean.parseBoolean(req.get("isRainMode").toString());
        } else {
            rain = !dispatchService.isRainMode();
        }
        String previous = dispatchService.getCurrentScenarioId();
        Map<String, Object> result = dispatchService.setRainMode(rain);
        lineNotifyService.weatherChanged(previous, result);
        return ResponseEntity.ok(Map.of("success", true, "data", result, "message", "天候避險狀態已切換"));
    }
}

