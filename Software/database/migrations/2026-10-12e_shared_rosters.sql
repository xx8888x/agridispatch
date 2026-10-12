-- 2026-10-12 社區共享班表：建 shared_rosters 表、orders 加 shared_roster_id
-- 可重複執行：表或欄位已存在就略過
DECLARE
    n NUMBER;
BEGIN
    SELECT COUNT(*) INTO n FROM all_tables
     WHERE owner = SYS_CONTEXT('USERENV', 'CURRENT_SCHEMA') AND table_name = 'SHARED_ROSTERS';
    IF n = 0 THEN
        EXECUTE IMMEDIATE q'[
            CREATE TABLE shared_rosters (
                id                 VARCHAR2(32 CHAR)   NOT NULL,
                title              VARCHAR2(100 CHAR)  NOT NULL,
                region             VARCHAR2(100 CHAR)  NOT NULL,
                description        VARCHAR2(500 CHAR),
                team_id            VARCHAR2(32 CHAR)   NOT NULL,
                crew_worker_ids    VARCHAR2(400 CHAR)  NOT NULL,
                dispatch_category  VARCHAR2(50 CHAR)   NOT NULL,
                start_date         DATE                NOT NULL,
                end_date           DATE                NOT NULL,
                min_claim_percent  NUMBER(3)           DEFAULT 60 NOT NULL,
                claim_deadline     DATE                NOT NULL,
                status             VARCHAR2(20 CHAR)   DEFAULT 'open' NOT NULL,
                cancel_reason      VARCHAR2(200 CHAR),
                created_at         TIMESTAMP           DEFAULT SYSTIMESTAMP NOT NULL,
                CONSTRAINT pk_shared_rosters PRIMARY KEY (id),
                CONSTRAINT fk_shared_rosters_team FOREIGN KEY (team_id) REFERENCES dispatch_teams(id),
                CONSTRAINT chk_shared_rosters_period CHECK (end_date >= start_date AND claim_deadline < start_date),
                CONSTRAINT chk_shared_rosters_pct CHECK (min_claim_percent BETWEEN 1 AND 100),
                CONSTRAINT chk_shared_rosters_status CHECK (status IN ('open', 'confirmed', 'cancelled', 'completed'))
            )]';
        DBMS_OUTPUT.PUT_LINE('migration 2026-10-12e_shared_rosters: table created');
    ELSE
        DBMS_OUTPUT.PUT_LINE('migration 2026-10-12e_shared_rosters: table already applied');
    END IF;
    SELECT COUNT(*) INTO n FROM all_tab_columns
     WHERE owner = SYS_CONTEXT('USERENV', 'CURRENT_SCHEMA') AND table_name = 'ORDERS' AND column_name = 'SHARED_ROSTER_ID';
    IF n = 0 THEN
        EXECUTE IMMEDIATE 'ALTER TABLE orders ADD (shared_roster_id VARCHAR2(32 CHAR))';
        DBMS_OUTPUT.PUT_LINE('migration 2026-10-12e_shared_rosters: column created');
    ELSE
        DBMS_OUTPUT.PUT_LINE('migration 2026-10-12e_shared_rosters: column already applied');
    END IF;
END;
/
