-- 2026-10-13 工人請假：建 worker_leaves 表（工人申請、合作社核准；核准的假在派工媒合視同撞期）
-- 可重複執行：表已存在就略過
DECLARE
    n NUMBER;
BEGIN
    SELECT COUNT(*) INTO n FROM all_tables
     WHERE owner = SYS_CONTEXT('USERENV', 'CURRENT_SCHEMA') AND table_name = 'WORKER_LEAVES';
    IF n = 0 THEN
        EXECUTE IMMEDIATE q'[
            CREATE TABLE worker_leaves (
                id                 VARCHAR2(32 CHAR)   NOT NULL,
                worker_id          VARCHAR2(32 CHAR)   NOT NULL,
                leave_type         VARCHAR2(20 CHAR)   NOT NULL,
                start_date         DATE                NOT NULL,
                end_date           DATE                NOT NULL,
                time_slot          VARCHAR2(16 CHAR)   DEFAULT 'full_day' NOT NULL,
                reason             VARCHAR2(200 CHAR),
                status             VARCHAR2(20 CHAR)   DEFAULT 'pending' NOT NULL,
                requested_by       VARCHAR2(64 CHAR),
                created_at         TIMESTAMP           DEFAULT SYSTIMESTAMP NOT NULL,
                decided_by         VARCHAR2(64 CHAR),
                decided_at         TIMESTAMP,
                decision_note      VARCHAR2(200 CHAR),
                CONSTRAINT pk_worker_leaves PRIMARY KEY (id),
                CONSTRAINT fk_worker_leaves_worker FOREIGN KEY (worker_id) REFERENCES workers(id),
                CONSTRAINT chk_worker_leaves_period CHECK (end_date >= start_date),
                CONSTRAINT chk_worker_leaves_slot CHECK (time_slot IN ('full_day', 'morning', 'afternoon')),
                CONSTRAINT chk_worker_leaves_status CHECK (status IN ('pending', 'approved', 'rejected', 'cancelled'))
            )]';
        EXECUTE IMMEDIATE 'CREATE INDEX idx_worker_leaves_worker ON worker_leaves (worker_id, start_date)';
        EXECUTE IMMEDIATE 'CREATE INDEX idx_worker_leaves_status ON worker_leaves (status)';
        EXECUTE IMMEDIATE q'[COMMENT ON TABLE worker_leaves IS '工人請假：工人申請、合作社核准；核准的假在派工媒合視同撞期。假別給薪與額度依勞基法與勞工請假規則【待確認】']';
        EXECUTE IMMEDIATE q'[COMMENT ON COLUMN worker_leaves.leave_type IS '假別：annual 特休 / personal 事假 / sick 普通傷病假 / menstrual 生理假 / family_care 家庭照顧假 / marriage 婚假 / bereavement 喪假 / official 公假 / injury 公傷病假 / maternity 產假產檢假 / paternity 陪產檢及陪產假 / other 其他']';
        EXECUTE IMMEDIATE q'[COMMENT ON COLUMN worker_leaves.time_slot IS 'full_day 整天 / morning 上午 / afternoon 下午（半天假須單日）']';
        EXECUTE IMMEDIATE q'[COMMENT ON COLUMN worker_leaves.status IS 'pending 待審核 / approved 已核准 / rejected 已駁回 / cancelled 已取消']';
    END IF;
END;
/
