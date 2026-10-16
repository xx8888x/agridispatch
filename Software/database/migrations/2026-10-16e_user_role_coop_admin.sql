-- 2026-10-16 users.role 檢查條件補上合作社管理員 coop_admin（後端種子與登入已有此角色，舊資料庫的 CHK_USER_ROLE 沒有，啟動寫入 coop_admin 帳號會 ORA-02290）
-- 可重複執行：條件已含 coop_admin 就略過。
DECLARE
    n NUMBER;
BEGIN
    SELECT COUNT(*) INTO n FROM user_constraints
     WHERE constraint_name = 'CHK_USER_ROLE' AND search_condition_vc LIKE '%coop_admin%';
    IF n = 0 THEN
        SELECT COUNT(*) INTO n FROM user_constraints WHERE constraint_name = 'CHK_USER_ROLE';
        IF n > 0 THEN
            EXECUTE IMMEDIATE 'ALTER TABLE users DROP CONSTRAINT chk_user_role';
        END IF;
        EXECUTE IMMEDIATE q'[ALTER TABLE users ADD CONSTRAINT chk_user_role CHECK (role IN ('admin_tech', 'coop_admin', 'farmer', 'worker_foreign', 'worker_local'))]';
        DBMS_OUTPUT.PUT_LINE('migration 2026-10-16e_user_role_coop_admin: updated');
    ELSE
        DBMS_OUTPUT.PUT_LINE('migration 2026-10-16e_user_role_coop_admin: already applied');
    END IF;
END;
/
