package com.agridispatch.controller.common;

import com.agridispatch.common.ApiException;
import com.agridispatch.config.AgriSettings;
import com.agridispatch.security.AuthContext;
import com.agridispatch.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 認證端點（規格第 4 節）
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService authService;
    private final AgriSettings settings;

    public AuthController(AuthService authService, AgriSettings settings) {
        this.authService = authService;
        this.settings = settings;
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Map<String, Object> req, HttpServletRequest request) {
        String identifier = str(req.get("identifier"));
        if (identifier == null || identifier.isBlank()) {
            identifier = str(req.get("username"));
        }
        String password = str(req.get("password"));

        // 來源 IP 一律取連線端位址（不信任可偽造的 X-Forwarded-For）；部署在反向代理後須設定 server.forward-headers-strategy
        return authService.login(identifier, password, request.getRemoteAddr())
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElse(ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of("success", false, "error", "帳號或密碼錯誤")));
    }

    /** 農場主自助註冊：成功回 200 {success, token, user}（同 login），手機已註冊 409，次數過多 429 */
    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody Map<String, Object> req, HttpServletRequest request) {
        return ResponseEntity.ok(authService.registerFarmer(req, request.getRemoteAddr()));
    }

    /** demo 模式免密登入；非 demo 模式回 404 */
    @PostMapping("/demo-login")
    public ResponseEntity<?> demoLogin(@RequestBody(required = false) Map<String, Object> req) {
        if (!settings.isDemoEnabled()) {
            throw ApiException.notFound("NOT_FOUND");
        }
        String role = req != null ? str(req.get("role")) : null;
        String refId = req != null ? str(req.get("refId")) : null;
        return ResponseEntity.ok(authService.demoLogin(role, refId));
    }

    @GetMapping("/me")
    public ResponseEntity<?> me() {
        AuthContext ctx = AuthContext.require();
        return ResponseEntity.ok(Map.of("success", true, "user", authService.me(ctx)));
    }

    /** 僅 demo 模式：示範帳號清單（不含密碼 / 證號） */
    @GetMapping("/quick-profiles")
    public ResponseEntity<?> getQuickProfiles() {
        return ResponseEntity.ok(Map.of(
                "success", true,
                "data", authService.getQuickProfiles()
        ));
    }

    /** 本人變更密碼：{oldPassword, newPassword} */
    @PostMapping("/change-password")
    public ResponseEntity<?> changePassword(@RequestBody Map<String, Object> req) {
        AuthContext ctx = AuthContext.require();
        authService.changePassword(ctx, str(req.get("oldPassword")), str(req.get("newPassword")));
        return ResponseEntity.ok(Map.of("success", true, "message", "密碼已更新"));
    }

    /** 管理員重設密碼：{identifier, newPassword}（授權由 AuthInterceptor 檢查 admin_tech） */
    @PostMapping("/reset-password")
    public ResponseEntity<?> resetPassword(@RequestBody Map<String, Object> req) {
        authService.resetPassword(str(req.get("identifier")), str(req.get("newPassword")));
        return ResponseEntity.ok(Map.of("success", true, "message", "密碼已重設"));
    }

    /** 管理員帳號清單：{identifier, name, role, refId, roleDesc, kind, hasPassword}（授權由 AuthInterceptor 檢查 admin_tech） */
    @GetMapping("/accounts")
    public ResponseEntity<?> accounts() {
        return ResponseEntity.ok(Map.of("success", true, "data", authService.listAccounts()));
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }
}

