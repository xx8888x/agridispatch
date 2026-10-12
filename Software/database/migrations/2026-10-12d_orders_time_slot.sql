-- 2026-10-12 半日時段：orders 加 time_slot（full_day／morning／afternoon；NULL 視同整天，舊訂單不需回填）
-- 可重複執行：欄位已存在就略過
DECLARE
    n NUMBER;
BEGIN
    SELECT COUNT(*) INTO n FROM all_tab_columns
     WHERE owner = SYS_CONTEXT('USERENV', 'CURRENT_SCHEMA') AND table_name = 'ORDERS' AND column_name = 'TIME_SLOT';
    IF n = 0 THEN
        EXECUTE IMMEDIATE 'ALTER TABLE orders ADD (time_slot VARCHAR2(16 CHAR))';
        DBMS_OUTPUT.PUT_LINE('migration 2026-10-12d_orders_time_slot: created');
    ELSE
        DBMS_OUTPUT.PUT_LINE('migration 2026-10-12d_orders_time_slot: already applied');
    END IF;
END;
/
