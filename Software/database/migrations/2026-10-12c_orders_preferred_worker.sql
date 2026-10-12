-- 2026-10-12 指名回頭工人：orders 加 preferred_worker_id、preferred_worker_name（姓名快照）
-- 可重複執行：欄位已存在就略過。工人可刪除，preferred_worker_id 不設外鍵
DECLARE
    n NUMBER;
BEGIN
    SELECT COUNT(*) INTO n FROM all_tab_columns
     WHERE owner = SYS_CONTEXT('USERENV', 'CURRENT_SCHEMA') AND table_name = 'ORDERS' AND column_name = 'PREFERRED_WORKER_ID';
    IF n = 0 THEN
        EXECUTE IMMEDIATE 'ALTER TABLE orders ADD (preferred_worker_id VARCHAR2(32 CHAR), preferred_worker_name VARCHAR2(100 CHAR))';
        DBMS_OUTPUT.PUT_LINE('migration 2026-10-12c_orders_preferred_worker: created');
    ELSE
        DBMS_OUTPUT.PUT_LINE('migration 2026-10-12c_orders_preferred_worker: already applied');
    END IF;
END;
/
