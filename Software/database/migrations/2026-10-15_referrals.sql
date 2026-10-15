-- 2026-10-15 推薦裂變：建 referral_codes（推薦碼）與 referrals（推薦紀錄）表
-- 可重複執行：表已存在就略過
DECLARE
    n NUMBER;
BEGIN
    SELECT COUNT(*) INTO n FROM all_tables
     WHERE owner = SYS_CONTEXT('USERENV', 'CURRENT_SCHEMA') AND table_name = 'REFERRAL_CODES';
    IF n = 0 THEN
        EXECUTE IMMEDIATE q'[
            CREATE TABLE referral_codes (
                code               VARCHAR2(12 CHAR)   NOT NULL,
                owner_username     VARCHAR2(64 CHAR)   NOT NULL,
                owner_ref_id       VARCHAR2(32 CHAR),
                owner_role         VARCHAR2(32 CHAR)   NOT NULL,
                click_count        NUMBER(10)          DEFAULT 0 NOT NULL,
                created_at         TIMESTAMP           DEFAULT SYSTIMESTAMP NOT NULL,
                CONSTRAINT pk_referral_codes PRIMARY KEY (code),
                CONSTRAINT uk_referral_codes_owner UNIQUE (owner_username)
            )]';
        EXECUTE IMMEDIATE q'[COMMENT ON TABLE referral_codes IS '推薦碼：每個農場主／工人一組，邀請連結帶 ?ref=code；click_count 為連結開啟次數']';
    ELSE
        DBMS_OUTPUT.PUT_LINE('migration 2026-10-15_referrals: referral_codes already exists');
    END IF;

    SELECT COUNT(*) INTO n FROM all_tables
     WHERE owner = SYS_CONTEXT('USERENV', 'CURRENT_SCHEMA') AND table_name = 'REFERRALS';
    IF n = 0 THEN
        EXECUTE IMMEDIATE q'[
            CREATE TABLE referrals (
                id                 VARCHAR2(32 CHAR)   NOT NULL,
                code               VARCHAR2(12 CHAR)   NOT NULL,
                referrer_username  VARCHAR2(64 CHAR)   NOT NULL,
                referrer_role      VARCHAR2(32 CHAR)   NOT NULL,
                invitee_role       VARCHAR2(16 CHAR)   NOT NULL,
                invitee_name       VARCHAR2(50 CHAR)   NOT NULL,
                invitee_phone      VARCHAR2(30 CHAR),
                invitee_ref_id     VARCHAR2(32 CHAR),
                channel            VARCHAR2(16 CHAR),
                status             VARCHAR2(16 CHAR)   DEFAULT 'pending' NOT NULL,
                note               VARCHAR2(500 CHAR),
                created_at         TIMESTAMP           DEFAULT SYSTIMESTAMP NOT NULL,
                updated_at         TIMESTAMP,
                CONSTRAINT pk_referrals PRIMARY KEY (id),
                CONSTRAINT chk_ref_invitee_role CHECK (invitee_role IN ('farmer', 'worker')),
                CONSTRAINT chk_ref_status CHECK (status IN ('registered', 'pending', 'contacted', 'onboarded', 'closed'))
            )]';
        EXECUTE IMMEDIATE 'CREATE INDEX idx_referrals_referrer ON referrals (referrer_username)';
        EXECUTE IMMEDIATE q'[COMMENT ON TABLE referrals IS '推薦紀錄：農場主經邀請連結註冊＝registered；工人意願登記由合作社跟進（pending→contacted→onboarded／closed）；不含獎勵金額']';
    ELSE
        DBMS_OUTPUT.PUT_LINE('migration 2026-10-15_referrals: referrals already exists');
    END IF;
END;
/
