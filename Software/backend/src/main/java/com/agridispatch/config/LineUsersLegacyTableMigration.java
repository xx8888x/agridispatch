package com.agridispatch.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.orm.jpa.EntityManagerFactoryDependsOnPostProcessor;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * line_users 舊表遷移（2026-10-02，LINE 雙帳號 P2）
 *
 * 2026-09-29 建立的 line_users 為保留實體、程式未讀寫，欄位全 NOT NULL 且 line_user_id 單欄唯一，
 * 與雙帳號新結構（多一個 channel 欄、未綁定時 username 為空）衝突；Hibernate ddl-auto=update 不會放寬既有約束。
 * 因此在 Hibernate 建表「之前」檢查：若 line_users 存在、沒有 channel 欄、且沒有任何資料，就刪掉讓 Hibernate 依新結構重建。
 * 有資料時不動，只寫錯誤 log，需人工處理。
 */
@Component("lineUsersLegacyTableMigration")
public class LineUsersLegacyTableMigration implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(LineUsersLegacyTableMigration.class);

    private final DataSource dataSource;

    public LineUsersLegacyTableMigration(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void afterPropertiesSet() {
        try (Connection conn = dataSource.getConnection()) {
            String schema = conn.getSchema(); // 只看目前 schema，避免誤判其他 schema 的同名表
            String table = findTable(conn.getMetaData(), schema, "LINE_USERS", "line_users");
            if (table == null) return; // 新資料庫，交給 Hibernate 建表
            if (hasColumn(conn.getMetaData(), schema, table, "CHANNEL", "channel")) return; // 已是新結構

            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + table)) {
                long rows = rs.next() ? rs.getLong(1) : 0;
                if (rows > 0) {
                    log.error("[LINE] line_users 為舊結構且有 {} 筆資料，未自動遷移；請手動備份後刪表或改寫為新結構（見 05_LINE官方帳號拆分規格.md 6.5）", rows);
                    return;
                }
            }
            try (Statement st = conn.createStatement()) {
                st.execute("DROP TABLE " + table);
            }
            log.info("[LINE] 已刪除舊結構的空表 line_users，將由 Hibernate 依雙帳號新結構重建");
        } catch (Exception e) {
            log.warn("[LINE] 檢查 line_users 舊結構失敗：{}", e.getMessage());
        }
    }

    private static String findTable(DatabaseMetaData md, String schema, String... names) throws Exception {
        for (String n : names) {
            try (ResultSet rs = md.getTables(null, schema, n, new String[]{"TABLE"})) {
                if (rs.next()) return rs.getString("TABLE_NAME");
            }
        }
        return null;
    }

    private static boolean hasColumn(DatabaseMetaData md, String schema, String table, String... names) throws Exception {
        for (String n : names) {
            try (ResultSet rs = md.getColumns(null, schema, table, n)) {
                if (rs.next()) return true;
            }
        }
        return false;
    }

    /** 讓 EntityManagerFactory（Hibernate 建表）在本遷移之後才初始化 */
    @Configuration
    static class EntityManagerFactoryDependsOnLineUsersMigration extends EntityManagerFactoryDependsOnPostProcessor {
        EntityManagerFactoryDependsOnLineUsersMigration() {
            super("lineUsersLegacyTableMigration");
        }
    }
}
