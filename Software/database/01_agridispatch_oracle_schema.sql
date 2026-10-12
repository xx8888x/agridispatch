-- ====================================================================
-- 智農派工 (AgriDispatch) - Oracle Database 完整建庫與初始資料腳本
-- 適用版本：Oracle Database 19c / 21c / 23c / 23ai Free / XE
-- 字元集建議：AL32UTF8
-- ====================================================================

-- 【步驟 0】建立專案專用使用者與權限 (需以 SYSDBA 或 SYSTEM 執行，若已在專屬 Schema 下可略過)
/*
CREATE USER c##agridispatch IDENTIFIED BY "<請自行設定強密碼，勿寫入版本控制>"
DEFAULT TABLESPACE users
TEMPORARY TABLESPACE temp
QUOTA UNLIMITED ON users;

GRANT CONNECT, RESOURCE, CREATE VIEW, CREATE SEQUENCE, CREATE SYNONYM TO c##agridispatch;
ALTER SESSION SET CURRENT_SCHEMA = c##agridispatch;
*/

-- 清理舊有資料表 (若存在)
BEGIN
    EXECUTE IMMEDIATE 'DROP TABLE skill_reviews CASCADE CONSTRAINTS';
EXCEPTION WHEN OTHERS THEN NULL;
END;
/
BEGIN
    EXECUTE IMMEDIATE 'DROP TABLE insurance_records CASCADE CONSTRAINTS';
EXCEPTION WHEN OTHERS THEN NULL;
END;
/
BEGIN
    EXECUTE IMMEDIATE 'DROP TABLE legalize_workers CASCADE CONSTRAINTS';
EXCEPTION WHEN OTHERS THEN NULL;
END;
/
BEGIN
    EXECUTE IMMEDIATE 'DROP TABLE team_ratings CASCADE CONSTRAINTS';
EXCEPTION WHEN OTHERS THEN NULL;
END;
/
BEGIN
    EXECUTE IMMEDIATE 'DROP TABLE payment_transactions CASCADE CONSTRAINTS';
EXCEPTION WHEN OTHERS THEN NULL;
END;
/
BEGIN
    EXECUTE IMMEDIATE 'DROP TABLE brochures CASCADE CONSTRAINTS';
EXCEPTION WHEN OTHERS THEN NULL;
END;
/
BEGIN
    EXECUTE IMMEDIATE 'DROP TABLE users CASCADE CONSTRAINTS';
EXCEPTION WHEN OTHERS THEN NULL;
END;
/
BEGIN
    EXECUTE IMMEDIATE 'DROP TABLE line_messages CASCADE CONSTRAINTS';
EXCEPTION WHEN OTHERS THEN NULL;
END;
/
BEGIN
    EXECUTE IMMEDIATE 'DROP TABLE line_users CASCADE CONSTRAINTS';
EXCEPTION WHEN OTHERS THEN NULL;
END;
/
BEGIN
    EXECUTE IMMEDIATE 'DROP TABLE financial_ledger CASCADE CONSTRAINTS';
EXCEPTION WHEN OTHERS THEN NULL;
END;
/
BEGIN
    EXECUTE IMMEDIATE 'DROP TABLE clock_ins CASCADE CONSTRAINTS';
EXCEPTION WHEN OTHERS THEN NULL;
END;
/
BEGIN
    EXECUTE IMMEDIATE 'DROP TABLE workers CASCADE CONSTRAINTS';
EXCEPTION WHEN OTHERS THEN NULL;
END;
/
BEGIN
    EXECUTE IMMEDIATE 'DROP TABLE orders CASCADE CONSTRAINTS';
EXCEPTION WHEN OTHERS THEN NULL;
END;
/
BEGIN
    EXECUTE IMMEDIATE 'DROP TABLE dispatch_teams CASCADE CONSTRAINTS';
EXCEPTION WHEN OTHERS THEN NULL;
END;
/
BEGIN
    EXECUTE IMMEDIATE 'DROP TABLE farmers CASCADE CONSTRAINTS';
EXCEPTION WHEN OTHERS THEN NULL;
END;
/
BEGIN
    EXECUTE IMMEDIATE 'DROP TABLE crops CASCADE CONSTRAINTS';
EXCEPTION WHEN OTHERS THEN NULL;
END;
/
BEGIN
    EXECUTE IMMEDIATE 'DROP TABLE talents CASCADE CONSTRAINTS';
EXCEPTION WHEN OTHERS THEN NULL;
END;
/
BEGIN
    EXECUTE IMMEDIATE 'DROP TABLE weather_states CASCADE CONSTRAINTS';
EXCEPTION WHEN OTHERS THEN NULL;
END;
/

-- ====================================================================
-- 1. 作物基準表 (CROPS)
-- ====================================================================
CREATE TABLE crops (
    id                 VARCHAR2(32 CHAR)   NOT NULL,
    name               VARCHAR2(100 CHAR)  NOT NULL,
    base_hours_per_fen NUMBER(6,1)    DEFAULT 20 NOT NULL,
    workers_per_fen    NUMBER(4)      DEFAULT 3 NOT NULL,
    rate               NUMBER(8)      DEFAULT 300 NOT NULL,
    season             VARCHAR2(50 CHAR)   NOT NULL,
    task_description   VARCHAR2(1000 CHAR) NOT NULL,
    indoor_alternative VARCHAR2(1000 CHAR) NOT NULL,
    created_at         TIMESTAMP      DEFAULT SYSTIMESTAMP NOT NULL,
    CONSTRAINT pk_crops PRIMARY KEY (id)
);

COMMENT ON TABLE crops IS '作物基準表：存放各農作物分地工時、費率與避險作業SOP';
COMMENT ON COLUMN crops.id IS '作物唯一識別碼 (如 mango, onion, pepper...)';
COMMENT ON COLUMN crops.name IS '作物全稱與主要產區';
COMMENT ON COLUMN crops.base_hours_per_fen IS '每分地基準作業工時';
COMMENT ON COLUMN crops.workers_per_fen IS '每分地建議派遣人數';
COMMENT ON COLUMN crops.rate IS '每工時標準牌價 (新台幣)';
COMMENT ON COLUMN crops.season IS '盛產作業產季週期';
COMMENT ON COLUMN crops.task_description IS '田間外銷級採收作業標準SOP';
COMMENT ON COLUMN crops.indoor_alternative IS '突發暴雨室內冷鏈集貨備援作業';

-- ====================================================================
-- 2. 小農/契作商表 (FARMERS)
-- ====================================================================
CREATE TABLE farmers (
    id                 VARCHAR2(32 CHAR)   NOT NULL,
    name               VARCHAR2(100 CHAR)  NOT NULL,
    phone              VARCHAR2(30 CHAR)   NOT NULL,
    address            VARCHAR2(255 CHAR)  NOT NULL,
    tax_id             VARCHAR2(20 CHAR),
    farm_type          VARCHAR2(50 CHAR)   DEFAULT '個人農戶' NOT NULL,
    created_at         TIMESTAMP      DEFAULT SYSTIMESTAMP NOT NULL,
    CONSTRAINT pk_farmers PRIMARY KEY (id)
);

COMMENT ON TABLE farmers IS '小農與契作農企業表：發包單位主檔';
COMMENT ON COLUMN farmers.id IS '小農/產銷班編號 (如 F001)';
COMMENT ON COLUMN farmers.name IS '農場名稱或負責人姓名';
COMMENT ON COLUMN farmers.phone IS '聯絡手機 / LINE 綁定號碼';
COMMENT ON COLUMN farmers.address IS '農場/溫室田區地址';
COMMENT ON COLUMN farmers.tax_id IS '統一編號 (供合作社開立合規發票扣抵營業稅)';
COMMENT ON COLUMN farmers.farm_type IS '農場型態 (個人農戶 / 產銷班 / 農企業 / 農會／合作社)';

-- 農場主登錄的農場（一個農場主可多個，預約派工時選一個）；farmer_id 為 users.ref_id，
-- 種子帳號沒有 farmers 列，故不設外鍵。既有資料庫以 migrations/2026-10-12_farms.sql 補建
CREATE TABLE farms (
    id                 VARCHAR2(32 CHAR)   NOT NULL,
    farmer_id          VARCHAR2(32 CHAR)   NOT NULL,
    name               VARCHAR2(100 CHAR)  NOT NULL,
    contact_name       VARCHAR2(50 CHAR)   NOT NULL,
    phone              VARCHAR2(30 CHAR)   NOT NULL,
    address            VARCHAR2(255 CHAR)  NOT NULL,
    farm_type          VARCHAR2(50 CHAR)   DEFAULT '個人農戶' NOT NULL,
    created_at         TIMESTAMP      DEFAULT SYSTIMESTAMP NOT NULL,
    CONSTRAINT pk_farms PRIMARY KEY (id)
);
CREATE INDEX idx_farms_farmer ON farms (farmer_id, created_at);

COMMENT ON TABLE farms IS '農場主登錄的農場：預約派工時選擇';
COMMENT ON COLUMN farms.id IS '農場編號 (如 FM0001)';
COMMENT ON COLUMN farms.farmer_id IS '農場主編號 (users.ref_id，如 F001)';
COMMENT ON COLUMN farms.contact_name IS '現場聯絡人';
COMMENT ON COLUMN farms.address IS '農場地址／地號（預約時的預設作業地點）';

-- ====================================================================
-- 3. 調度工班與專車表 (DISPATCH_TEAMS)
-- ====================================================================
CREATE TABLE dispatch_teams (
    id                 VARCHAR2(32 CHAR)   NOT NULL,
    name               VARCHAR2(50 CHAR)   NOT NULL,
    worker_count       NUMBER(4)      DEFAULT 0 NOT NULL,
    leader             VARCHAR2(50 CHAR)   NOT NULL,
    leader_phone       VARCHAR2(30 CHAR)   NOT NULL,
    vehicle            VARCHAR2(100 CHAR)  NOT NULL,
    driver             VARCHAR2(50 CHAR)   NOT NULL,
    normal_location    VARCHAR2(255 CHAR)  NOT NULL,
    normal_task        VARCHAR2(255 CHAR)  NOT NULL,
    hedge_location     VARCHAR2(255 CHAR)  NOT NULL,
    hedge_task         VARCHAR2(255 CHAR)  NOT NULL,
    status             VARCHAR2(30 CHAR)   DEFAULT 'active_field' NOT NULL,
    CONSTRAINT pk_dispatch_teams PRIMARY KEY (id),
    CONSTRAINT chk_team_status CHECK (status IN ('active_field', 'hedged_indoor', 'standby'))
);

COMMENT ON TABLE dispatch_teams IS '調度工班與專車表：管理4大工班與接送專車路網';
COMMENT ON COLUMN dispatch_teams.id IS '工班代號 (如 team_a, team_b...)';
COMMENT ON COLUMN dispatch_teams.name IS '工班名稱';
COMMENT ON COLUMN dispatch_teams.worker_count IS '在籍農工人數';
COMMENT ON COLUMN dispatch_teams.leader IS '帶隊組長姓名';
COMMENT ON COLUMN dispatch_teams.vehicle IS '專屬接送專車車號 (如 智農 1 號車 RBF-8899)';
COMMENT ON COLUMN dispatch_teams.normal_location IS '晴天天候田間出勤場域';
COMMENT ON COLUMN dispatch_teams.hedge_location IS '暴雨天候室內冷鏈避險場域';
COMMENT ON COLUMN dispatch_teams.status IS '出勤狀態 (active_field:田間出勤, hedged_indoor:冷鏈避險, standby:待命)';

-- ====================================================================
-- 4. 派工訂單表 (ORDERS)
-- ====================================================================
CREATE TABLE orders (
    id                 VARCHAR2(32 CHAR)   NOT NULL,
    farmer_id          VARCHAR2(32 CHAR),
    farmer_name        VARCHAR2(100 CHAR)  NOT NULL,
    phone              VARCHAR2(30 CHAR),               -- API 允許管理員代建訂單時不填
    farm_location      VARCHAR2(255 CHAR),
    crop_id            VARCHAR2(32 CHAR)   NOT NULL,
    crop_name          VARCHAR2(100 CHAR)  NOT NULL,
    area               NUMBER(6,2)    NOT NULL,
    plan               VARCHAR2(30 CHAR)   DEFAULT 'early_bird' NOT NULL,
    plan_name          VARCHAR2(100 CHAR)  NOT NULL,
    dispatch_category  VARCHAR2(50 CHAR),
    category_name      VARCHAR2(100 CHAR),
    transport_zone     VARCHAR2(50 CHAR),
    transport_surcharge NUMBER(8)     DEFAULT 0 NOT NULL,
    total_hours        NUMBER(6)      NOT NULL,
    suggested_workers  NUMBER(4)      NOT NULL,
    unit_rate          NUMBER(8)      NOT NULL,
    standard_amount    NUMBER(10)     NOT NULL,
    final_amount       NUMBER(10)     NOT NULL,
    deposit_amount     NUMBER(10)     DEFAULT 0 NOT NULL,
    deposit_paid       CHAR(1)        DEFAULT 'Y' NOT NULL,
    status             VARCHAR2(30 CHAR)   DEFAULT 'confirmed' NOT NULL,
    assigned_team_id   VARCHAR2(32 CHAR),
    foreman_name       VARCHAR2(100 CHAR),
    foreman_phone      VARCHAR2(30 CHAR),
    foreman_notes      VARCHAR2(500 CHAR),
    crew_worker_ids    VARCHAR2(400 CHAR),
    requested_workers  NUMBER(4),
    package_id         VARCHAR2(40 CHAR),
    farm_id            VARCHAR2(32 CHAR),               -- 下單時選的農場（farms.id）；農場可刪除，故不設外鍵
    farm_name          VARCHAR2(100 CHAR),              -- 下單當時的農場名稱（快照）
    preferred_worker_id   VARCHAR2(32 CHAR),            -- 農場主指名的工人（workers.id）；工人可刪除，故不設外鍵
    preferred_worker_name VARCHAR2(100 CHAR),           -- 指名工人姓名（快照）
    start_date         DATE,
    end_date           DATE,
    created_at         TIMESTAMP      DEFAULT SYSTIMESTAMP NOT NULL,
    CONSTRAINT pk_orders PRIMARY KEY (id),
    CONSTRAINT chk_orders_period CHECK ((start_date IS NULL AND end_date IS NULL) OR (start_date IS NOT NULL AND end_date IS NOT NULL AND end_date >= start_date)),
    CONSTRAINT fk_orders_crop FOREIGN KEY (crop_id) REFERENCES crops(id),
    CONSTRAINT fk_orders_team FOREIGN KEY (assigned_team_id) REFERENCES dispatch_teams(id),
    CONSTRAINT chk_orders_deposit_paid CHECK (deposit_paid IN ('Y', 'N'))
);

COMMENT ON TABLE orders IS '派工訂單表：早鳥工時包預約與出工履約紀錄';
COMMENT ON COLUMN orders.id IS '訂單流水號 (如 ORD-2026-001)';
COMMENT ON COLUMN orders.farmer_id IS '下單農場主編號（登入 token 的 ref，對應 users.ref_id）；不設 FK 到 farmers：路演帳號與新註冊農場主不一定有 farmers 資料';
COMMENT ON COLUMN orders.crew_worker_ids IS '出工名單（工號逗號分隔），由派工媒合設定；有名單時派工前加保檢查與打卡連結以名單為準';
COMMENT ON COLUMN orders.preferred_worker_id IS '農場主指名的回頭工人（限曾到該農場主訂單出工者，建單時驗證）；媒合時排在出工名單第一位，尚無名單時視同佔用該工期';
COMMENT ON COLUMN orders.start_date IS '預定開工日（含當日）；與 end_date 須同時有值或同時為 NULL';
COMMENT ON COLUMN orders.end_date IS '預定完工日（含當日）；派工媒合依工期排除撞期工人、打卡依工期判斷所屬訂單';
COMMENT ON COLUMN orders.plan IS '商業方案 (early_bird:早鳥工時包 315元/hr 不打折, hourly_flex:彈性點工, turnkey:成果統包)';
COMMENT ON COLUMN orders.final_amount IS '折讓後應付總額';
COMMENT ON COLUMN orders.deposit_amount IS '早鳥 20% 鎖約定金';
COMMENT ON COLUMN orders.deposit_paid IS '定金是否已繳納 (Y/N)';

-- ====================================================================
-- 5. 在籍外展移工表 (WORKERS)
-- ====================================================================
CREATE TABLE workers (
    id                  VARCHAR2(32 CHAR)  NOT NULL,
    team_id             VARCHAR2(32 CHAR)  NOT NULL,
    name                VARCHAR2(100 CHAR) NOT NULL,
    chinese_name        VARCHAR2(50 CHAR)  NOT NULL,
    nationality         VARCHAR2(50 CHAR)  NOT NULL,
    is_leader           CHAR(1)       DEFAULT 'N' NOT NULL,
    tier                VARCHAR2(30 CHAR),
    base_salary         NUMBER(8)     DEFAULT 29500 NOT NULL,
    agri_bonus          NUMBER(8)     DEFAULT 8800 NOT NULL,
    employment_type     VARCHAR2(16 CHAR),   -- 本國工人工作型態：full_time 正職／part_time 兼職／senior 中高齡／casual 臨時打工／student 學生打工
    transport_allowance NUMBER(8)     DEFAULT 2400 NOT NULL,
    arc_number          VARCHAR2(32 CHAR),
    passport_number     VARCHAR2(32 CHAR),
    permit_expiry       VARCHAR2(20 CHAR),
    insurance_status    VARCHAR2(100 CHAR),
    current_task        VARCHAR2(255 CHAR),
    dispatch_status     VARCHAR2(30 CHAR),
    phone               VARCHAR2(30 CHAR),
    password_hash       VARCHAR2(255 CHAR),
    token_version       NUMBER(10)     DEFAULT 0 NOT NULL,   -- 改密碼／重設／變更層級時 +1，舊 token 立即失效
    created_at          TIMESTAMP     DEFAULT SYSTIMESTAMP NOT NULL,
    CONSTRAINT pk_workers PRIMARY KEY (id),
    CONSTRAINT fk_workers_team FOREIGN KEY (team_id) REFERENCES dispatch_teams(id),
    CONSTRAINT chk_workers_leader CHECK (is_leader IN ('Y', 'N'))
);

COMMENT ON TABLE workers IS '在籍外展農工表：外展機構合法聘僱之 20 位外展移工與本國師級人才';
COMMENT ON COLUMN workers.id IS '移工編號 (如 W001)';
COMMENT ON COLUMN workers.nationality IS '國籍 (越南, 印尼, 泰國, 本國)';
COMMENT ON COLUMN workers.base_salary IS '外展保障基本法定底薪（2026 年最低工資 NT$ 29,500）';
COMMENT ON COLUMN workers.agri_bonus IS '中央農業部外展就業獎勵 (最高 NT$ 8,800/月)';
COMMENT ON COLUMN workers.employment_type IS '工作型態 (full_time 正職, part_time 兼職, senior 中高齡, casual 臨時打工, student 學生打工；外籍為 NULL)';
COMMENT ON COLUMN workers.transport_allowance IS '高溫與專車交通津貼 (NT$ 2,400/月)';

-- ====================================================================
-- 6. GPS 現場數位打卡表 (CLOCK_INS)
-- ====================================================================
CREATE TABLE clock_ins (
    id                 NUMBER(19)     GENERATED BY DEFAULT AS IDENTITY,   -- 後端 Attendance.id 為 Long + IDENTITY
    worker_id          VARCHAR2(32 CHAR)   NOT NULL,
    work_date          DATE,
    latitude           NUMBER(10,6),
    longitude          NUMBER(10,6),
    location_name      VARCHAR2(255 CHAR),
    location           VARCHAR2(200 CHAR),
    gps_accuracy_meters NUMBER(8,2),
    clock_in_time      TIMESTAMP      DEFAULT SYSTIMESTAMP NOT NULL,
    clock_out_time     TIMESTAMP,
    status             VARCHAR2(20 CHAR)   DEFAULT 'success' NOT NULL,
    actual_hours       NUMBER(6,2),
    normal_hours       NUMBER(6,2),
    overtime_hours     NUMBER(6,2),
    foreman_signature  VARCHAR2(50 CHAR),
    order_id           VARCHAR2(40 CHAR),
    foreman_rating     VARCHAR2(20 CHAR),
    foreman_rated_by   VARCHAR2(32 CHAR),
    foreman_rated_at   TIMESTAMP,
    created_at         TIMESTAMP      DEFAULT SYSTIMESTAMP,
    CONSTRAINT chk_clock_ins_rating CHECK (foreman_rating IS NULL OR foreman_rating IN ('excellent', 'ok', 'needs_improvement')),
    CONSTRAINT pk_clock_ins PRIMARY KEY (id),
    CONSTRAINT fk_clock_ins_worker FOREIGN KEY (worker_id) REFERENCES workers(id),
    CONSTRAINT fk_clock_ins_order FOREIGN KEY (order_id) REFERENCES orders(id)
);

CREATE INDEX idx_clock_ins_order ON clock_ins (order_id, work_date);

COMMENT ON TABLE clock_ins IS 'GPS 現場數位打卡紀錄表：工人抵達田區或冷鏈場打卡';
COMMENT ON COLUMN clock_ins.order_id IS '本次出勤所屬訂單（合法用工證明依此彙整）；舊資料或無法判定時為 NULL';
COMMENT ON COLUMN clock_ins.foreman_rating IS '領班逐人評核：excellent 優 / ok 可 / needs_improvement 需加強；NULL 為未評核';
COMMENT ON COLUMN clock_ins.foreman_rated_by IS '評核者工號（同工班領班）或管理員編號';

-- ====================================================================
-- 7. 自願登錄人才庫表 (TALENTS)
-- ====================================================================
CREATE TABLE talents (
    id                 VARCHAR2(32 CHAR)   NOT NULL,
    name               VARCHAR2(100 CHAR)  NOT NULL,
    category           VARCHAR2(50 CHAR)   NOT NULL,
    category_name      VARCHAR2(100 CHAR)  NOT NULL,
    phone              VARCHAR2(30 CHAR)   NOT NULL,
    experience         VARCHAR2(1000 CHAR) NOT NULL,
    pdpa_consent       CHAR(1)        DEFAULT 'Y' NOT NULL,
    registered_at      TIMESTAMP      DEFAULT SYSTIMESTAMP,
    CONSTRAINT pk_talents PRIMARY KEY (id),
    CONSTRAINT chk_talents_pdpa CHECK (pdpa_consent IN ('Y', 'N'))
);

COMMENT ON TABLE talents IS '自願登錄人才庫表：合規個資法第19條，杜絕網路爬蟲獵人頭違法爭議';

-- ====================================================================
-- 8. 天候避險狀態表 (WEATHER_STATES)
-- ====================================================================
CREATE TABLE weather_states (
    id                 VARCHAR2(32 CHAR)   NOT NULL,
    scenario_id        VARCHAR2(32 CHAR)   DEFAULT 'cloudy' NOT NULL,
    scenario_name      VARCHAR2(64 CHAR)   DEFAULT '舒適多雲時晴' NOT NULL,
    is_rain_mode       CHAR(1)        DEFAULT 'N' NOT NULL,
    current_weather    VARCHAR2(100 CHAR)  NOT NULL,
    alert_level        VARCHAR2(50 CHAR)   DEFAULT 'normal' NOT NULL,
    message            VARCHAR2(500 CHAR)  NOT NULL,
    secured_revenue    NUMBER(10)     DEFAULT 62500 NOT NULL,
    daily_capacity_hrs NUMBER(6)      DEFAULT 160 NOT NULL,
    regional_json      CLOB,
    updated_at         TIMESTAMP      DEFAULT SYSTIMESTAMP NOT NULL,
    CONSTRAINT pk_weather_states PRIMARY KEY (id),
    CONSTRAINT chk_rain_mode CHECK (is_rain_mode IN ('Y', 'N'))
);

COMMENT ON TABLE weather_states IS '天候避險狀態表：微氣候大腦狀態與全區通報紀錄';

-- ====================================================================
-- 9. 雙軌利潤分流帳本 (FINANCIAL_LEDGER)
-- ====================================================================
CREATE TABLE financial_ledger (
    id                 VARCHAR2(32 CHAR)   NOT NULL,
    order_id           VARCHAR2(32 CHAR)   NOT NULL,
    total_gmv          NUMBER(12)     NOT NULL,
    tech_take_rate     NUMBER(12)     NOT NULL,
    coop_pass_through  NUMBER(12)     NOT NULL,
    total_agri_subsidy NUMBER(10)     DEFAULT 0 NOT NULL,
    settlement_date    TIMESTAMP      DEFAULT SYSTIMESTAMP NOT NULL,
    CONSTRAINT pk_financial_ledger PRIMARY KEY (id),
    CONSTRAINT fk_ledger_order FOREIGN KEY (order_id) REFERENCES orders(id)
);

COMMENT ON TABLE financial_ledger IS '雙軌利潤分流帳本：科技公司16%代管費與合作社84%人事成本清算（F2 v2.4）';

-- ====================================================================
-- 10. LINE 官方帳號使用者表 (LINE_USERS)
--     雙帳號（農場主 farmer／工作者 worker）：一筆＝一個 LINE userId 在一個帳號的追蹤、語言與綁定狀態
--     規格：07_系統分析及文件/05_LINE官方帳號拆分規格.md 6.5（實體 LineUser.java）
-- ====================================================================
CREATE TABLE line_users (
    id                 VARCHAR2(32 CHAR)   NOT NULL,
    channel            VARCHAR2(10 CHAR)   NOT NULL,
    line_user_id       VARCHAR2(64 CHAR)   NOT NULL,
    username           VARCHAR2(32 CHAR),
    ref_id             VARCHAR2(32 CHAR),
    display_name       VARCHAR2(100 CHAR),
    role               VARCHAR2(32 CHAR),
    lang               VARCHAR2(10 CHAR)   DEFAULT 'zh' NOT NULL,
    lang_locked        CHAR(1)        DEFAULT 'N' NOT NULL,
    is_bound           CHAR(1)        DEFAULT 'N' NOT NULL,
    bound_at           TIMESTAMP,
    followed           CHAR(1)        DEFAULT 'Y' NOT NULL,
    follow_changed_at  TIMESTAMP,
    CONSTRAINT pk_line_users PRIMARY KEY (id),
    CONSTRAINT uk_line_users_user_channel UNIQUE (line_user_id, channel),
    CONSTRAINT chk_line_channel CHECK (channel IN ('farmer', 'worker')),
    CONSTRAINT chk_line_lang CHECK (lang IN ('zh', 'vi', 'id', 'th', 'tl')),
    CONSTRAINT chk_line_lang_locked CHECK (lang_locked IN ('Y', 'N')),
    CONSTRAINT chk_line_is_bound CHECK (is_bound IN ('Y', 'N')),
    CONSTRAINT chk_line_followed CHECK (followed IN ('Y', 'N')),
    -- 已綁定必有平台帳號與身分；未綁定三欄皆空
    CONSTRAINT chk_line_bound_fields CHECK (
        (is_bound = 'Y' AND username IS NOT NULL AND role IS NOT NULL AND bound_at IS NOT NULL)
        OR (is_bound = 'N' AND username IS NULL AND role IS NULL)),
    -- 帳號與身分相符：農場主帳號只綁 farmer，工作者帳號只綁 worker_*（規格 5.3 第 2 點）
    CONSTRAINT chk_line_role_channel CHECK (role IS NULL
        OR (channel = 'farmer' AND role = 'farmer')
        OR (channel = 'worker' AND role IN ('worker_foreign', 'worker_local')))
);

-- 同一個平台帳號在同一個 LINE 官方帳號只能綁一個 LINE userId（username 為空的未綁定列不受限）
CREATE UNIQUE INDEX uk_line_users_username_channel ON line_users (
    CASE WHEN username IS NOT NULL THEN username END,
    CASE WHEN username IS NOT NULL THEN channel END);

COMMENT ON TABLE line_users IS 'LINE 官方帳號使用者：農場主／工作者兩帳號的追蹤狀態、語言與平台帳號綁定';

-- ====================================================================
-- 11. LINE 推播歷程表 (LINE_MESSAGES)
-- ====================================================================
CREATE TABLE line_messages (
    id                 VARCHAR2(64 CHAR)   NOT NULL,
    line_user_id       VARCHAR2(64 CHAR),
    sender             VARCHAR2(64 CHAR)   DEFAULT 'SYSTEM_BROADCAST' NOT NULL,
    target_role        VARCHAR2(32 CHAR)   DEFAULT 'all' NOT NULL,
    message_type       VARCHAR2(32 CHAR)   NOT NULL,
    content            VARCHAR2(2000 CHAR) NOT NULL,
    created_at         TIMESTAMP      DEFAULT SYSTIMESTAMP NOT NULL,
    CONSTRAINT pk_line_messages PRIMARY KEY (id)
);

COMMENT ON TABLE line_messages IS 'LINE 訊息與推播歷程表：記錄 Flex Message 廣播與即時警報';

-- ====================================================================
-- 12. 產品文宣 DM 範本表 (BROCHURES)
-- ====================================================================
CREATE TABLE brochures (
    id                 VARCHAR2(64 CHAR)   NOT NULL,
    title              VARCHAR2(200 CHAR)  NOT NULL,
    subtitle           VARCHAR2(200 CHAR),
    category           VARCHAR2(50 CHAR),
    crop               VARCHAR2(100 CHAR),
    region             VARCHAR2(100 CHAR),
    target_audience    VARCHAR2(200 CHAR),
    badge              VARCHAR2(100 CHAR),
    icon               VARCHAR2(50 CHAR),
    theme_color        VARCHAR2(30 CHAR),
    cover_image        CLOB,
    original_price     NUMBER(10)     DEFAULT 0 NOT NULL,
    discount_price     NUMBER(10)     DEFAULT 0 NOT NULL,
    deposit_rate       NUMBER(4)      DEFAULT 20 NOT NULL,
    deposit_amount     NUMBER(10)     DEFAULT 0 NOT NULL,
    hours              NUMBER(6)      DEFAULT 0 NOT NULL,
    workers            NUMBER(4)      DEFAULT 0 NOT NULL,
    days               NUMBER(4)      DEFAULT 0 NOT NULL,
    description        CLOB,
    features_json      CLOB,
    comparison_json    CLOB,
    testimonial_name   VARCHAR2(100 CHAR),
    testimonial_title  VARCHAR2(200 CHAR),
    testimonial_quote  CLOB,
    phone              VARCHAR2(30 CHAR),
    service_office     VARCHAR2(200 CHAR),
    status             VARCHAR2(20 CHAR)   DEFAULT 'active' NOT NULL,
    created_at         VARCHAR2(30 CHAR),
    CONSTRAINT pk_brochures PRIMARY KEY (id),
    CONSTRAINT chk_brochure_status CHECK (status IN ('active', 'draft'))
);

COMMENT ON TABLE brochures IS '產品文宣 DM 範本表：早鳥工時包行銷文宣與農場主購買引導';
COMMENT ON COLUMN brochures.features_json IS '商品亮點清單 JSON 陣列';
COMMENT ON COLUMN brochures.comparison_json IS '同業比較表 JSON 陣列';
COMMENT ON COLUMN brochures.deposit_rate IS '定金比例 (% 整數，預設 20)';

-- ====================================================================
-- 13. 金流交易紀錄表 (PAYMENT_TRANSACTIONS)
-- ====================================================================
CREATE TABLE payment_transactions (
    id                 VARCHAR2(20 CHAR)   NOT NULL,
    order_id           VARCHAR2(40 CHAR)   NOT NULL,
    amount             NUMBER(10)     NOT NULL,
    provider           VARCHAR2(16 CHAR)   NOT NULL,
    payment_method     VARCHAR2(32 CHAR),
    status             VARCHAR2(16 CHAR)   NOT NULL,
    payer_name         VARCHAR2(100 CHAR),
    gateway_trade_no   VARCHAR2(40 CHAR),
    auth_code          VARCHAR2(20 CHAR),
    invoice_no         VARCHAR2(20 CHAR),
    created_at         TIMESTAMP      DEFAULT SYSTIMESTAMP,
    paid_at            TIMESTAMP,
    CONSTRAINT pk_payment_transactions PRIMARY KEY (id),
    CONSTRAINT fk_payment_order FOREIGN KEY (order_id) REFERENCES orders(id),
    CONSTRAINT chk_payment_status CHECK (status IN ('PENDING', 'PAID', 'FAILED')),
    CONSTRAINT chk_payment_provider CHECK (provider IN ('mock', 'ecpay'))
);

COMMENT ON TABLE payment_transactions IS '定金付款交易紀錄表：綠界 ECPay 與模擬金流之完整交易流水';
COMMENT ON COLUMN payment_transactions.id IS '交易序號（亦作為綠界 MerchantTradeNo，英數 20 碼內）';
COMMENT ON COLUMN payment_transactions.provider IS '金流供應商：mock (模擬) / ecpay (綠界)';
COMMENT ON COLUMN payment_transactions.gateway_trade_no IS '金流商回傳之交易編號（綠界 TradeNo）';
COMMENT ON COLUMN payment_transactions.auth_code IS '信用卡授權碼';
COMMENT ON COLUMN payment_transactions.invoice_no IS '電子發票號碼';

-- ====================================================================
-- 14. 系統帳號表 (USERS)
-- ====================================================================
CREATE TABLE users (
    username           VARCHAR2(64 CHAR)   NOT NULL,
    password           VARCHAR2(255 CHAR),
    name               VARCHAR2(100 CHAR)  NOT NULL,
    role               VARCHAR2(32 CHAR)   NOT NULL,
    ref_id             VARCHAR2(64 CHAR),
    role_desc          VARCHAR2(200 CHAR),
    token_version      NUMBER(10)     DEFAULT 0 NOT NULL,   -- 改密碼／重設／變更角色時 +1，舊 token 立即失效
    CONSTRAINT pk_users PRIMARY KEY (username),
    CONSTRAINT chk_user_role CHECK (role IN ('admin_tech', 'farmer', 'worker_foreign', 'worker_local'))
);

COMMENT ON TABLE users IS '系統帳號表：各角色登入身分與 PBKDF2 密碼雜湊';
COMMENT ON COLUMN users.password IS '密碼雜湊（pbkdf2$iterations$salt$hash），永不明碼儲存；NULL 表示尚未設定密碼（正式模式的示範帳號，須由管理員重設後才能登入）';
COMMENT ON COLUMN users.role IS '角色：admin_tech / farmer / worker_foreign / worker_local';
COMMENT ON COLUMN users.ref_id IS '關聯業務實體 ID（如農工 W001、農場主 F001）';

-- ====================================================================
-- 15. 工班評價表 (TEAM_RATINGS)
-- ====================================================================
CREATE TABLE team_ratings (
    id                 VARCHAR2(50 CHAR)   NOT NULL,
    order_id           VARCHAR2(40 CHAR)   NOT NULL,
    team_id            VARCHAR2(32 CHAR)   NOT NULL,
    farmer_id          VARCHAR2(40 CHAR),
    score              NUMBER(1)      NOT NULL,
    tags               VARCHAR2(200 CHAR),
    rating_comment     VARCHAR2(300 CHAR),
    created_at         TIMESTAMP      DEFAULT SYSTIMESTAMP,
    updated_at         TIMESTAMP,
    CONSTRAINT pk_team_ratings PRIMARY KEY (id),
    CONSTRAINT uq_team_ratings_order UNIQUE (order_id),
    CONSTRAINT fk_rating_order FOREIGN KEY (order_id) REFERENCES orders(id),
    CONSTRAINT fk_rating_team FOREIGN KEY (team_id) REFERENCES dispatch_teams(id),
    CONSTRAINT chk_rating_score CHECK (score BETWEEN 1 AND 5)
);

-- ====================================================================
-- 工班合法化（黑轉白）清冊 (LEGALIZE_WORKERS)
-- 失聯移工／借用移工（borrowed、runaway）不登錄個資：nickname 為 NULL
-- ====================================================================
CREATE TABLE legalize_workers (
    id                 VARCHAR2(32 CHAR)   NOT NULL,
    farmer_id          VARCHAR2(40 CHAR)   NOT NULL,
    worker_type        VARCHAR2(16 CHAR)   NOT NULL,
    nickname           VARCHAR2(60 CHAR),
    job                VARCHAR2(60 CHAR),
    days_per_week      NUMBER(1)      NOT NULL,
    steps_done         VARCHAR2(40 CHAR)   NOT NULL,
    created_at         TIMESTAMP      DEFAULT SYSTIMESTAMP,
    updated_at         TIMESTAMP,
    CONSTRAINT pk_legalize_workers PRIMARY KEY (id),
    CONSTRAINT chk_legalize_type CHECK (worker_type IN ('local','spouse','student','borrowed','runaway')),
    CONSTRAINT chk_legalize_days CHECK (days_per_week BETWEEN 1 AND 7),
    CONSTRAINT chk_legalize_anon CHECK (worker_type NOT IN ('borrowed','runaway') OR nickname IS NULL)
);

CREATE INDEX idx_team_ratings_team ON team_ratings (team_id, created_at);

COMMENT ON TABLE team_ratings IS '工班評價表：農場主於工班出工後為訂單評分，每張訂單一筆、可修改';
COMMENT ON COLUMN team_ratings.id IS '評價編號：RAT-<訂單編號>';
COMMENT ON COLUMN team_ratings.farmer_id IS '評分者（農場主 F001 等；不回傳給前端）';
COMMENT ON COLUMN team_ratings.score IS '整體評分 1～5';
COMMENT ON COLUMN team_ratings.tags IS '評價標籤，逗號分隔：準時到場/做工細心/效率高/溝通順暢/遵守安全規範/需加強訓練';

-- ====================================================================
-- 16. 加退保紀錄表 (INSURANCE_RECORDS)
-- ====================================================================
CREATE TABLE insurance_records (
    id                 VARCHAR2(60 CHAR)   NOT NULL,
    worker_id          VARCHAR2(32 CHAR)   NOT NULL,
    insurance_type     VARCHAR2(16 CHAR)   NOT NULL,
    insured_salary     NUMBER(8)      NOT NULL,
    enroll_date        DATE           NOT NULL,
    withdraw_date      DATE,
    withdraw_reason    VARCHAR2(100 CHAR),
    filing_ref         VARCHAR2(40 CHAR),
    created_at         TIMESTAMP      DEFAULT SYSTIMESTAMP,
    updated_at         TIMESTAMP,
    CONSTRAINT pk_insurance_records PRIMARY KEY (id),
    CONSTRAINT fk_insurance_worker FOREIGN KEY (worker_id) REFERENCES workers(id),
    CONSTRAINT chk_insurance_type CHECK (insurance_type IN ('labor', 'employment', 'occupational', 'health')),
    CONSTRAINT chk_insurance_salary CHECK (insured_salary > 0),
    CONSTRAINT chk_insurance_dates CHECK (withdraw_date IS NULL OR withdraw_date >= enroll_date)
);

CREATE INDEX idx_insurance_worker ON insurance_records (worker_id, insurance_type);

COMMENT ON TABLE insurance_records IS '加退保紀錄表：每段投保期間一筆，歷史保留；實際申報由合作社於勞保局／健保署辦理';
COMMENT ON COLUMN insurance_records.id IS 'INS-<工人編號>-<險種>-<加保日 yyyyMMdd>';
COMMENT ON COLUMN insurance_records.insurance_type IS '險種：labor 勞保 / employment 就保 / occupational 職保 / health 健保';
COMMENT ON COLUMN insurance_records.insured_salary IS '月投保薪資（健保為投保金額），新台幣元';
COMMENT ON COLUMN insurance_records.withdraw_date IS '退保日：自該日起不再承保；NULL 表示仍在保';
COMMENT ON COLUMN insurance_records.filing_ref IS '勞保局／健保署申報受理編號';

-- ====================================================================
-- 17. 熟手標籤複核紀錄表 (SKILL_REVIEWS)
-- ====================================================================
CREATE TABLE skill_reviews (
    id                 NUMBER(19)     GENERATED BY DEFAULT AS IDENTITY,
    worker_id          VARCHAR2(32 CHAR)   NOT NULL,
    skill_key          VARCHAR2(16 CHAR)   NOT NULL,
    cleared_at         TIMESTAMP      NOT NULL,
    cleared_by         VARCHAR2(32 CHAR),
    note               VARCHAR2(200 CHAR),
    CONSTRAINT pk_skill_reviews PRIMARY KEY (id),
    CONSTRAINT fk_skill_review_worker FOREIGN KEY (worker_id) REFERENCES workers(id),
    CONSTRAINT chk_skill_key CHECK (skill_key IN ('harvest', 'pruning', 'tillage', 'packing'))
);

CREATE INDEX idx_skill_reviews_worker ON skill_reviews (worker_id, skill_key, cleared_at);

COMMENT ON TABLE skill_reviews IS '熟手標籤暫停後的管理員複核紀錄：cleared_at 之前的評價／評核不再觸發暫停';
COMMENT ON COLUMN skill_reviews.skill_key IS '技能：harvest 採收 / pruning 修剪套袋 / tillage 整地定植 / packing 包裝';

-- ====================================================================
-- 【初始種子資料 (SEED DATA)】
-- ====================================================================

-- 1. 作物基準
INSERT INTO crops VALUES ('mango', '愛文芒果 (屏東枋山/台南玉井)', 28, 4, 300, '4月 ~ 7月',
    '外銷級採收 (留蒂 0.5cm 護果粉)、疏果、防菌套袋',
    '屏南冷鏈預冷分級場 (清洗、重量自動分級、外銷裝箱)', SYSTIMESTAMP);

INSERT INTO crops VALUES ('onion', '洋蔥 (屏東恆春/高樹)', 32, 4, 290, '1月 ~ 4月',
    '拔蔥、剪根剪葉、田間裝袋 (每袋 20kg)、搬運上埂',
    '恆春恆農洋蔥低溫冷藏通風庫 (分級篩選、冷藏排板)', SYSTIMESTAMP);

INSERT INTO crops VALUES ('pepper', '設施彩椒/小黃瓜 (彰化/雲林)', 20, 3, 310, '全年設施溫室',
    '整枝修芽、溫室精準分批採摘、防蟲網維護',
    '雲林西螺蔬果集貨冷藏中心 (生鮮小包裝、貼標封口)', SYSTIMESTAMP);

INSERT INTO crops VALUES ('scallion', '三星青蔥 (宜蘭三星)', 24, 3, 300, '6月 ~ 10月',
    '洗蔥、拔蔥、分級束捆、冷藏保鮮預備',
    '三星地區農會洗蔥預冷處理場 (水洗機操作、蔥管套袋)', SYSTIMESTAMP);

INSERT INTO crops VALUES ('pomelo', '文旦柚 (台南麻豆/花蓮鶴岡)', 36, 5, 320, '8月 ~ 9月',
    '白露產季搶收、全區動員、果樹修剪',
    '麻豆文旦產銷集貨場 (甜度光波檢測、辭水儲存箱包裝)', SYSTIMESTAMP);

-- 2. 小農
INSERT INTO farmers VALUES ('F001', '陳建龍 (阿龍班長)', '0912-345-678', '屏東縣枋山鄉加祿段 32 號', '88776655', '產銷班', SYSTIMESTAMP);
INSERT INTO farmers VALUES ('F002', '高樹金牌洋蔥合作社', '0922-333-444', '屏東縣高樹鄉南華路 108 號', '77665544', '契作農企業', SYSTIMESTAMP);

-- 3. 調度工班
INSERT INTO dispatch_teams VALUES ('team_a', '工班 A 組', 5, '阮文雄 (Nguyễn Văn Hùng)', '0988-111-222',
    '智農 1 號車 (RBF-8899)', '張大哥 (0912-111-333)',
    '🌾 高樹洋蔥專區 (第 3 田區)', '洋蔥採收、剪葉裝袋 (每袋 20kg)',
    '🏢 屏南蔬果冷鏈預冷集貨場 (B棟 分級包裝線)', '洋蔥低溫乾燥分級、電腦秤重篩選網袋包裝', 'active_field');

INSERT INTO dispatch_teams VALUES ('team_b', '工班 B 組', 6, '阿迪 (Agus Santoso)', '0988-222-333',
    '智農 2 號車 (RBF-6688)', '李大哥 (0912-222-444)',
    '🌾 枋山愛文芒果 (山坡地外銷供果園)', '套袋疏果、防菌果袋封口、留蒂 0.5cm 採收',
    '🏢 枋寮生鮮冷鏈物流中心 (A棟 清洗預冷場)', '芒果清洗預冷、自動重量分級套網袋、出口外銷箱貼標封箱', 'active_field');

INSERT INTO dispatch_teams VALUES ('team_c', '工班 C 組 (機耕隊)', 4, '陳建宏 (資深農機技師)', '0988-333-444',
    '智農 3 號工程車 (大疆 T50 植保機)', '陳建宏',
    '🌾 雲林設施甜椒園區', '無人機代噴 + 溫室整枝修剪',
    '🏢 西螺農會智慧農業資材維護所', '無人機機隊拆解保養、噴嘴超音波清洗、調配資材準備', 'active_field');

INSERT INTO dispatch_teams VALUES ('team_d', '工班 D 組 (冷鏈常態組)', 5, '范氏紅 (Phạm Thị Hồng)', '0988-444-555',
    '智農專車固定專線', '王大哥 (0912-555-666)',
    '🏢 屏南冷鏈預冷分級場 (室內 A 棟)', '蔬果預冷降溫、外銷精密秤重分級、冷鏈櫃裝櫃',
    '🏢 屏南冷鏈預冷分級場 (室內 A 棟)', '蔬果預冷降溫、外銷精密秤重分級、冷鏈櫃裝櫃', 'hedged_indoor');

-- 4. 派工訂單
INSERT INTO orders (id, farmer_id, farmer_name, phone, farm_location, crop_id, crop_name, area, plan, plan_name, total_hours, suggested_workers, unit_rate, standard_amount, final_amount, deposit_amount, deposit_paid, status, assigned_team_id, created_at) VALUES ('ORD-2026-001', 'F001', '陳建龍 (阿龍班長)', '0912-345-678', '屏東縣枋山鄉加祿段 32 號',
    'mango', '愛文芒果 (屏東枋山)', 6.0, 'early_bird', '產季早鳥工時包 (鎖定名額・優先排班)',
    168, 4, 315, 52920, 52920, 10584, 'Y', 'confirmed', 'team_b', SYSTIMESTAMP);

-- 5. 在籍移工 (20位)
INSERT INTO workers (id, team_id, name, chinese_name, nationality, is_leader, base_salary, agri_bonus, transport_allowance, created_at) VALUES ('W001', 'team_a', 'Nguyễn Văn Hùng', '阮文雄', '越南', 'Y', 29500, 8800, 2400, SYSTIMESTAMP);
INSERT INTO workers (id, team_id, name, chinese_name, nationality, is_leader, base_salary, agri_bonus, transport_allowance, created_at) VALUES ('W002', 'team_a', 'Trần Văn Bình', '陳文平', '越南', 'N', 29500, 8800, 2400, SYSTIMESTAMP);
INSERT INTO workers (id, team_id, name, chinese_name, nationality, is_leader, base_salary, agri_bonus, transport_allowance, created_at) VALUES ('W003', 'team_a', 'Lê Thị Mai', '黎氏梅', '越南', 'N', 29500, 8800, 2400, SYSTIMESTAMP);
INSERT INTO workers (id, team_id, name, chinese_name, nationality, is_leader, base_salary, agri_bonus, transport_allowance, created_at) VALUES ('W004', 'team_a', 'Phạm Văn Đức', '范文德', '越南', 'N', 29500, 8800, 2400, SYSTIMESTAMP);
INSERT INTO workers (id, team_id, name, chinese_name, nationality, is_leader, base_salary, agri_bonus, transport_allowance, created_at) VALUES ('W005', 'team_a', 'Hoàng Minh Tuấn', '黃明俊', '越南', 'N', 29500, 8800, 2400, SYSTIMESTAMP);

INSERT INTO workers (id, team_id, name, chinese_name, nationality, is_leader, base_salary, agri_bonus, transport_allowance, created_at) VALUES ('W006', 'team_b', 'Agus Santoso', '阿迪', '印尼', 'Y', 29500, 8800, 2400, SYSTIMESTAMP);
INSERT INTO workers (id, team_id, name, chinese_name, nationality, is_leader, base_salary, agri_bonus, transport_allowance, created_at) VALUES ('W007', 'team_b', 'Budi Setiawan', '布迪', '印尼', 'N', 29500, 8800, 2400, SYSTIMESTAMP);
INSERT INTO workers (id, team_id, name, chinese_name, nationality, is_leader, base_salary, agri_bonus, transport_allowance, created_at) VALUES ('W008', 'team_b', 'Dewi Lestari', '蒂薇', '印尼', 'N', 29500, 8800, 2400, SYSTIMESTAMP);
INSERT INTO workers (id, team_id, name, chinese_name, nationality, is_leader, base_salary, agri_bonus, transport_allowance, created_at) VALUES ('W009', 'team_b', 'Eko Prasetyo', '艾科', '印尼', 'N', 29500, 8800, 2400, SYSTIMESTAMP);
INSERT INTO workers (id, team_id, name, chinese_name, nationality, is_leader, base_salary, agri_bonus, transport_allowance, created_at) VALUES ('W010', 'team_b', 'Fajar Hidayat', '法賈', '印尼', 'N', 29500, 8800, 2400, SYSTIMESTAMP);
INSERT INTO workers (id, team_id, name, chinese_name, nationality, is_leader, base_salary, agri_bonus, transport_allowance, created_at) VALUES ('W011', 'team_b', 'Gita Pratama', '吉塔', '印尼', 'N', 29500, 8800, 2400, SYSTIMESTAMP);

INSERT INTO workers (id, team_id, name, chinese_name, nationality, is_leader, base_salary, agri_bonus, transport_allowance, created_at) VALUES ('W012', 'team_c', '陳建宏', '陳建宏 (機耕師)', '本國', 'Y', 42000, 8800, 3000, SYSTIMESTAMP);
INSERT INTO workers (id, team_id, name, chinese_name, nationality, is_leader, base_salary, agri_bonus, transport_allowance, created_at) VALUES ('W013', 'team_c', '林志強', '林志強 (無人機手)', '本國', 'N', 42000, 8800, 3000, SYSTIMESTAMP);
INSERT INTO workers (id, team_id, name, chinese_name, nationality, is_leader, base_salary, agri_bonus, transport_allowance, created_at) VALUES ('W014', 'team_c', 'Đặng Văn Lâm', '鄧文林', '越南', 'N', 29500, 8800, 2400, SYSTIMESTAMP);
INSERT INTO workers (id, team_id, name, chinese_name, nationality, is_leader, base_salary, agri_bonus, transport_allowance, created_at) VALUES ('W015', 'team_c', 'Hadi Wijaya', '哈迪', '印尼', 'N', 29500, 8800, 2400, SYSTIMESTAMP);

INSERT INTO workers (id, team_id, name, chinese_name, nationality, is_leader, base_salary, agri_bonus, transport_allowance, created_at) VALUES ('W016', 'team_d', 'Phạm Thị Hồng', '范氏紅', '越南', 'Y', 29500, 8800, 2400, SYSTIMESTAMP);
INSERT INTO workers (id, team_id, name, chinese_name, nationality, is_leader, base_salary, agri_bonus, transport_allowance, created_at) VALUES ('W017', 'team_d', 'Vũ Thị Hương', '武氏香', '越南', 'N', 29500, 8800, 2400, SYSTIMESTAMP);
INSERT INTO workers (id, team_id, name, chinese_name, nationality, is_leader, base_salary, agri_bonus, transport_allowance, created_at) VALUES ('W018', 'team_d', 'Siti Rahma', '西蒂', '印尼', 'N', 29500, 8800, 2400, SYSTIMESTAMP);
INSERT INTO workers (id, team_id, name, chinese_name, nationality, is_leader, base_salary, agri_bonus, transport_allowance, created_at) VALUES ('W019', 'team_d', 'Nurul Aini', '努魯', '印尼', 'N', 29500, 8800, 2400, SYSTIMESTAMP);
INSERT INTO workers (id, team_id, name, chinese_name, nationality, is_leader, base_salary, agri_bonus, transport_allowance, created_at) VALUES ('W020', 'team_d', 'Somchai Prasert', '宋猜', '泰國', 'N', 29500, 8800, 2400, SYSTIMESTAMP);

-- 6. 人才庫
INSERT INTO talents VALUES ('TAL-001', '陳文義', 'local_driver', '本國大型農機/曳引機駕駛員', '0933-222-111',
    '具備 80 馬力曳引機 5 年耕作經驗、水稻插秧整地熟練', 'Y', SYSTIMESTAMP);

-- 7. 天候避險大腦狀態
INSERT INTO weather_states VALUES ('WS-001', 'sunny', '全台晴朗炎熱', 'N', '晴朗 29°C', 'normal',
    '全區田間出勤中・外展農工出勤率 100% (20/20 人在崗)', 62500, 160, NULL, SYSTIMESTAMP);

-- 8. 雙軌財務帳本
INSERT INTO financial_ledger VALUES ('LED-001', 'ORD-2026-001', 52920, 8467, 44453, 8800, SYSTIMESTAMP);  -- 16% 代管費（F2 v2.4）

-- 9. LINE 官方帳號使用者（admin_tech 不使用 LINE）
INSERT INTO line_users (id, channel, line_user_id, username, ref_id, display_name, role, lang, lang_locked, is_bound, bound_at, followed)
    VALUES ('LU-001', 'farmer', 'U1001_FARMER', 'farmer', 'F001', '陳建龍 (阿龍班長)', 'farmer', 'zh', 'N', 'Y', SYSTIMESTAMP, 'Y');
INSERT INTO line_users (id, channel, line_user_id, username, ref_id, display_name, role, lang, lang_locked, is_bound, bound_at, followed)
    VALUES ('LU-002', 'worker', 'U2001_WORKER_VN', 'W001', 'W001', 'Nguyễn Văn Hùng', 'worker_foreign', 'vi', 'N', 'Y', SYSTIMESTAMP, 'Y');
INSERT INTO line_users (id, channel, line_user_id, username, ref_id, display_name, role, lang, lang_locked, is_bound, bound_at, followed)
    VALUES ('LU-003', 'worker', 'U3001_WORKER_TW', 'W012', 'W012', '陳建宏 (機耕師)', 'worker_local', 'zh', 'N', 'Y', SYSTIMESTAMP, 'Y');
INSERT INTO line_users (id, channel, line_user_id, display_name, lang, lang_locked, is_bound, followed)
    VALUES ('LU-004', 'worker', 'U5001_GUEST_ID', 'Siti (未綁定・印尼語介面)', 'id', 'N', 'N', 'Y');

-- 10. LINE 推播歷程
INSERT INTO line_messages VALUES ('LMSG-1726826610001', 'U1001_FARMER', 'SYSTEM_BROADCAST', 'farmer', 'early_bird',
    '📋 智農派工 - 枋山愛文芒果派工確認單 (早鳥工時包・鎖定名額)', SYSTIMESTAMP);
INSERT INTO line_messages VALUES ('LMSG-1726826610002', 'U2001_WORKER_VN', 'SYSTEM_BROADCAST', 'worker_foreign', 'daily_task',
    '📋 Thẻ Nhiệm Vụ Hôm Nay - 06:30 Tập hợp lên xe 1 (张大哥)', SYSTIMESTAMP);

COMMIT;

-- ====================================================================
-- 驗證查詢 (Verification Query)
-- ====================================================================
SELECT 'crops' AS table_name, COUNT(*) AS row_count FROM crops
UNION ALL
SELECT 'farmers', COUNT(*) FROM farmers
UNION ALL
SELECT 'dispatch_teams', COUNT(*) FROM dispatch_teams
UNION ALL
SELECT 'orders', COUNT(*) FROM orders
UNION ALL
SELECT 'workers', COUNT(*) FROM workers
UNION ALL
SELECT 'talents', COUNT(*) FROM talents
UNION ALL
SELECT 'weather_states', COUNT(*) FROM weather_states
UNION ALL
SELECT 'financial_ledger', COUNT(*) FROM financial_ledger
UNION ALL
SELECT 'line_users', COUNT(*) FROM line_users
UNION ALL
SELECT 'line_messages', COUNT(*) FROM line_messages
UNION ALL
SELECT 'team_ratings', COUNT(*) FROM team_ratings
UNION ALL
SELECT 'insurance_records', COUNT(*) FROM insurance_records
UNION ALL
SELECT 'skill_reviews', COUNT(*) FROM skill_reviews
UNION ALL
SELECT 'legalize_workers', COUNT(*) FROM legalize_workers;
