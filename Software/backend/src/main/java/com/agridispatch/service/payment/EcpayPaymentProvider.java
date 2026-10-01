package com.agridispatch.service.payment;

import com.agridispatch.entity.Order;
import com.agridispatch.entity.PaymentTransaction;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 綠界 ECPay 全方位金流（AIO）：產生表單參數與 CheckMacValue，由前端以 POST form 導向綠界付款頁。
 * MerchantID / HashKey / HashIV 由環境變數 ECPAY_MERCHANT_ID / ECPAY_HASH_KEY / ECPAY_HASH_IV 提供，不寫死金鑰。
 * 注意：未對真實綠界沙盒驗證。
 */
@Component
public class EcpayPaymentProvider implements PaymentProvider {

    private static final DateTimeFormatter TRADE_DATE = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss");

    @Value("${agri.payment.ecpay.merchant-id:}")
    private String merchantId;
    @Value("${agri.payment.ecpay.hash-key:}")
    private String hashKey;
    @Value("${agri.payment.ecpay.hash-iv:}")
    private String hashIv;
    @Value("${agri.payment.ecpay.action-url:https://payment-stage.ecpay.com.tw/Cashier/AioCheckOut/V5}")
    private String actionUrl;
    /** 綠界伺服器端付款結果通知網址（需可由外網連線），對應 POST /api/payments/ecpay/notify */
    @Value("${agri.payment.ecpay.return-url:}")
    private String returnUrl;
    /** 付款完成後使用者返回的前端頁面（選填） */
    @Value("${agri.payment.ecpay.client-back-url:}")
    private String clientBackUrl;

    @Override
    public String id() {
        return "ecpay";
    }

    public boolean isConfigured() {
        return notBlank(merchantId) && notBlank(hashKey) && notBlank(hashIv) && notBlank(returnUrl);
    }

    public String getMerchantId() { return merchantId; }

    @Override
    public Map<String, Object> buildCheckout(PaymentTransaction tx, Order order) {
        if (!isConfigured()) {
            throw new IllegalStateException("ECPay 尚未設定（ECPAY_MERCHANT_ID / ECPAY_HASH_KEY / ECPAY_HASH_IV / ECPAY_RETURN_URL）");
        }
        Map<String, String> p = new LinkedHashMap<>();
        p.put("MerchantID", merchantId);
        p.put("MerchantTradeNo", tx.getId());
        p.put("MerchantTradeDate", tx.getCreatedAt().format(TRADE_DATE));
        p.put("PaymentType", "aio");
        p.put("TotalAmount", String.valueOf(tx.getAmount()));
        p.put("TradeDesc", "AgriDispatch deposit");
        p.put("ItemName", "智農派工訂單定金 " + order.getId());
        p.put("ReturnURL", returnUrl);
        p.put("ChoosePayment", "ALL");
        p.put("EncryptType", "1");
        p.put("CustomField1", order.getId());
        if (notBlank(clientBackUrl)) {
            p.put("ClientBackURL", clientBackUrl);
        }
        p.put("CheckMacValue", EcpayCheckMac.generate(p, hashKey, hashIv));

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("actionUrl", actionUrl);
        m.put("method", "POST");
        m.put("params", p);
        return m;
    }

    /** 驗證綠界通知的 CheckMacValue 與 MerchantID */
    public boolean verifyNotify(Map<String, String> params) {
        if (!notBlank(hashKey) || !notBlank(hashIv)) return false;
        if (notBlank(merchantId) && !merchantId.equals(params.get("MerchantID"))) return false;
        return EcpayCheckMac.verify(params, hashKey, hashIv);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
