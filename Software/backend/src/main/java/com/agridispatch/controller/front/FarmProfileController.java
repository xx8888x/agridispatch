package com.agridispatch.controller.front;

import com.agridispatch.security.AuthContext;
import com.agridispatch.service.FarmProfileService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 我的農場（預約派工第一步，僅 farmer）：可登錄多個，預約時選一個
 * - GET    /api/farmers/me/farms：本人的農場清單（依登錄先後）
 * - POST   /api/farmers/me/farms：新增
 * - PUT    /api/farmers/me/farms/{id}：修改
 * - DELETE /api/farmers/me/farms/{id}：刪除
 */
@RestController
@RequestMapping("/api/farmers/me/farms")
public class FarmProfileController {

    private final FarmProfileService farmProfileService;

    public FarmProfileController(FarmProfileService farmProfileService) {
        this.farmProfileService = farmProfileService;
    }

    @GetMapping
    public ResponseEntity<?> list() {
        return ResponseEntity.ok(Map.of("success", true, "data", farmProfileService.list(AuthContext.require())));
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody Map<String, Object> req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("success", true,
                "data", farmProfileService.create(AuthContext.require(), req), "message", "農場已登錄"));
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable String id, @RequestBody Map<String, Object> req) {
        return ResponseEntity.ok(Map.of("success", true,
                "data", farmProfileService.update(AuthContext.require(), id, req), "message", "農場資料已儲存"));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable String id) {
        farmProfileService.delete(AuthContext.require(), id);
        return ResponseEntity.ok(Map.of("success", true, "message", "農場已刪除"));
    }
}
