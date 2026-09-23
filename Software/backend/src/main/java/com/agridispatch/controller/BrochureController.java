package com.agridispatch.controller;

import com.agridispatch.entity.Brochure;
import com.agridispatch.service.BrochureService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/brochures")
public class BrochureController {

    private final BrochureService brochureService;

    public BrochureController(BrochureService brochureService) {
        this.brochureService = brochureService;
    }

    @GetMapping
    public ResponseEntity<?> getAllBrochures(@RequestParam(required = false, defaultValue = "false") boolean all) {
        return ResponseEntity.ok(Map.of("success", true, "data", brochureService.getAllBrochures(!all)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getBrochureById(@PathVariable String id) {
        return brochureService.getBrochureById(id)
                .map(brochure -> ResponseEntity.ok(Map.of("success", true, "data", brochure)))
                .orElse(ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("success", false, "error", "找不到該產品文宣 DM: " + id)));
    }

    @PostMapping
    public ResponseEntity<?> createBrochure(@RequestBody Brochure brochure) {
        // 新增時若 ID 已存在不可覆寫
        if (brochure.getId() != null && !brochure.getId().isBlank() && brochureService.getBrochureById(brochure.getId()).isPresent()) {
            throw com.agridispatch.common.ApiException.conflict("文宣 DM 編號已存在: " + brochure.getId());
        }
        Brochure saved = brochureService.saveBrochure(brochure);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Map.of("success", true, "data", saved, "message", "成功上傳/發布產品文宣 DM"));
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> updateBrochure(@PathVariable String id, @RequestBody Brochure brochure) {
        if (brochureService.getBrochureById(id).isEmpty()) {
            throw com.agridispatch.common.ApiException.notFound("找不到該產品文宣 DM: " + id);
        }
        brochure.setId(id);
        Brochure updated = brochureService.saveBrochure(brochure);
        return ResponseEntity.ok(Map.of("success", true, "data", updated, "message", "成功更新產品文宣 DM"));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteBrochure(@PathVariable String id) {
        brochureService.deleteBrochure(id);
        return ResponseEntity.ok(Map.of("success", true, "message", "成功刪除產品文宣 DM: " + id));
    }

    @PostMapping("/seed")
    public ResponseEntity<?> seedBrochures() {
        brochureService.seedDefaultBrochures();
        return ResponseEntity.ok(Map.of("success", true, "message", "成功初始化/重置產品文宣 DM 範本", "data", brochureService.getAllBrochures(false)));
    }
}
