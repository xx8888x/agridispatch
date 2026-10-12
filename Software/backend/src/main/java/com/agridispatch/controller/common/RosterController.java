package com.agridispatch.controller.common;

import com.agridispatch.security.AuthContext;
import com.agridispatch.service.RosterService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 社區共享班表 API
 * - GET  /api/rosters                ：admin 全部；farmer 開放認領與已開班（工人 403）
 * - GET  /api/rosters/{id}           ：單一班表（含每天上午／下午時段格）
 * - POST /api/rosters                ：開班（admin）{title, region, description?, teamId, crew:[工號], dispatchCategory?,
 *                                       startDate, endDate, minClaimPercent?, claimDeadline?}
 * - POST /api/rosters/{id}/claims    ：認領時段（farmer）{date, timeSlot, farmId?, farmerName, phone, farmLocation, cropId, area, plan?, foremanNotes?}
 * - POST /api/rosters/{id}/confirm   ：確認開班（admin，須已達門檻）
 * - POST /api/rosters/{id}/cancel    ：取消班表與其認領訂單（admin）{reason?}
 * 認領的時段要退出：以 DELETE /api/orders/{orderId} 取消該訂單
 */
@RestController
@RequestMapping("/api/rosters")
public class RosterController {

    private final RosterService rosterService;

    public RosterController(RosterService rosterService) {
        this.rosterService = rosterService;
    }

    @GetMapping
    public ResponseEntity<?> list() {
        return ResponseEntity.ok(Map.of("success", true, "data", rosterService.list(AuthContext.require())));
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(@PathVariable String id) {
        return ResponseEntity.ok(Map.of("success", true, "data", rosterService.get(AuthContext.require(), id)));
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody Map<String, Object> req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("success", true,
                "data", rosterService.create(AuthContext.require(), req), "message", "共享班表已開放認領"));
    }

    @PostMapping("/{id}/claims")
    public ResponseEntity<?> claim(@PathVariable String id, @RequestBody Map<String, Object> req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("success", true,
                "data", rosterService.claim(AuthContext.require(), id, req), "message", "已認領時段，成立訂單"));
    }

    @PostMapping("/{id}/confirm")
    public ResponseEntity<?> confirm(@PathVariable String id) {
        return ResponseEntity.ok(Map.of("success", true, "data", rosterService.confirm(AuthContext.require(), id), "message", "班表已開班"));
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<?> cancel(@PathVariable String id, @RequestBody(required = false) Map<String, Object> req) {
        Object reason = req == null ? null : req.get("reason");
        return ResponseEntity.ok(Map.of("success", true,
                "data", rosterService.cancel(AuthContext.require(), id, reason == null ? null : reason.toString()), "message", "班表已取消"));
    }
}
