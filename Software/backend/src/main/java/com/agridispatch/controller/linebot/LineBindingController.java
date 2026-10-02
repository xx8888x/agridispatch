package com.agridispatch.controller.linebot;

import com.agridispatch.security.AuthContext;
import com.agridispatch.service.LineBindingService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * LINE 官方帳號綁定（規格 05 第 5.3、5.4、6.4 節）
 * - POST   /api/line/bind              本人：{channel, idToken} → 綁定
 * - DELETE /api/line/bind/{channel}    本人：解除自己在該帳號的綁定
 * - GET    /api/line/bindings          admin：綁定清單（LINE userId 遮罩）
 * - DELETE /api/line/bindings/{id}     admin：強制解除綁定
 */
@RestController
@RequestMapping("/api/line")
public class LineBindingController {

    private final LineBindingService lineBindingService;

    public LineBindingController(LineBindingService lineBindingService) {
        this.lineBindingService = lineBindingService;
    }

    @PostMapping("/bind")
    public ResponseEntity<?> bind(@RequestBody Map<String, Object> req) {
        AuthContext auth = AuthContext.require();
        Object channel = req.get("channel");
        Object idToken = req.get("idToken");
        return ResponseEntity.ok(lineBindingService.bind(auth,
                channel == null ? null : String.valueOf(channel), idToken == null ? null : String.valueOf(idToken)));
    }

    @DeleteMapping("/bind/{channel}")
    public ResponseEntity<?> unbind(@PathVariable String channel) {
        lineBindingService.unbind(AuthContext.require(), channel);
        return ResponseEntity.ok(Map.of("success", true));
    }

    @GetMapping("/bindings")
    public ResponseEntity<?> list() {
        AuthContext.require().requireAdmin();
        return ResponseEntity.ok(Map.of("success", true, "bindings", lineBindingService.list()));
    }

    @DeleteMapping("/bindings/{id}")
    public ResponseEntity<?> adminUnbind(@PathVariable String id) {
        AuthContext.require().requireAdmin();
        lineBindingService.adminUnbind(id);
        return ResponseEntity.ok(Map.of("success", true));
    }
}
