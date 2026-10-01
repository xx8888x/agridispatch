package com.agridispatch.controller.common;

import com.agridispatch.entity.Order;
import com.agridispatch.security.AuthContext;
import com.agridispatch.service.MatchingService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 派工媒合 API（僅 admin）
 * - GET /api/matching/orders/{orderId}              ：工班排序＋出工名單建議
 * - GET /api/matching/orders/{orderId}/crew?teamId= ：指定工班的出工名單建議
 * - PUT /api/matching/orders/{orderId}              ：確認派工 {teamId, crew:[工號...]}
 */
@RestController
@RequestMapping("/api/matching/orders/{orderId}")
public class MatchingController {

    private final MatchingService matchingService;

    public MatchingController(MatchingService matchingService) {
        this.matchingService = matchingService;
    }

    @GetMapping
    public ResponseEntity<?> recommend(@PathVariable String orderId) {
        AuthContext.require().requireAdmin();
        return ResponseEntity.ok(Map.of("success", true, "data", matchingService.recommend(orderId)));
    }

    @GetMapping("/crew")
    public ResponseEntity<?> crew(@PathVariable String orderId, @RequestParam String teamId) {
        AuthContext.require().requireAdmin();
        return ResponseEntity.ok(Map.of("success", true, "data", matchingService.crewFor(orderId, teamId)));
    }

    @PutMapping
    public ResponseEntity<?> assign(@PathVariable String orderId, @RequestBody Map<String, Object> req) {
        AuthContext.require().requireAdmin();
        Order saved = matchingService.assign(orderId, req.get("teamId"), req.get("crew"));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("orderId", saved.getId());
        data.put("assignedTeamId", saved.getAssignedTeamId());
        data.put("crew", saved.crewList());
        return ResponseEntity.ok(Map.of("success", true, "data", data));
    }
}
