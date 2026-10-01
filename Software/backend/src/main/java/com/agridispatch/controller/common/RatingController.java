package com.agridispatch.controller.common;

import com.agridispatch.entity.TeamRating;
import com.agridispatch.security.AuthContext;
import com.agridispatch.service.RatingService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 工班評價 API（皆需登入）
 * - PUT /api/ratings/orders/{orderId}：訂單擁有者（farmer）或 admin 新增／修改評價，工班出工後才可評
 * - GET /api/ratings/orders/{orderId}：讀取權限同訂單
 * - GET /api/ratings/teams、/api/ratings/teams/{teamId}：工班評價彙總
 */
@RestController
@RequestMapping("/api/ratings")
public class RatingController {

    private final RatingService ratingService;

    public RatingController(RatingService ratingService) {
        this.ratingService = ratingService;
    }

    @GetMapping("/tags")
    public ResponseEntity<?> tags() {
        return ResponseEntity.ok(Map.of("success", true, "data", RatingService.ALLOWED_TAGS));
    }

    @GetMapping("/teams")
    public ResponseEntity<?> teams() {
        AuthContext.require();
        return ResponseEntity.ok(Map.of("success", true, "data", ratingService.teamSummaries()));
    }

    @GetMapping("/teams/{teamId}")
    public ResponseEntity<?> team(@PathVariable String teamId) {
        AuthContext ctx = AuthContext.require();
        return ResponseEntity.ok(Map.of("success", true, "data", ratingService.teamDetail(ctx, teamId)));
    }

    @GetMapping("/orders/{orderId}")
    public ResponseEntity<?> forOrder(@PathVariable String orderId) {
        AuthContext ctx = AuthContext.require();
        Map<String, Object> body = new HashMap<>();
        body.put("success", true);
        body.put("data", ratingService.getForOrder(ctx, orderId).map(r -> ratingService.toView(r, true)).orElse(null));
        return ResponseEntity.ok(body);
    }

    @PutMapping("/orders/{orderId}")
    public ResponseEntity<?> rate(@PathVariable String orderId, @RequestBody Map<String, Object> req) {
        AuthContext ctx = AuthContext.require();
        TeamRating saved = ratingService.rate(ctx, orderId, req.get("score"), req.get("tags"), req.get("comment"));
        return ResponseEntity.ok(Map.of("success", true, "data", ratingService.toView(saved, true)));
    }
}
