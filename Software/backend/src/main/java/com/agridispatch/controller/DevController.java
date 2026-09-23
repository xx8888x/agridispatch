package com.agridispatch.controller;

import com.agridispatch.service.SeedService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/dev")
public class DevController {

    private final SeedService seedService;

    public DevController(SeedService seedService) {
        this.seedService = seedService;
    }

    @PostMapping("/seed")
    public ResponseEntity<?> seedDatabase(@RequestParam(defaultValue = "false") boolean cleanFirst) {
        seedService.seedAll(cleanFirst);
        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", cleanFirst ? "已清空舊資料並成功注入全套擬真假資料！" : "已成功補足/注入全套擬真假資料！"
        ));
    }
}
