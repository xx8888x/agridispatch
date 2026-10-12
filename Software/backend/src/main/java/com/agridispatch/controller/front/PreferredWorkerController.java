package com.agridispatch.controller.front;

import com.agridispatch.security.AuthContext;
import com.agridispatch.service.PreferredWorkerService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 指名回頭工人（僅 farmer）
 * - GET /api/farmers/me/past-workers?start=&end=：合作過的工人；給工期時附 available / unavailableReason
 * 下單時把 preferredWorkerId 放進 POST /api/orders，由後端驗證
 */
@RestController
@RequestMapping("/api/farmers/me/past-workers")
public class PreferredWorkerController {

    private final PreferredWorkerService preferredWorkerService;

    public PreferredWorkerController(PreferredWorkerService preferredWorkerService) {
        this.preferredWorkerService = preferredWorkerService;
    }

    @GetMapping
    public ResponseEntity<?> list(@RequestParam(required = false) String start, @RequestParam(required = false) String end) {
        return ResponseEntity.ok(Map.of("success", true,
                "data", preferredWorkerService.pastWorkers(AuthContext.require(), start, end)));
    }
}
