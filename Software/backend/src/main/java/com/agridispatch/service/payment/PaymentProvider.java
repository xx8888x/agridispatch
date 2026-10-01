package com.agridispatch.service.payment;

import com.agridispatch.entity.Order;
import com.agridispatch.entity.PaymentTransaction;

import java.util.Map;

/**
 * 金流供應者抽象：agri.payment.provider = mock | ecpay
 */
public interface PaymentProvider {

    /** 供應者代號（mock / ecpay） */
    String id();

    /**
     * 建立待付款交易後，產生前端下一步所需資訊
     * （mock：確認網址；ecpay：AIO 表單參數與 CheckMacValue）
     */
    Map<String, Object> buildCheckout(PaymentTransaction tx, Order order);
}
