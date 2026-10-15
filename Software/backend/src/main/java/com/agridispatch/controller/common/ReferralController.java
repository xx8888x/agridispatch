package com.agridispatch.controller.common;

import com.agridispatch.security.AuthContext;
import com.agridispatch.service.ReferralService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 推薦裂變（邀請連結）
 * - GET  /api/referrals/me                  本人推薦碼、成效、被邀請者（農場主與工人）
 * - GET  /api/public/referral/{code}        免登入：開啟邀請連結（計一次開啟，回邀請人遮罩姓名）
 * - POST /api/public/referral/lead          免登入：工人意願登記 {code, name, phone, pdpaConsent, channel?}（同一 IP 每小時 10 次）
 * - GET  /api/referrals                     合作社／科技後台：全部推薦紀錄
 * - POST /api/referrals/{id}/status         合作社／科技後台：更新工人意願登記的跟進狀態 {status, note?}
 * 農場主被邀請時走既有註冊 POST /api/auth/register，多帶 refCode、refChannel。
 */
@RestController
public class ReferralController {

    private final ReferralService service;

    public ReferralController(ReferralService service) {
        this.service = service;
    }

    @GetMapping("/api/referrals/me")
    public ResponseEntity<?> mine() {
        return ResponseEntity.ok(Map.of("success", true, "data", service.mine(AuthContext.require())));
    }

    @GetMapping("/api/public/referral/{code}")
    public ResponseEntity<?> open(@PathVariable String code) {
        Map<String, Object> res = new HashMap<>(service.open(code));
        res.put("success", true);
        return ResponseEntity.ok(res);
    }

    @PostMapping("/api/public/referral/lead")
    public ResponseEntity<?> lead(@RequestBody Map<String, Object> req, HttpServletRequest request) {
        service.submitWorkerLead(req, request.getRemoteAddr());
        return ResponseEntity.ok(Map.of("success", true,
                "message", "已收到您的登記。合作社會以您留的電話聯絡，並說明需要準備的證件。"));
    }

    @GetMapping("/api/referrals")
    public ResponseEntity<?> list() {
        return ResponseEntity.ok(Map.of("success", true, "data", service.list()));
    }

    @PostMapping("/api/referrals/{id}/status")
    public ResponseEntity<?> status(@PathVariable String id, @RequestBody Map<String, Object> req) {
        Object status = req.get("status"), note = req.get("note");
        return ResponseEntity.ok(Map.of("success", true,
                "data", service.updateStatus(id, status == null ? null : status.toString(), note == null ? null : note.toString())));
    }
}
