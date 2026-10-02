package com.agridispatch;

import com.agridispatch.common.ApiException;
import com.agridispatch.entity.Brochure;
import com.agridispatch.entity.Order;
import com.agridispatch.repository.BrochureRepository;
import com.agridispatch.service.OrderService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 工時包（文宣 DM）報價：工時、價格、定金取自 DM 本身，所以文宣標價 = 成立金額；
 * 自訂規格仍依作物與面積計價。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:agri_package_order_test;DB_CLOSE_DELAY=-1",
        "agri.demo.enabled=false",
        "agri.security.token-secret=unit-test-token-secret-0123456789abcdef",
        "agri.security.admin-password=Admin#Pass2026"
})
class PackageOrderTest {

    @Autowired OrderService orderService;
    @Autowired BrochureRepository brochureRepository;

    @Test
    void packageQuoteMatchesTheBrochurePrice() {
        // DM 標價：芒果 60 工時 18,900／溫室 120 工時 37,800／無人機 30 工時 25,200（牌價 28,800）／冷鏈 80 工時 25,200
        // 欄位：id、工時、牌價、成交價、定金、建議人數
        Object[][] expected = {
                {"dm-mango-2026", 60, 18900, 18900, 3780, 4},
                {"dm-greenhouse-2026", 120, 37800, 37800, 7560, 6},
                {"dm-drone-2026", 30, 28800, 25200, 5040, 2},
                {"dm-coldchain-2026", 80, 25200, 25200, 5040, 5},
        };
        for (Object[] e : expected) {
            Map<String, Object> q = orderService.calculatePackage((String) e[0], "core_35km", null);
            assertEquals(e[1], q.get("totalHours"), (String) e[0]);
            assertEquals(e[2], q.get("standardTotal"), (String) e[0]);
            assertEquals(e[3], q.get("actualTotal"), (String) e[0]);
            assertEquals(e[4], q.get("deposit"), (String) e[0]);
            assertEquals(e[5], q.get("suggestedWorkers"), (String) e[0]);
            assertEquals(e[0], q.get("packageId"));
            assertEquals("early_bird", q.get("plan"));
        }
    }

    @Test
    void crewSizeChangesDaysButNotThePackagePrice() {
        Map<String, Object> auto = orderService.calculatePackage("dm-mango-2026", "core_35km", null);
        Map<String, Object> crew2 = orderService.calculatePackage("dm-mango-2026", "core_35km", 2);
        assertEquals(auto.get("actualTotal"), crew2.get("actualTotal"));
        assertEquals(2, crew2.get("suggestedWorkers"));
        assertEquals(3.8, (double) crew2.get("estDays"), 0.001);   // 60 ÷ (2 × 8)
        assertThrows(ApiException.class, () -> orderService.calculatePackage("dm-mango-2026", "core_35km", 31));
    }

    @Test
    void crossRegionSurchargeIsAddedOnTop() {
        Map<String, Object> q = orderService.calculatePackage("dm-mango-2026", "cross_region", null);
        assertEquals(1200, q.get("transportSurcharge"));
        assertEquals(18900 + 1200, q.get("actualTotal"));
    }

    @Test
    void unknownOrDraftPackageIsRejected() {
        assertThrows(ApiException.class, () -> orderService.calculatePackage("dm-nope", "core_35km", null));

        Brochure custom = brochureRepository.findById("dm-mango-2026").orElseThrow();
        String old = custom.getStatus();
        custom.setStatus("draft");
        brochureRepository.save(custom);
        try {
            assertThrows(ApiException.class, () -> orderService.calculatePackage("dm-mango-2026", "core_35km", null));
        } finally {
            custom.setStatus(old);
            brochureRepository.save(custom);
        }
    }

    @Test
    void adminChangingTheBrochurePriceFlowsIntoNewOrders() {
        Brochure b = brochureRepository.findById("dm-drone-2026").orElseThrow();
        int oldPrice = b.getDiscountPrice();
        b.setDiscountPrice(23000);
        brochureRepository.save(b);
        try {
            assertEquals(23000, orderService.calculatePackage("dm-drone-2026", "core_35km", null).get("actualTotal"));
        } finally {
            b.setDiscountPrice(oldPrice);
            brochureRepository.save(b);
        }
    }

    private Order packageOrder(String packageId) {
        Order in = new Order();
        in.setFarmerName("測試農友");
        in.setPhone("0900-000-000");
        in.setFarmLocation("屏東縣枋山鄉");
        // 前端另傳的作物、面積、方案、類別一律忽略，以工時包為準
        in.setCropId("tea");
        in.setArea(99);
        in.setPlan("hourly_flex");
        in.setDispatchCategory("typhoon_emergency");
        in.setPackageId(packageId);
        return orderService.createOrder(in, "F001");
    }

    @Test
    void createdPackageOrderChargesTheBrochurePrice() {
        Order o = packageOrder("dm-drone-2026");
        assertEquals("dm-drone-2026", o.getPackageId());
        assertEquals("rice", o.getCropId());
        assertEquals("drone_pest_control", o.getDispatchCategory());
        assertEquals("early_bird", o.getPlan());
        assertEquals(30, o.getTotalHours());
        assertEquals(25200, o.getFinalAmount());
        assertEquals(5040, o.getDepositAmount());
        assertEquals(2, o.getSuggestedWorkers());
        assertEquals(OrderService.PENDING, o.getStatus());
    }

    @Test
    void changingTheSpecTurnsItIntoACustomOrder() {
        Order o = packageOrder("dm-mango-2026");
        assertEquals(18900, o.getFinalAmount());

        Order patch = new Order();
        patch.setArea(5.0);
        Order custom = orderService.updateOrder(o.getId(), patch, false);
        assertNull(custom.getPackageId());
        assertEquals(140, custom.getTotalHours());            // mango 28 小時/分地 × 5
        assertEquals(44100, custom.getFinalAmount());         // 140 × 315
    }

    @Test
    void crewChangeKeepsThePackagePrice() {
        Order o = packageOrder("dm-greenhouse-2026");
        Order patch = new Order();
        patch.setRequestedWorkers(3);
        Order changed = orderService.updateOrder(o.getId(), patch, false);
        assertEquals("dm-greenhouse-2026", changed.getPackageId());
        assertEquals(3, changed.getSuggestedWorkers());
        assertEquals(37800, changed.getFinalAmount());
    }
}
