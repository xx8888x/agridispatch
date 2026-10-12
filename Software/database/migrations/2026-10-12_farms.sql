-- 2026-10-12 農場主可登錄多個農場（預約派工時選一個）：補建 farms 表
-- 可重複執行：表已存在就略過。DEV 由 deploy/dev/server-deploy.sh 每次部署依檔名順序執行 migrations/*.sql
DECLARE
    n NUMBER;
BEGIN
    SELECT COUNT(*) INTO n FROM all_tables
     WHERE owner = SYS_CONTEXT('USERENV', 'CURRENT_SCHEMA') AND table_name = 'FARMS';
    IF n = 0 THEN
        EXECUTE IMMEDIATE q'[
            CREATE TABLE farms (
                id                 VARCHAR2(32 CHAR)   NOT NULL,
                farmer_id          VARCHAR2(32 CHAR)   NOT NULL,
                name               VARCHAR2(100 CHAR)  NOT NULL,
                contact_name       VARCHAR2(50 CHAR)   NOT NULL,
                phone              VARCHAR2(30 CHAR)   NOT NULL,
                address            VARCHAR2(255 CHAR)  NOT NULL,
                farm_type          VARCHAR2(50 CHAR)   DEFAULT '個人農戶' NOT NULL,
                created_at         TIMESTAMP      DEFAULT SYSTIMESTAMP NOT NULL,
                CONSTRAINT pk_farms PRIMARY KEY (id)
            )]';
        EXECUTE IMMEDIATE 'CREATE INDEX idx_farms_farmer ON farms (farmer_id, created_at)';
        DBMS_OUTPUT.PUT_LINE('migration 2026-10-12_farms: created');
    ELSE
        DBMS_OUTPUT.PUT_LINE('migration 2026-10-12_farms: already applied');
    END IF;
END;
/
