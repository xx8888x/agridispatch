-- 2026-10-15 刪除帳號申請：建 account_deletion_requests 表（Google Play 刪除帳號規定）
-- 可重複執行：表已存在就略過
DECLARE
    n NUMBER;
BEGIN
    SELECT COUNT(*) INTO n FROM all_tables
     WHERE owner = SYS_CONTEXT('USERENV', 'CURRENT_SCHEMA') AND table_name = 'ACCOUNT_DELETION_REQUESTS';
    IF n = 0 THEN
        EXECUTE IMMEDIATE q'[
            CREATE TABLE account_deletion_requests (
                id                 VARCHAR2(32 CHAR)   NOT NULL,
                source             VARCHAR2(8 CHAR)    NOT NULL,
                username           VARCHAR2(64 CHAR),
                ref_id             VARCHAR2(32 CHAR),
                role               VARCHAR2(32 CHAR),
                contact_name       VARCHAR2(50 CHAR),
                contact_phone      VARCHAR2(30 CHAR),
                account_hint       VARCHAR2(64 CHAR),
                reason             VARCHAR2(500 CHAR),
                status             VARCHAR2(16 CHAR)   DEFAULT 'pending' NOT NULL,
                created_at         TIMESTAMP           DEFAULT SYSTIMESTAMP NOT NULL,
                decided_at         TIMESTAMP,
                decided_by         VARCHAR2(64 CHAR),
                decision_note      VARCHAR2(500 CHAR),
                result_summary     VARCHAR2(500 CHAR),
                CONSTRAINT pk_account_deletion_requests PRIMARY KEY (id),
                CONSTRAINT chk_adr_source CHECK (source IN ('app', 'web')),
                CONSTRAINT chk_adr_status CHECK (status IN ('pending', 'completed', 'rejected', 'withdrawn'))
            )]';
        EXECUTE IMMEDIATE 'CREATE INDEX idx_adr_username ON account_deletion_requests (username)';
        EXECUTE IMMEDIATE q'[COMMENT ON TABLE account_deletion_requests IS '刪除帳號申請：app＝已登入者申請、web＝免登入網頁申請']';
    ELSE
        DBMS_OUTPUT.PUT_LINE('migration 2026-10-15_account_deletion_requests: table already exists');
    END IF;
END;
/
