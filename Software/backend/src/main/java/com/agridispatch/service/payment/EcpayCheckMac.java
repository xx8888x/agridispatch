package com.agridispatch.service.payment;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.TreeMap;

/**
 * 綠界 ECPay AIO CheckMacValue（SHA256，EncryptType=1）
 * 演算法（依綠界官方說明）：
 *   1. 移除 CheckMacValue，其餘參數依 key 字母排序（不分大小寫）
 *   2. 以 key=value 用 &amp; 串接，前加 HashKey=...&amp;，後加 &amp;HashIV=...
 *   3. 整串做 .NET 風格 URL encode（HttpUtility.UrlEncode）後轉小寫
 *   4. SHA256 後轉大寫十六進位
 * 注意：本實作未對真實綠界沙盒驗證，上線前請以綠界提供的測試帳號實測。
 */
public final class EcpayCheckMac {

    private EcpayCheckMac() {}

    public static String generate(Map<String, String> params, String hashKey, String hashIv) {
        TreeMap<String, String> sorted = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (Map.Entry<String, String> e : params.entrySet()) {
            if ("CheckMacValue".equalsIgnoreCase(e.getKey())) continue;
            sorted.put(e.getKey(), e.getValue() == null ? "" : e.getValue());
        }
        StringBuilder sb = new StringBuilder("HashKey=").append(hashKey);
        for (Map.Entry<String, String> e : sorted.entrySet()) {
            sb.append('&').append(e.getKey()).append('=').append(e.getValue());
        }
        sb.append("&HashIV=").append(hashIv);

        String encoded = dotNetUrlEncode(sb.toString()).toLowerCase();
        return sha256Hex(encoded).toUpperCase();
    }

    /** 以 constant-time 比對驗證回傳的 CheckMacValue */
    public static boolean verify(Map<String, String> params, String hashKey, String hashIv) {
        String received = params.get("CheckMacValue");
        if (received == null || received.isBlank()) return false;
        String expected = generate(params, hashKey, hashIv);
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                received.trim().toUpperCase().getBytes(StandardCharsets.US_ASCII));
    }

    /**
     * .NET HttpUtility.UrlEncode 相容：Java URLEncoder 會把 ! ( ) 編碼，.NET 不會；
     * 其餘（空白 → +、- _ . * 不編碼）兩者一致。
     */
    static String dotNetUrlEncode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8)
                .replace("%21", "!")
                .replace("%28", "(")
                .replace("%29", ")");
    }

    private static String sha256Hex(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(d.length * 2);
            for (byte b : d) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
