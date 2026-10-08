package com.agridispatch.common;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 實體以字串 "yyyy-MM-dd HH:mm:ss" 保存的時間（API 也回這個格式）↔ 資料庫 TIMESTAMP 欄位。
 * Oracle 的 orders.created_at、talents.registered_at 是 TIMESTAMP，直接以字串寫入會依 NLS_TIMESTAMP_FORMAT
 * 隱含轉換而失敗（ORA-01843／01861），讀回也會多出 ".0"。H2 舊檔案資料庫的 varchar 欄位同樣相容。
 */
@Converter
public class DateTimeStringConverter implements AttributeConverter<String, LocalDateTime> {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Override
    public LocalDateTime convertToDatabaseColumn(String value) {
        if (value == null || value.isBlank()) return null;
        String v = value.trim().replace('T', ' ');
        int dot = v.indexOf('.');
        if (dot > 0) v = v.substring(0, dot);   // 去掉毫秒
        if (v.length() == 16) v += ":00";        // yyyy-MM-dd HH:mm
        return LocalDateTime.parse(v, FMT);
    }

    @Override
    public String convertToEntityAttribute(LocalDateTime db) {
        return db == null ? null : db.format(FMT);
    }
}
