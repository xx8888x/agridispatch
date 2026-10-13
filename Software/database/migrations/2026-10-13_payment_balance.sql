-- 2026-10-13 尾款收款登錄：payment_transactions
--   加 kind（deposit 定金／balance 尾款；NULL 視同定金，舊資料不需回填）與 note（尾款登錄說明、作廢原因）
--   status 多一種 VOID（作廢的尾款登錄）、provider 多一種 offline（合作社另行收款，由 admin 登錄）
-- 可重複執行：欄位已存在就略過；CHECK 限制每次先刪再建（內容固定）
DECLARE
    n NUMBER;
    PROCEDURE drop_check(p_name VARCHAR2) IS
    BEGIN
        EXECUTE IMMEDIATE 'ALTER TABLE payment_transactions DROP CONSTRAINT ' || p_name;
    EXCEPTION WHEN OTHERS THEN
        IF SQLCODE != -2443 THEN RAISE; END IF;   -- ORA-02443：限制不存在
    END;
BEGIN
    SELECT COUNT(*) INTO n FROM all_tab_columns
     WHERE owner = SYS_CONTEXT('USERENV', 'CURRENT_SCHEMA') AND table_name = 'PAYMENT_TRANSACTIONS' AND column_name = 'KIND';
    IF n = 0 THEN
        EXECUTE IMMEDIATE 'ALTER TABLE payment_transactions ADD (kind VARCHAR2(16 CHAR))';
        DBMS_OUTPUT.PUT_LINE('migration 2026-10-13_payment_balance: kind created');
    ELSE
        DBMS_OUTPUT.PUT_LINE('migration 2026-10-13_payment_balance: kind already applied');
    END IF;

    SELECT COUNT(*) INTO n FROM all_tab_columns
     WHERE owner = SYS_CONTEXT('USERENV', 'CURRENT_SCHEMA') AND table_name = 'PAYMENT_TRANSACTIONS' AND column_name = 'NOTE';
    IF n = 0 THEN
        EXECUTE IMMEDIATE 'ALTER TABLE payment_transactions ADD (note VARCHAR2(255 CHAR))';
        DBMS_OUTPUT.PUT_LINE('migration 2026-10-13_payment_balance: note created');
    ELSE
        DBMS_OUTPUT.PUT_LINE('migration 2026-10-13_payment_balance: note already applied');
    END IF;

    drop_check('chk_payment_status');
    drop_check('chk_payment_provider');
    drop_check('chk_payment_kind');
    EXECUTE IMMEDIATE q'[ALTER TABLE payment_transactions ADD CONSTRAINT chk_payment_status CHECK (status IN ('PENDING', 'PAID', 'FAILED', 'VOID'))]';
    EXECUTE IMMEDIATE q'[ALTER TABLE payment_transactions ADD CONSTRAINT chk_payment_provider CHECK (provider IN ('mock', 'ecpay', 'offline'))]';
    EXECUTE IMMEDIATE q'[ALTER TABLE payment_transactions ADD CONSTRAINT chk_payment_kind CHECK (kind IS NULL OR kind IN ('deposit', 'balance'))]';
    DBMS_OUTPUT.PUT_LINE('migration 2026-10-13_payment_balance: check constraints updated');
END;
/
