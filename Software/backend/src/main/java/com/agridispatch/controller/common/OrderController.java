package com.agridispatch.controller.common;

import com.agridispatch.common.ApiException;
import com.agridispatch.entity.Order;
import com.agridispatch.security.AuthContext;
import com.agridispatch.service.OrderService;
import com.agridispatch.service.PreferredWorkerService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/orders")
public class OrderController {
    private final OrderService orderService;
    private final PreferredWorkerService preferredWorkerService;

    public OrderController(OrderService orderService, PreferredWorkerService preferredWorkerService) {
        this.orderService = orderService;
        this.preferredWorkerService = preferredWorkerService;
    }

    /** admin / 農工：全部訂單；farmer：只看自己的訂單 */
    @GetMapping
    public ResponseEntity<?> getAllOrders() {
        AuthContext ctx = AuthContext.require();
        return ResponseEntity.ok(Map.of("success", true, "data", orderService.getOrdersVisibleTo(ctx)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getOrderById(@PathVariable String id) {
        AuthContext ctx = AuthContext.require();
        Order order = orderService.requireOrder(id);
        orderService.checkReadable(ctx, order);
        return ResponseEntity.ok(Map.of("success", true, "data", order));
    }

    @GetMapping("/categories")
    public ResponseEntity<?> getDispatchCategories() {
        return ResponseEntity.ok(Map.of("success", true, "data", orderService.getDispatchCategories()));
    }

    @PostMapping("/calculate")
    public ResponseEntity<?> calculateOrder(@RequestBody Map<String, Object> req) {
        String cropId = str(req.get("cropId"), "taoyuan_veg");
        double area = req.get("area") != null ? parseArea(req.get("area")) : 5.0;
        String plan = str(req.get("plan"), "early_bird");
        String zone = str(req.get("zone"), str(req.get("transportZone"), "core_35km"));
        String dispatchCategory = str(req.get("dispatchCategory"), "harvest_grading");

        Object w = req.get("workers") != null ? req.get("workers") : req.get("requestedWorkers");
        Integer requestedWorkers = null;
        if (w != null && !w.toString().isBlank()) {
            try {
                requestedWorkers = (int) Math.round(Double.parseDouble(w.toString()));
            } catch (NumberFormatException e) {
                throw ApiException.badRequest("每日工人數 (workers) 必須為數字");
            }
        }

        Object pkg = req.get("packageId");
        Map<String, Object> result = (pkg != null && !pkg.toString().isBlank())
                ? orderService.calculatePackage(pkg.toString().trim(), zone, requestedWorkers)
                : orderService.calculateOrder(cropId, area, plan, zone, dispatchCategory, requestedWorkers);
        return ResponseEntity.ok(Map.of("success", true, "data", result));
    }

    /** 建立訂單：farmer（自動綁定 farmerId = token.ref）或 admin；preferredWorkerId 為指名工人（選填，farmer 限合作過的工人） */
    @PostMapping
    public ResponseEntity<?> createOrder(@RequestBody Order order) {
        AuthContext ctx = AuthContext.require();
        String farmerId;
        if (ctx.isAdmin()) {
            farmerId = order.getFarmerId();
        } else if (ctx.isFarmer()) {
            farmerId = ctx.ref();
            order.setAssignedTeamId(null);   // 工班由系統建議或調度指派，農場主不得自選（避開加保檢查）
        } else {
            throw ApiException.forbidden();
        }
        var preferred = preferredWorkerService.resolveForNewOrder(ctx.isAdmin() ? null : farmerId,
                order.getPreferredWorkerId(), order.getStartDate(), order.getEndDate());
        Order created = orderService.createOrder(order, farmerId, preferred);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Map.of("success", true, "data", created, "message", "成功建立派工訂單"));
    }

    /** 更新訂單（admin 或訂單擁有者）；status / depositPaid / 金額欄位一律忽略 */
    @PutMapping("/{id}")
    public ResponseEntity<?> updateOrder(@PathVariable String id, @RequestBody Order order) {
        AuthContext ctx = AuthContext.require();
        orderService.checkOwnerOrAdmin(ctx, orderService.requireOrder(id));
        Order updated = orderService.updateOrder(id, order, ctx.isAdmin());
        return ResponseEntity.ok(Map.of("success", true, "data", updated, "message", "成功更新訂單資料"));
    }

    /** admin 明確變更訂單狀態：{status}（授權由 AuthInterceptor 檢查） */
    @PutMapping("/{id}/status")
    public ResponseEntity<?> updateStatus(@PathVariable String id, @RequestBody Map<String, Object> req) {
        Order updated = orderService.changeStatus(id, str(req.get("status"), null));
        return ResponseEntity.ok(Map.of("success", true, "data", updated, "message", "訂單狀態已更新為 " + updated.getStatus()));
    }

    /** admin：刪除訂單；farmer（擁有者）：取消訂單（保留紀錄） */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteOrder(@PathVariable String id) {
        AuthContext ctx = AuthContext.require();
        if (ctx.isAdmin()) {
            orderService.deleteOrder(id);
            return ResponseEntity.ok(Map.of("success", true, "message", "成功刪除訂單: " + id));
        }
        orderService.checkOwnerOrAdmin(ctx, orderService.requireOrder(id));
        Order cancelled = orderService.cancelOrder(id);
        return ResponseEntity.ok(Map.of("success", true, "data", cancelled, "message", "成功取消訂單: " + id));
    }

    private static String str(Object o, String def) {
        return o == null || o.toString().isBlank() ? def : o.toString();
    }

    private static double parseArea(Object o) {
        try {
            return Double.parseDouble(o.toString());
        } catch (NumberFormatException e) {
            throw ApiException.badRequest("面積 (area) 必須為數字");
        }
    }
}

