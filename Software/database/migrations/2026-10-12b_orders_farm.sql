-- 2026-10-12 訂單記錄下單時選的農場：orders 加 farm_id、farm_name（農場名稱快照）
-- 可重複執行：欄位已存在就略過。農場可刪除，farm_id 不設外鍵
DECLARE
    n NUMBER;
BEGIN
    SELECT COUNT(*) INTO n FROM all_tab_columns
     WHERE owner = SYS_CONTEXT('USERENV', 'CURRENT_SCHEMA') AND table_name = 'ORDERS' AND column_name = 'FARM_ID';
    IF n = 0 THEN
        EXECUTE IMMEDIATE 'ALTER TABLE orders ADD (farm_id VARCHAR2(32 CHAR), farm_name VARCHAR2(100 CHAR))';
        DBMS_OUTPUT.PUT_LINE('migration 2026-10-12b_orders_farm: created');
    ELSE
        DBMS_OUTPUT.PUT_LINE('migration 2026-10-12b_orders_farm: already applied');
    END IF;
END;
/
