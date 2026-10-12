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
        if (!isAdmin()) {
            overview.remove("securedRevenue");
            List<Map<String, Object>> teams = new ArrayList<>();
            for (Object o : (List<?>) overview.get("teams")) teams.add(publicView((DispatchTeam) o));
            overview.put("teams", teams);
        }
        return ResponseEntity.ok(Map.of("success", true, "data", overview));
    }

    private static boolean isAdmin() {
        AuthContext ctx = AuthContext.current();
        return ctx != null && ctx.isAdmin();
    }

    /** 非 admin 看到的工班：不含領班電話、司機與車輛（個資與調度資訊只給科技後台） */
    private static Map<String, Object> publicView(DispatchTeam t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.getId());
        m.put("name", t.getName());
        m.put("workerCount", t.getWorkerCount());
        m.put("normalLocation", t.getNormalLocation());
        m.put("normalTask", t.getNormalTask());
        m.put("hedgeLocation", t.getHedgeLocation());
        m.put("hedgeTask", t.getHedgeTask());
        m.put("status", t.getStatus());
        return m;
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

    /** 需登入；非 admin（農場主看工班名稱、工人）只回公開欄位 */
    @GetMapping("/teams")
    public ResponseEntity<?> getAllTeams() {
        List<DispatchTeam> teams = dispatchService.getAllTeams();
        Object data = isAdmin() ? teams : teams.stream().map(DispatchController::publicView).toList();
        return ResponseEntity.ok(Map.of("success", true, "data", data));
    }

    @GetMapping("/teams/{id}")
    public ResponseEntity<?> getTeamById(@PathVariable String id) {
        return dispatchService.getTeamById(id)
                .map(team -> ResponseEntity.ok(Map.of("success", true, "data", isAdmin() ? team : publicView(team))))
                .orElse(ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("success", false, "error", "找不到該工班代號: " + id)));
    }

    /** 新增工班（admin，AuthInterceptor 擋 POST /api/dispatch/**） */
    @PostMapping("/teams")
    public ResponseEntity<?> createTeam(@RequestBody DispatchTeam team) {
        DispatchTeam created = dispatchService.createTeam(team);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Map.of("success", true, "data", created, "message", "成功新增工班: " + created.getName()));
    }

    /** 刪除工班（admin）：仍有工人、訂單或評價時回 409 */
    @DeleteMapping("/teams/{id}")
    public ResponseEntity<?> deleteTeam(@PathVariable String id) {
        dispatchService.deleteTeam(id);
        return ResponseEntity.ok(Map.of("success", true, "message", "成功刪除工班: " + id));
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

