package com.agridispatch;

import com.agridispatch.common.ApiException;
import com.agridispatch.entity.Order;
import com.agridispatch.service.OrderService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 指定每日工人數（requestedWorkers）：只影響編制人數與預估天數，不影響金額；
 * 範圍 1～30；訂單建立後可改（已排定的訂單只有 admin 能改），改回 0 = 由系統估算。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:agri_requested_workers_test;DB_CLOSE_DELAY=-1",
        "agri.demo.enabled=false",
        "agri.security.token-secret=unit-test-token-secret-0123456789abcdef",
        "agri.security.admin-password=Admin#Pass2026"
})
class RequestedWorkersTest {

    @Autowired OrderService orderService;

    @Test
    void crewSizeChangesWorkersAndDaysButNotPrice() {
        Map<String, Object> auto = orderService.calculateOrder("mango", 6.0, "early_bird", "core_35km", "harvest_grading");
        Map<String, Object> crew3 = orderService.calculateOrder("mango", 6.0, "early_bird", "core_35km", "harvest_grading", 3);

        assertNull(auto.get("requestedWorkers"));
        assertEquals(3, crew3.get("requestedWorkers"));
        assertEquals(3, crew3.get("suggestedWorkers"));
        // 作業量與金額不變
        assertEquals(auto.get("totalHours"), crew3.get("totalHours"));
        assertEquals(auto.get("actualTotal"), crew3.get("actualTotal"));
        assertEquals(auto.get("deposit"), crew3.get("deposit"));
        // 168 小時 ÷ (3 人 × 8 小時) = 7.0 天
        assertEquals(7.0, (double) crew3.get("estDays"), 0.001);
        assertTrue((double) crew3.get("estDays") > (double) auto.get("estDays"));
    }

    @Test
    void crewSizeMustBeBetweenOneAndThirty() {
        assertThrows(ApiException.class, () -> orderService.calculateOrder("mango", 6.0, "early_bird", "core_35km", "harvest_grading", 0));
        assertThrows(ApiException.class, () -> orderService.calculateOrder("mango", 6.0, "early_bird", "core_35km", "harvest_grading", 31));
        assertEquals(1, orderService.calculateOrder("mango", 6.0, "early_bird", "core_35km", "harvest_grading", 1).get("suggestedWorkers"));
        assertEquals(30, orderService.calculateOrder("mango", 6.0, "early_bird", "core_35km", "harvest_grading", 30).get("suggestedWorkers"));
    }

    private Order newOrder(Integer workers) {
        Order in = new Order();
        in.setFarmerName("測試農友");
        in.setPhone("0900-000-000");
        in.setFarmLocation("屏東縣枋山鄉");
        in.setCropId("mango");
        in.setArea(6.0);
        in.setPlan("early_bird");
        in.setDispatchCategory("harvest_grading");
        in.setRequestedWorkers(workers);
        return orderService.createOrder(in, "F001");
    }

    @Test
    void createdOrderKeepsRequestedCrewAndSurvivesRepricing() {
        Order created = newOrder(3);
        assertEquals(3, created.getRequestedWorkers());
        assertEquals(3, created.getSuggestedWorkers());
        assertEquals(52920, created.getFinalAmount());

        // 面積變更會重算金額，但指定的人數要保留
        Order patch = new Order();
        patch.setArea(8.0);
        Order repriced = orderService.updateOrder(created.getId(), patch, false);
        assertEquals(3, repriced.getRequestedWorkers());
        assertEquals(3, repriced.getSuggestedWorkers());
        assertEquals(70560, repriced.getFinalAmount());   // 224 小時 × 315

        // 不指定人數的訂單仍由系統依面積估算
        Order auto = newOrder(null);
        assertNull(auto.getRequestedWorkers());
        assertEquals(6, auto.getSuggestedWorkers());
    }

    @Test
    void crewCanBeChangedWhilePendingAndClearedWithZero() {
        Order created = newOrder(null);
        String id = created.getId();
        int autoWorkers = created.getSuggestedWorkers();
        int amount = created.getFinalAmount();

        Order patch = new Order();
        patch.setRequestedWorkers(2);
        Order changed = orderService.updateOrder(id, patch, false);
        assertEquals(2, changed.getSuggestedWorkers());
        assertEquals(2, changed.getRequestedWorkers());
        assertEquals(amount, changed.getFinalAmount());   // 金額不動

        Order clear = new Order();
        clear.setRequestedWorkers(0);
        Order cleared = orderService.updateOrder(id, clear, false);
        assertNull(cleared.getRequestedWorkers());
        assertEquals(autoWorkers, cleared.getSuggestedWorkers());

        Order bad = new Order();
        bad.setRequestedWorkers(99);
        assertThrows(ApiException.class, () -> orderService.updateOrder(id, bad, false));
    }

    @Test
    void confirmedOrderCrewOnlyAdminCanChange() {
        // 彈性計時免定金，建立時就是已排定（confirmed）
        Order in = new Order();
        in.setFarmerName("測試農友");
        in.setPhone("0900-000-000");
        in.setFarmLocation("屏東縣枋山鄉");
        in.setCropId("mango");
        in.setArea(6.0);
        in.setPlan("hourly_flex");
        Order created = orderService.createOrder(in, "F001");
        assertEquals(OrderService.CONFIRMED, created.getStatus());

        Order patch = new Order();
        patch.setRequestedWorkers(4);
        assertThrows(ApiException.class, () -> orderService.updateOrder(created.getId(), patch, false));
        assertEquals(4, orderService.updateOrder(created.getId(), patch, true).getSuggestedWorkers());
    }
}
