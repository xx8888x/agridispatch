-- 2026-10-16 工人出勤補登／修正申請：建 attendance_requests
-- 規則見後端 AttendanceRequestService；工人頁送申請，科技後台 TechBackend/payroll.html 審核
-- 可重複執行：表已存在就略過
DECLARE
    n NUMBER;
BEGIN
    SELECT COUNT(*) INTO n FROM all_tables
     WHERE owner = SYS_CONTEXT('USERENV', 'CURRENT_SCHEMA') AND table_name = 'ATTENDANCE_REQUESTS';
    IF n = 0 THEN
        EXECUTE IMMEDIATE q'[
            CREATE TABLE attendance_requests (
                id                   VARCHAR2(32 CHAR)   NOT NULL,
                worker_id            VARCHAR2(32 CHAR)   NOT NULL,
                kind                 VARCHAR2(10 CHAR)   NOT NULL,
                attendance_id        NUMBER(19),
                work_date            DATE                NOT NULL,
                clock_in             VARCHAR2(5 CHAR),
                clock_out            VARCHAR2(5 CHAR),
                order_id             VARCHAR2(32 CHAR),
                reason               VARCHAR2(200 CHAR)  NOT NULL,
                status               VARCHAR2(20 CHAR)   NOT NULL,
                requested_by         VARCHAR2(64 CHAR),
                created_at           TIMESTAMP           DEFAULT SYSTIMESTAMP NOT NULL,
                decided_by           VARCHAR2(64 CHAR),
                decided_at           TIMESTAMP,
                decision_note        VARCHAR2(200 CHAR),
                result_attendance_id NUMBER(19),
                CONSTRAINT pk_attendance_requests PRIMARY KEY (id),
                CONSTRAINT fk_att_req_worker FOREIGN KEY (worker_id) REFERENCES workers(id),
                CONSTRAINT fk_att_req_clock_in FOREIGN KEY (attendance_id) REFERENCES clock_ins(id),
                CONSTRAINT chk_att_req_kind CHECK (kind IN ('create', 'update')),
                CONSTRAINT chk_att_req_status CHECK (status IN ('pending', 'approved', 'rejected', 'cancelled'))
            )]';
        EXECUTE IMMEDIATE 'CREATE INDEX idx_att_req_worker ON attendance_requests (worker_id, created_at)';
        EXECUTE IMMEDIATE 'CREATE INDEX idx_att_req_status ON attendance_requests (status, work_date)';
        EXECUTE IMMEDIATE q'[COMMENT ON TABLE attendance_requests IS '工人出勤補登／修正申請：工人本人申請、合作社審核；核准時才寫入 clock_ins 並留 attendance_corrections 紀錄']';
        EXECUTE IMMEDIATE q'[COMMENT ON COLUMN attendance_requests.kind IS 'create 補登新出勤 / update 修正既有出勤（attendance_id）']';
        EXECUTE IMMEDIATE q'[COMMENT ON COLUMN attendance_requests.status IS 'pending 待審核 / approved 已核准 / rejected 已駁回 / cancelled 已取消']';
    END IF;
END;
/
