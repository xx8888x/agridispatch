package com.agridispatch.service.payment;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 綠界 CheckMacValue 演算法測試（綠界公開測試用 HashKey / HashIV，非正式金鑰）
 */
class EcpayCheckMacTest {

    private static final String KEY = "pwFHCqoQZGmho4w6";
    private static final String IV = "EkRm7iFT261dpevs";

    private static Map<String, String> sample() {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("TradeDesc", "促銷方案");
        p.put("PaymentType", "aio");
        p.put("MerchantTradeDate", "2023/03/12 15:30:23");
        p.put("MerchantTradeNo", "ecpay20230312153023");
        p.put("MerchantID", "3002607");
        p.put("ReturnURL", "https://www.ecpay.com.tw/receive.php");
        p.put("ItemName", "Apple iphone 15");
        p.put("TotalAmount", "30000");
        p.put("ChoosePayment", "ALL");
        p.put("EncryptType", "1");
        return p;
    }

    @Test
    void matchesOfficialDocumentExample() {
        // 綠界技術文件「檢查碼機制」範例結果
        assertEquals("6C51C9E6888DE861FD62FB1DD17029FC742634498FD813DC43D4243B5685B840",
                EcpayCheckMac.generate(sample(), KEY, IV));
    }

    @Test
    void dotNetStyleEncoding() {
        assertEquals("a+b!*()-_.%2f%3a", EcpayCheckMac.dotNetUrlEncode("a b!*()-_./:").toLowerCase());
    }

    @Test
    void verifyAcceptsValidAndRejectsTampered() {
        Map<String, String> p = sample();
        p.put("CheckMacValue", EcpayCheckMac.generate(p, KEY, IV));
        assertTrue(EcpayCheckMac.verify(p, KEY, IV));

        Map<String, String> tampered = new LinkedHashMap<>(p);
        tampered.put("TotalAmount", "1");
        assertFalse(EcpayCheckMac.verify(tampered, KEY, IV), "金額被竄改應驗證失敗");

        Map<String, String> missing = new LinkedHashMap<>(sample());
        assertFalse(EcpayCheckMac.verify(missing, KEY, IV), "缺少 CheckMacValue 應驗證失敗");
        assertFalse(EcpayCheckMac.verify(p, KEY, "wrongIV"), "HashIV 不符應驗證失敗");
    }
}
