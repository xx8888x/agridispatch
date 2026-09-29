package com.agridispatch.service.payment;

import com.agridispatch.entity.Order;
import com.agridispatch.entity.PaymentTransaction;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 模擬金流：建立待付款交易；僅 demo 模式可呼叫 POST /api/payments/{txId}/mock-confirm 完成付款
 */
@Component
public class MockPaymentProvider implements PaymentProvider {

    @Override
    public String id() {
        return "mock";
    }

    @Override
    public Map<String, Object> buildCheckout(PaymentTransaction tx, Order order) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("confirmUrl", "/api/payments/" + tx.getId() + "/mock-confirm");
        m.put("note", "模擬金流：demo 模式下呼叫 confirmUrl 完成付款，不會實際扣款");
        return m;
    }
}
