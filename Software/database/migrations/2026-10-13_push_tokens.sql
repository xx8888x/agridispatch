-- 2026-10-13 App 推播：建 push_tokens 表（Android FCM token 與登入者對應）
-- 可重複執行：表已存在就略過
DECLARE
    n NUMBER;
BEGIN
    SELECT COUNT(*) INTO n FROM all_tables
     WHERE owner = SYS_CONTEXT('USERENV', 'CURRENT_SCHEMA') AND table_name = 'PUSH_TOKENS';
    IF n = 0 THEN
        EXECUTE IMMEDIATE q'[
            CREATE TABLE push_tokens (
                id                 VARCHAR2(64 CHAR)   NOT NULL,
                token              VARCHAR2(1024 CHAR) NOT NULL,
                username           VARCHAR2(64 CHAR)   NOT NULL,
                ref_id             VARCHAR2(32 CHAR),
                role               VARCHAR2(32 CHAR),
                platform           VARCHAR2(16 CHAR)   DEFAULT 'android' NOT NULL,
                updated_at         TIMESTAMP           DEFAULT SYSTIMESTAMP NOT NULL,
                CONSTRAINT pk_push_tokens PRIMARY KEY (id)
            )]';
        EXECUTE IMMEDIATE 'CREATE INDEX idx_push_tokens_ref ON push_tokens (ref_id)';
        EXECUTE IMMEDIATE 'CREATE INDEX idx_push_tokens_role ON push_tokens (role)';
        EXECUTE IMMEDIATE q'[COMMENT ON TABLE push_tokens IS 'App 推播權杖：Android FCM token 與登入者對應']';
    END IF;
END;
/
