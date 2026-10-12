-- 2026-10-12 本國工人「工作型態」（正職／兼職／中高齡／臨時打工／學生打工）：workers 補 employment_type 欄位
-- 可重複執行：欄位已存在就略過。值由後端 SeedService 補上（示範資料），後台工人管理可修改
DECLARE
    n NUMBER;
BEGIN
    SELECT COUNT(*) INTO n FROM all_tab_columns
     WHERE owner = SYS_CONTEXT('USERENV', 'CURRENT_SCHEMA') AND table_name = 'WORKERS' AND column_name = 'EMPLOYMENT_TYPE';
    IF n = 0 THEN
        EXECUTE IMMEDIATE 'ALTER TABLE workers ADD (employment_type VARCHAR2(16 CHAR))';
        EXECUTE IMMEDIATE q'[COMMENT ON COLUMN workers.employment_type IS '工作型態 (full_time 正職, part_time 兼職, senior 中高齡, casual 臨時打工, student 學生打工；外籍為 NULL)']';
        DBMS_OUTPUT.PUT_LINE('migration 2026-10-12_worker_employment_type: added');
    ELSE
        DBMS_OUTPUT.PUT_LINE('migration 2026-10-12_worker_employment_type: already applied');
    END IF;
END;
/
