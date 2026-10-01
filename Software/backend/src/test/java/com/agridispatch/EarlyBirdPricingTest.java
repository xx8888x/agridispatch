package com.agridispatch;

import com.agridispatch.entity.Brochure;
import com.agridispatch.entity.Order;
import com.agridispatch.repository.BrochureRepository;
import com.agridispatch.repository.OrderRepository;
import com.agridispatch.service.OrderService;
import com.agridispatch.service.SeedService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 早鳥工時包 315 元/hr 不打折：試算與種子一致，且舊版 9 折種子資料啟動時會被遷移
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:agri_early_bird_test;DB_CLOSE_DELAY=-1",
        "agri.demo.enabled=false",
        "agri.security.token-secret=unit-test-token-secret-0123456789abcdef",
        "agri.security.admin-password=Admin#Pass2026"
})
class EarlyBirdPricingTest {

    @Autowired OrderService orderService;
    @Autowired SeedService seedService;
    @Autowired OrderRepository orderRepository;
    @Autowired BrochureRepository brochureRepository;

    @Test
    void earlyBirdIs315PerHourWithoutDiscount() {
        Map<String, Object> r = orderService.calculateOrder("mango", 6.0, "early_bird", "core_35km");
        assertEquals(315, r.get("unitRate"));
        assertEquals(168, r.get("totalHours"));
        assertEquals(52920, r.get("actualTotal"));
        assertEquals(52920, r.get("standardTotal"));
        assertEquals(10584, r.get("deposit"));
        assertEquals(0, r.get("savings"));

        // 防颱急單仍 360 元/hr、其他方案不變
        assertEquals(360, orderService.calculateOrder("mango", 6.0, "early_bird", "core_35km", "typhoon_emergency").get("unitRate"));
        assertEquals(310, orderService.calculateOrder("mango", 6.0, "hourly_flex", "core_35km").get("unitRate"));
        assertEquals(47880, orderService.calculateOrder("mango", 6.0, "turnkey", "core_35km").get("actualTotal"));

        // 種子訂單金額與試算一致
        Order seeded = orderRepository.findById("ORD-2026-013").orElseThrow();
        assertEquals(52920, seeded.getFinalAmount());
        assertEquals(10584, seeded.getDepositAmount());
    }

    @Test
    void legacyNinetyPercentSeedIsMigrated() {
        Order o = orderRepository.findById("ORD-2026-013").orElseThrow();
        o.setPlanName("【Tier 1】產季早鳥工時包 (9折保證出工)");
        o.setUnitRate(300);
        o.setStandardAmount(50400);
        o.setFinalAmount(45360);
        o.setDepositAmount(9072);
        orderRepository.save(o);

        // 使用者改過金額的訂單不動
        Order edited = orderRepository.findById("ORD-2026-014").orElseThrow();
        edited.setPlanName("【Tier 1】產季早鳥工時包 (9折保證出工)");
        edited.setFinalAmount(12345);
        orderRepository.save(edited);

        Brochure b = brochureRepository.findById("dm-mango-2026").orElseThrow();
        b.setOriginalPrice(18000);
        b.setDiscountPrice(16200);
        b.setDepositAmount(3240);
        brochureRepository.save(b);

        seedService.seedAll(false);

        Order m = orderRepository.findById("ORD-2026-013").orElseThrow();
        assertEquals("【Tier 1】產季早鳥工時包 (鎖定名額・優先排班)", m.getPlanName());
        assertEquals(315, m.getUnitRate());
        assertEquals(52920, m.getFinalAmount());
        assertEquals(10584, m.getDepositAmount());
        assertEquals(12345, orderRepository.findById("ORD-2026-014").orElseThrow().getFinalAmount());

        Brochure mb = brochureRepository.findById("dm-mango-2026").orElseThrow();
        assertEquals(18900, mb.getOriginalPrice());
        assertEquals(18900, mb.getDiscountPrice());
        assertEquals(3780, mb.getDepositAmount());
    }
}
