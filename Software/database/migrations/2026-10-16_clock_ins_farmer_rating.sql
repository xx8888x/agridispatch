-- 2026-10-16 農場主逐人評核：clock_ins 加 farmer_rating、farmer_rated_by、farmer_rated_at
-- 可重複執行：欄位已存在就略過。技能標籤採計時領班評核優先，無領班評核才用農場主評核
DECLARE
    n NUMBER;
BEGIN
    SELECT COUNT(*) INTO n FROM all_tab_columns
     WHERE owner = SYS_CONTEXT('USERENV', 'CURRENT_SCHEMA') AND table_name = 'CLOCK_INS' AND column_name = 'FARMER_RATING';
    IF n = 0 THEN
        EXECUTE IMMEDIATE 'ALTER TABLE clock_ins ADD (farmer_rating VARCHAR2(20 CHAR), farmer_rated_by VARCHAR2(32 CHAR), farmer_rated_at TIMESTAMP)';
        EXECUTE IMMEDIATE 'ALTER TABLE clock_ins ADD CONSTRAINT chk_clock_ins_farmer_rating CHECK (farmer_rating IS NULL OR farmer_rating IN (''excellent'', ''ok'', ''needs_improvement''))';
        DBMS_OUTPUT.PUT_LINE('migration 2026-10-16_clock_ins_farmer_rating: created');
    ELSE
        DBMS_OUTPUT.PUT_LINE('migration 2026-10-16_clock_ins_farmer_rating: already applied');
    END IF;
END;
/
