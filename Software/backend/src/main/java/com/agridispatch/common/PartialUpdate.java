package com.agridispatch.common;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 管理端 PUT 的「部分更新」：只套用請求中有帶且非 null 的欄位，沒帶的欄位維持原值（規格 03 第 5 節）。
 * 以 Jackson 將 body 合併到既有實體上，基本型別（int / boolean）沒帶時同樣不會被清成 0 / false。
 */
public final class PartialUpdate {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private PartialUpdate() {}

    /**
     * 將 body 中非 null 的欄位合併到 target（就地修改並回傳 target）。
     * @param ignored 不接受由 body 修改的欄位（例：主鍵 id、系統產生的時間）
     */
    public static <T> T apply(T target, Map<String, Object> body, Set<String> ignored) {
        if (body == null) return target;
        Map<String, Object> present = new LinkedHashMap<>();
        body.forEach((k, v) -> {
            if (k != null && v != null && !ignored.contains(k)) present.put(k, v);
        });
        if (present.isEmpty()) return target;
        try {
            return MAPPER.updateValue(target, present);
        } catch (Exception e) {
            throw ApiException.badRequest("欄位格式錯誤");
        }
    }

    /** 必填欄位：沒帶或為 null 視為不變更；明確傳空字串（或只有空白）回 400 */
    public static void rejectBlank(Map<String, Object> body, String field, String label) {
        if (body == null) return;
        Object v = body.get(field);
        if (v != null && v.toString().isBlank()) {
            throw ApiException.badRequest(label + " (" + field + ") 不可為空");
        }
    }
}
