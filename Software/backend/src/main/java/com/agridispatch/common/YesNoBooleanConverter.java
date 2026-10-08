package com.agridispatch.common;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * boolean ↔ 'Y'／'N'：Oracle schema 的旗標欄位一律是 CHAR(1) CHECK IN ('Y','N')（01_agridispatch_oracle_schema.sql）。
 * Hibernate 預設把 boolean 寫成 1/0（或 Oracle 23 的 BOOLEAN），會違反 CHECK。
 * 讀取時也接受舊 H2 檔案資料庫 BOOLEAN 欄位讀出的 "TRUE"／"FALSE"（H2 寫入 'Y' 會自動轉成 TRUE）。
 */
@Converter
public class YesNoBooleanConverter implements AttributeConverter<Boolean, String> {

    @Override
    public String convertToDatabaseColumn(Boolean value) {
        return Boolean.TRUE.equals(value) ? "Y" : "N";
    }

    @Override
    public Boolean convertToEntityAttribute(String db) {
        if (db == null) return false;
        String v = db.trim().toUpperCase();
        return v.equals("Y") || v.equals("TRUE") || v.equals("T") || v.equals("1");
    }
}
