package com.agridispatch.controller;

import com.agridispatch.entity.Order;
import com.agridispatch.service.OrderService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/orders")
public class OrderController {
    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @GetMapping
    public ResponseEntity<?> getAllOrders() {
        return ResponseEntity.ok(Map.of("success", true, "data", orderService.getAllOrders()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getOrderById(@PathVariable String id) {
        return orderService.getOrderById(id)
                .map(order -> ResponseEntity.ok(Map.of("success", true, "data", order)))
                .orElse(ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("success", false, "error", "找不到該訂單編號: " + id)));
    }

    @GetMapping("/categories")
    public ResponseEntity<?> getDispatchCategories() {
        return ResponseEntity.ok(Map.of("success", true, "data", orderService.getDispatchCategories()));
    }

    @PostMapping("/calculate")
    public ResponseEntity<?> calculateOrder(@RequestBody Map<String, Object> req) {
        String cropId = req.getOrDefault("cropId", "taoyuan_veg").toString();
        double area = req.containsKey("area") ? Double.parseDouble(req.get("area").toString()) : 5.0;
        String plan = req.getOrDefault("plan", "early_bird").toString();
        String zone = req.getOrDefault("zone", "core_35km").toString();
        String dispatchCategory = req.getOrDefault("dispatchCategory", "harvest_grading").toString();

        Map<String, Object> result = orderService.calculateOrder(cropId, area, plan, zone, dispatchCategory);
        return ResponseEntity.ok(Map.of("success", true, "data", result));
    }

    @PostMapping
    public ResponseEntity<?> createOrder(@RequestBody Order order) {
        Order created = orderService.createOrder(order);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Map.of("success", true, "data", created, "message", "成功建立派工訂單"));
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> updateOrder(@PathVariable String id, @RequestBody Order order) {
        try {
            Order updated = orderService.updateOrder(id, order);
            return ResponseEntity.ok(Map.of("success", true, "data", updated, "message", "成功更新訂單資料"));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteOrder(@PathVariable String id) {
        orderService.deleteOrder(id);
        return ResponseEntity.ok(Map.of("success", true, "message", "成功取消/刪除訂單: " + id));
    }
}
