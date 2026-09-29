package com.agridispatch.controller.common;

import com.agridispatch.entity.Crop;
import com.agridispatch.service.CropService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/crops")
public class CropController {
    private final CropService cropService;

    public CropController(CropService cropService) {
        this.cropService = cropService;
    }

    @GetMapping
    public ResponseEntity<?> getAllCrops() {
        return ResponseEntity.ok(Map.of("success", true, "data", cropService.getAllCrops()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getCropById(@PathVariable String id) {
        return cropService.getCropById(id)
                .map(crop -> ResponseEntity.ok(Map.of("success", true, "data", crop)))
                .orElse(ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("success", false, "error", "找不到該作物代號: " + id)));
    }

    @PostMapping
    public ResponseEntity<?> createCrop(@RequestBody Crop crop) {
        Crop created = cropService.createCrop(crop);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Map.of("success", true, "data", created, "message", "成功新增作物基準"));
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> updateCrop(@PathVariable String id, @RequestBody Crop crop) {
        Crop updated = cropService.updateCrop(id, crop);
        return ResponseEntity.ok(Map.of("success", true, "data", updated, "message", "成功更新作物基準"));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteCrop(@PathVariable String id) {
        cropService.deleteCrop(id);
        return ResponseEntity.ok(Map.of("success", true, "message", "成功刪除作物基準: " + id));
    }
}

