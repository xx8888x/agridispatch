package com.agridispatch.security;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;

import java.io.IOException;

/**
 * 個資遮罩工具（規格第 6 節）：前 2 碼 + **** + 末 2 碼，例：AD****51
 */
public final class Masking {

    private Masking() {}

    public static String maskId(String value) {
        if (value == null) return null;
        String v = value.trim();
        if (v.isEmpty()) return v;
        if (v.length() <= 4) return "****";
        return v.substring(0, 2) + "****" + v.substring(v.length() - 2);
    }

    /** 判斷字串是否為遮罩後的值（前端回傳遮罩值時不可覆寫真實資料） */
    public static boolean isMasked(String value) {
        return value != null && value.contains("****");
    }

    /**
     * Jackson 序列化器：Worker 的 arcNumber / passportNumber 一律遮罩輸出。
     * 只有 admin_tech 呼叫 GET /api/workers/{id} 時由 Controller 明確填回完整值。
     */
    public static class MaskedIdSerializer extends JsonSerializer<String> {
        @Override
        public void serialize(String value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
            gen.writeString(maskId(value));
        }
    }
}
