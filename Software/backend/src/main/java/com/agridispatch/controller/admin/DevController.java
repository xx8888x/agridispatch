package com.agridispatch.controller.admin;

import com.agridispatch.service.DispatchService;
import com.agridispatch.service.SeedService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 開發 / 路演工具：僅 demo 模式 + admin_tech（非 demo 模式由 AuthInterceptor 回 404）
 */
@RestController
@RequestMapping("/api/dev")
public class DevController {

    private final SeedService seedService;
    private final DispatchService dispatchService;

    public DevController(SeedService seedService, DispatchService dispatchService) {
        this.seedService = seedService;
        this.dispatchService = dispatchService;
    }

    @PostMapping("/seed")
    public ResponseEntity<?> seedDatabase(@RequestParam(defaultValue = "false") boolean cleanFirst) {
        seedService.seedAll(cleanFirst);
        dispatchService.restoreStateFromDb();
        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", cleanFirst ? "已清空舊資料並成功注入全套擬真假資料！" : "已成功補足/注入全套擬真假資料！"
        ));
    }
}

