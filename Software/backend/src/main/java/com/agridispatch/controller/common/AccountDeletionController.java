package com.agridispatch.controller.common;

import com.agridispatch.security.AuthContext;
import com.agridispatch.service.AccountDeletionService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 刪除帳號申請（Google Play 刪除帳號規定）
 * - POST   /api/account/deletion-request        本人申請 {reason?}（App「我的帳號」）
 * - GET    /api/account/deletion-request        本人最近一筆申請
 * - DELETE /api/account/deletion-request        本人撤回待處理的申請
 * - POST   /api/public/account-deletion         免登入網頁申請 {name, phone, account?, reason?}（同一 IP 每小時 5 次；回應不透露帳號是否存在）
 * - GET    /api/account-deletions               admin：全部申請
 * - POST   /api/account-deletions/{id}/complete admin：完成刪除 {identifier?（網頁申請必填）, note?}
 * - POST   /api/account-deletions/{id}/reject   admin：不受理 {note}
 */
@RestController
public class AccountDeletionController {

    private final AccountDeletionService service;

    public AccountDeletionController(AccountDeletionService service) {
        this.service = service;
    }

    @PostMapping("/api/account/deletion-request")
    public ResponseEntity<?> request(@RequestBody(required = false) Map<String, Object> req) {
        Map<String, Object> data = service.requestFromApp(AuthContext.require(), str(req, "reason"));
        return ResponseEntity.ok(Map.of("success", true, "data", data,
                "message", "已送出申請，合作社核對身分後會處理並通知您"));
    }

    @GetMapping("/api/account/deletion-request")
    public ResponseEntity<?> mine() {
        Map<String, Object> res = new HashMap<>();
        res.put("success", true);
        res.put("data", service.mine(AuthContext.require()));
        return ResponseEntity.ok(res);
    }

    @DeleteMapping("/api/account/deletion-request")
    public ResponseEntity<?> withdraw() {
        return ResponseEntity.ok(Map.of("success", true, "data", service.withdraw(AuthContext.require()),
                "message", "已撤回刪除帳號申請"));
    }

    @PostMapping("/api/public/account-deletion")
    public ResponseEntity<?> web(@RequestBody Map<String, Object> req, HttpServletRequest request) {
        service.requestFromWeb(str(req, "name"), str(req, "phone"), str(req, "account"), str(req, "reason"), request.getRemoteAddr());
        return ResponseEntity.ok(Map.of("success", true,
                "message", "已收到您的申請。合作社會以您留的電話聯絡核對身分，確認後刪除帳號。"));
    }

    @GetMapping("/api/account-deletions")
    public ResponseEntity<?> list() {
        AuthContext.require().requireAdmin();
        return ResponseEntity.ok(Map.of("success", true, "data", service.list()));
    }

    @PostMapping("/api/account-deletions/{id}/complete")
    public ResponseEntity<?> complete(@PathVariable String id, @RequestBody(required = false) Map<String, Object> req) {
        AuthContext ctx = AuthContext.require();
        ctx.requireAdmin();
        return ResponseEntity.ok(Map.of("success", true, "data", service.complete(id, str(req, "identifier"), str(req, "note"), ctx)));
    }

    @PostMapping("/api/account-deletions/{id}/reject")
    public ResponseEntity<?> reject(@PathVariable String id, @RequestBody(required = false) Map<String, Object> req) {
        AuthContext ctx = AuthContext.require();
        ctx.requireAdmin();
        return ResponseEntity.ok(Map.of("success", true, "data", service.reject(id, str(req, "note"), ctx)));
    }

    private static String str(Map<String, Object> m, String k) {
        Object v = m == null ? null : m.get(k);
        return v == null ? null : v.toString();
    }
}
