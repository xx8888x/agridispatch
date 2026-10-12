package com.agridispatch.controller.front;

import com.agridispatch.security.AuthContext;
import com.agridispatch.service.FarmProfileService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 我的農場（預約派工第一步，僅 farmer）
 * - GET /api/farmers/me：已登錄回農場資料，尚未登錄 data = null
 * - PUT /api/farmers/me：新增或修改農場資料
 */
@RestController
@RequestMapping("/api/farmers/me")
public class FarmProfileController {

    private final FarmProfileService farmProfileService;

    public FarmProfileController(FarmProfileService farmProfileService) {
        this.farmProfileService = farmProfileService;
    }

    @GetMapping
    public ResponseEntity<?> get() {
        Map<String, Object> body = new HashMap<>();
        body.put("success", true);
        body.put("data", farmProfileService.get(AuthContext.require()));   // Map.of 不接受 null
        return ResponseEntity.ok(body);
    }

    @PutMapping
    public ResponseEntity<?> save(@RequestBody Map<String, Object> req) {
        return ResponseEntity.ok(Map.of("success", true, "data", farmProfileService.save(AuthContext.require(), req),
                "message", "農場資料已儲存"));
    }
}
