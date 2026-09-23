package com.agridispatch.controller;

import com.agridispatch.service.AuthService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Map<String, String> req) {
        String identifier = req.get("identifier");
        if (identifier == null || identifier.isBlank()) {
            identifier = req.get("username");
        }
        String password = req.get("password");

        return authService.authenticate(identifier, password)
                .map(authData -> {
                    String role = (String) authData.get("role");
                    return ResponseEntity.ok(Map.of(
                            "success", true,
                            "user", authData,
                            "token", "jwt-token-" + role + "-" + System.currentTimeMillis()
                    ));
                })
                .orElse(ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of(
                                "success", false,
                                "error", "帳號或密碼錯誤。外勞可使用居留證號/護照/工號，本工可使用手機/工號，農場主請用專屬帳號。預設密碼：123456"
                        )));
    }

    @GetMapping("/quick-profiles")
    public ResponseEntity<?> getQuickProfiles() {
        return ResponseEntity.ok(Map.of(
                "success", true,
                "data", authService.getQuickProfiles()
        ));
    }
}
