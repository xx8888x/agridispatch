package com.agridispatch.common;

import java.util.Collection;
import java.util.function.Predicate;

/**
 * 不重複序號產生器：取現有同前綴最大序號 +1，並再以 exists 檢查避免撞號
 * （取代舊版 count()+1：刪除過資料後會與既有 ID 相撞並覆寫）
 */
public final class IdGenerator {

    private IdGenerator() {}

    /**
     * @param prefix      例如 "ORD-2026-"、"W"、"TAL-"
     * @param width       序號最小位數（不足補 0）
     * @param existingIds 目前資料庫中以 prefix 開頭的 ID
     * @param exists      最終存在性檢查
     */
    public static String next(String prefix, int width, Collection<String> existingIds, Predicate<String> exists) {
        long max = 0;
        for (String id : existingIds) {
            if (id == null || !id.startsWith(prefix)) continue;
            String tail = id.substring(prefix.length());
            if (tail.isEmpty() || !tail.chars().allMatch(Character::isDigit) || tail.length() > 18) continue;
            max = Math.max(max, Long.parseLong(tail));
        }
        long seq = max + 1;
        String candidate = prefix + String.format("%0" + width + "d", seq);
        while (exists.test(candidate)) {
            seq++;
            candidate = prefix + String.format("%0" + width + "d", seq);
        }
        return candidate;
    }
}
