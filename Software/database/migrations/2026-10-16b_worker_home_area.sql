-- 2026-10-16 工人「居住地」（縣市＋鄉鎮市區）：workers 補 home_area 欄位，派工媒合依與農場的距離排序出工名單
-- 可重複執行：欄位已存在就略過。值由後端 SeedService 補上（示範資料），後台工人管理可修改
DECLARE
    n NUMBER;
BEGIN
    SELECT COUNT(*) INTO n FROM all_tab_columns
     WHERE owner = SYS_CONTEXT('USERENV', 'CURRENT_SCHEMA') AND table_name = 'WORKERS' AND column_name = 'HOME_AREA';
    IF n = 0 THEN
        EXECUTE IMMEDIATE 'ALTER TABLE workers ADD (home_area VARCHAR2(40 CHAR))';
        EXECUTE IMMEDIATE q'[COMMENT ON COLUMN workers.home_area IS '居住地 (縣市+鄉鎮市區，派工媒合排序用；未登錄為 NULL)']';
        DBMS_OUTPUT.PUT_LINE('migration 2026-10-16b_worker_home_area: added');
    ELSE
        DBMS_OUTPUT.PUT_LINE('migration 2026-10-16b_worker_home_area: already applied');
    END IF;
END;
/
