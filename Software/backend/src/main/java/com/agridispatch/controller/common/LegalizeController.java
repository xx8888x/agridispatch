package com.agridispatch.controller.common;

import com.agridispatch.security.AuthContext;
import com.agridispatch.service.LegalizeService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 工班合法化（黑轉白）API
 * - GET  /api/legalize/types           身分類型、分流路徑與轉白步驟（公開，法規參考文字）
 * - GET  /api/legalize/roster          農場主：自己的清冊；admin：全部（可 ?farmerId= 篩選）
 * - POST /api/legalize/roster          登錄現有工人（admin 須帶 farmerId）
 * - PUT  /api/legalize/roster/{id}     勾選步驟（stepIndex+checked 或 done[]）、改工作內容／天數
 * - DELETE /api/legalize/roster/{id}   從清冊移除
 * - GET  /api/legalize/summary         各農場進度、補位缺口與罰鍰暴露彙總（僅 admin，AuthInterceptor 檢查）
 * - POST /api/legalize/roster/reset    demo 模式：清冊重設為示範資料（非 demo 回 404）
 */
@RestController
@RequestMapping("/api/legalize")
public class LegalizeController {

    private final LegalizeService service;

    public LegalizeController(LegalizeService service) {
        this.service = service;
    }

    @GetMapping("/types")
    public ResponseEntity<?> types() {
        return ResponseEntity.ok(Map.of("success", true, "data", service.types()));
    }

    @GetMapping("/roster")
    public ResponseEntity<?> roster(@RequestParam(required = false) String farmerId) {
        AuthContext ctx = AuthContext.require();
        return ResponseEntity.ok(Map.of("success", true, "data", service.roster(ctx, farmerId)));
    }

    @PostMapping("/roster")
    public ResponseEntity<?> add(@RequestBody Map<String, Object> req) {
        AuthContext ctx = AuthContext.require();
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("success", true, "data", service.add(ctx, req)));
    }

    @PutMapping("/roster/{id}")
    public ResponseEntity<?> update(@PathVariable String id, @RequestBody Map<String, Object> req) {
        AuthContext ctx = AuthContext.require();
        return ResponseEntity.ok(Map.of("success", true, "data", service.update(ctx, id, req)));
    }

    @DeleteMapping("/roster/{id}")
    public ResponseEntity<?> remove(@PathVariable String id) {
        AuthContext ctx = AuthContext.require();
        service.remove(ctx, id);
        return ResponseEntity.ok(Map.of("success", true));
    }

    @GetMapping("/summary")
    public ResponseEntity<?> summary() {
        AuthContext ctx = AuthContext.require();
        return ResponseEntity.ok(Map.of("success", true, "data", service.summary(ctx)));
    }

    @PostMapping("/roster/reset")
    public ResponseEntity<?> reset(@RequestBody(required = false) Map<String, Object> req) {
        AuthContext ctx = AuthContext.require();
        String farmerId = req != null && req.get("farmerId") != null ? String.valueOf(req.get("farmerId")) : null;
        int n = service.resetDemo(ctx, farmerId);
        return ResponseEntity.ok(Map.of("success", true, "data", Map.of("count", n), "message", "已重設為示範資料"));
    }
}
