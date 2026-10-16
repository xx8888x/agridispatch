# 智農派工 認證與 API 安全規格（v1，2026-09-23）

前端（demo / web / android）與後端（Spring Boot，port 3000）共同遵守的介面約定。

## 1. 執行模式

| 模式 | 後端設定 | 前端 `js/app-config.js` | 用途 |
|---|---|---|---|
| **demo** | `agri.demo.enabled=true`（`--spring.profiles.active=demo`） | `mode: 'demo'` | 路演展示：免密通行、示範帳號、可重灌資料 |
| **production** | `agri.demo.enabled=false`（預設） | `mode: 'production'` | 正式版：真實帳密、Token 驗證、無後門 |

- `Software/demo/` 與 `Software/android/app/src/main/assets/www/` 為 demo 模式；`Software/web/` 為 production 模式。
- 前端唯一的模式差異來源是 `js/app-config.js`（每份副本各自一份，同步腳本不覆蓋）。
- `js/demo-accounts.js`（示範帳號清單）只存在 demo 與 android，**不同步到 web**。

## 2. Token

- 格式：`base64url(payloadJSON) + "." + base64url(HMAC-SHA256(secret, payloadPart))`
- payload：`{"sub":"<username>","role":"<role>","ref":"<refId>","demo":false,"ver":<tokenVersion>,"exp":<epochSeconds>}`，有效期 12 小時。
- `ver`＝簽發時帳號（`users` 或 `workers`）的 `token_version`；每次請求與資料庫比對，不同或帳號已不存在即視為無效 token（401），見第 5 節「舊 token 失效」。舊 token 無 `ver` 視為 0。
- 密鑰：環境變數 `AGRI_TOKEN_SECRET`（或 `agri.security.token-secret`）；未設定時每次啟動隨機產生並在 log 警告（重啟後舊 token 失效）。
- 前端存放：`sessionStorage.agri_token`、`sessionStorage.agri_user`（JSON）。
- 呼叫 API 一律帶 `Authorization: Bearer <token>`（由 `js/api-client.js` 自動加上）。

## 3. 角色

`admin_tech`（科技公司管理員）、`coop_admin`（合作社管理員，2026-10-15 起，詳見規格 11 與下方「後台人員」）、`farmer`（農場主）、`worker_foreign`（外展移工）、`worker_local`（本地工 / 領班 / 機耕 / 勞務員，`subRole` 區分）。

各角色的功能權限一覽見 `13_各角色權限總表.md`。

## 4. 認證端點

| Method | Path | 權限 | 說明 |
|---|---|---|---|
| POST | `/api/auth/login` | 公開 | body `{identifier, password}` → 200 `{success:true, token, user}`；失敗 401 `{success:false, error:"帳號或密碼錯誤"}`（**不得**提示預設密碼）；鎖定中 429（見第 5 節「登入次數限制」） |
| POST | `/api/auth/register` | 公開 | 農場主自助註冊（2026-10-08 起）：body `{name, phone, password, address, pdpaConsent:true, farmName?, farmType?, refCode?, refChannel?}` → 200 同 login 回應（註冊即登入）；`refCode`（邀請連結的推薦碼）有效時另記一筆推薦紀錄，碼無效或自己邀請自己不影響註冊。帳號＝手機號碼純數字（`09` 開頭 10 碼，輸入可含 `-`／空白），建立 `farmers`（編號 `F###`，與既有 farmer 帳號 refId 不重複）與 `users`（role `farmer`）。姓名 50 字、密碼 8～128 字元、農地地址必填 255 字、農場名稱 100 字；未同意個資蒐集 400；手機已註冊 409；同一來源 IP 每小時 10 次（`agri.security.register-ip-max`／`register-window-minutes`），超過 429。工人不開放自助註冊（須合作社查驗證件後建檔）。個資告知文字【待確認：律師審閱】 |
| POST | `/api/auth/demo-login` | 公開，**僅 demo 模式** | body `{role, refId?}` → 同 login 回應，`user.demo=true`；非 demo 模式回 404 |
| GET | `/api/auth/me` | 需登入 | 200 `{success:true, user}` |
| POST | `/api/auth/change-password` | 需登入（本人） | body `{oldPassword, newPassword}`；新密碼 8～128 字元，原密碼錯 400；成功後 token 版本 +1，本帳號所有 token（含本次）立即失效，前端清除登入狀態並請使用者以新密碼重新登入（「我的帳號」頁 `account.html`）。demo 免密 token 前端不提供此功能 |
| POST | `/api/auth/logout-all` | 需登入（本人） | 登出所有裝置（2026-10-13 起）：token 版本 +1（本帳號所有 token 含本次立即失效），並刪除本帳號（`username`）的 App 推播裝置（`push_tokens`），回 `{success, pushDevicesRemoved}`；密碼不變。demo 免密 token 不比對版本，回 400 |
| POST | `/api/auth/reset-password` | 僅 admin_tech | body `{identifier, newPassword}`：替使用者設定臨時密碼（忘記密碼時，合作社以電話或當面核對身分後操作，後台「帳號與密碼管理」`TechBackend/accounts.html`）；`identifier` 為 users 帳號或農工工號；該帳號 token 版本 +1。系統沒有簡訊／Email 自助重設 |
| GET | `/api/auth/accounts` | 僅 admin_tech | 帳號清單（2026-10-13 起）：`[{identifier, name, role, refId, roleDesc, teamId?, kind: user/worker, hasPassword}]`，**不含**密碼雜湊與證號 |
| POST／GET／DELETE | `/api/account/deletion-request` | 需登入（本人，admin_tech 除外） | 刪除帳號申請（2026-10-15 起，Google Play 規定）：POST `{reason?}` 申請（同時只能一筆待處理，重複 409）、GET 本人最近一筆、DELETE 撤回待處理。回應不含後台欄位 |
| POST | `/api/public/account-deletion` | 公開 | 免登入網頁申請 `{name, phone, account?, reason?}`：同一 IP 每小時 5 次（429）；不查帳號是否存在、回應一律相同 |
| GET／POST | `/api/account-deletions`、`/{id}/complete`、`/{id}/reject` | 僅 admin_tech | 申請清單（待處理附未完成訂單等提醒，網頁申請附 `suggestedAccount`）；complete `{identifier?（網頁申請必填）, note?}`、reject `{note}`（必填）。完成時的處理見 ERD 第參章第 17 點 |
| GET | `/api/referrals/me` | farmer／worker_local／worker_foreign | 推薦裂變（2026-10-15 起）：本人推薦碼（首次呼叫時產生，8 碼大寫英數、去掉 I O 0 1）、成效 `stats{opens, farmersRegistered, workerLeads, workersOnboarded}` 與被邀請者清單（姓名遮罩如「王○明」、不含電話與合作社備註）。後台帳號 403 |
| GET | `/api/public/referral/{code}` | 公開 | 開啟邀請連結：連結開啟數 +1，回 `{valid, inviterName（遮罩）, inviterRole}`；碼無效回 `valid:false`（200） |
| POST | `/api/public/referral/lead` | 公開 | 工人意願登記 `{code, name, phone, pdpaConsent:true, channel?}`：工人不能自助註冊，登記後由合作社聯絡查驗證件。同一 IP 每小時 10 次（429）；同碼同電話重複送出視為同一筆；用自己的電話登記 400。個資告知文字【待確認：律師審閱】 |
| GET／POST | `/api/referrals`、`/api/referrals/{id}/status` | admin_tech／coop_admin | 全部推薦紀錄（含被邀請者電話）；更新工人意願登記狀態 `{status: pending/contacted/onboarded/closed, note?}`。農場主註冊的紀錄不需跟進（400） |
| GET | `/api/auth/quick-profiles` | **僅 demo 模式** | 示範帳號清單，**不含**密碼、居留證號、護照號；非 demo 模式回 404 |
| GET | `/api/public/config` | 公開 | `{success:true, demoEnabled, paymentProvider}` |

`user` 物件欄位：`username, name, role, refId, roleDesc, subRole?, demo?, workerProfile?`（workerProfile 內證件號碼遮罩）。

## 5. 授權規則

- **公開（不需 token）**：`POST /api/auth/login`、`POST /api/auth/register`、`POST /api/public/account-deletion`（刪除帳號網頁申請，限次）、`POST /api/public/referral/lead`（邀請連結的工人意願登記，限次）、`POST /api/auth/demo-login`、`GET /api/public/**`、`GET /api/crops/**`、`GET /api/calendar/**`、`GET /api/brochures/**`、`GET /api/legalize/types`（黑轉白身分類型與法規參考文字）、`GET /api/payments/methods`、`GET /api/orders/categories`、`POST /api/orders/calculate`、`POST /api/talents/register`、`GET /api/dispatch/overview`、`GET /api/dispatch/weather-state`、`POST /api/payments/ecpay/notify`（以 CheckMacValue 驗證）、`POST /api/line/webhook`（已停用，一律回 410，見下方）、`POST /api/line/internal/**`（LINE Bot 內部端點，以 `X-Internal-Key` 驗證，見下方「LINE 官方帳號」）。
- **需登入**：其餘 `/api/**`。
- **僅 admin_tech**：crops / brochures / talents 的新增修改刪除、`/api/dispatch/*` 的 POST/PUT/DELETE（含 `POST /api/dispatch/teams` 新增工班、`DELETE /api/dispatch/teams/{id}` 刪除工班）、`GET /api/line/config`（原 `POST` 執行中改金鑰已於 2026-10-13 移除，金鑰只由環境變數／LINE Bot `config.properties` 提供）、`GET /api/line/bindings`、`DELETE /api/line/bindings/{id}`、`/api/notifications/send`、`PUT /api/orders/{id}/status`、代建與修改訂單、`/api/account-deletions/**`（刪除帳號申請審核，Controller 另以 `requireAdmin` 檢查）、`/api/dev/**`；工人證件完整號碼（`GET /api/workers/{id}`）。
- **後台人員 admin_tech 或 coop_admin（2026-10-15 起，`AuthContext.isStaff()`；AuthInterceptor 的 `STAFF_ONLY`，其餘 Controller／Service 以 `requireStaff()`、`isStaff()` 檢查）**：雇主責任範圍——`GET/POST/PUT/DELETE /api/workers`（工人名冊）、`/api/payroll/**`（出勤補登／修正、月結算）、`/api/insurance/**` 的管理操作、`/api/leaves/**` 審核、`/api/matching/**`、`/api/rosters` 開班／確認／取消、`/api/skills` 複核恢復、領班評核、尾款登錄與發票（`/api/payments/*/balance`、`transactions/*/invoice|void`）、`GET /api/legalize/summary`、`POST /api/auth/reset-password`、`GET /api/auth/accounts`；另可唯讀檢視全部訂單、共享班表（含認領農戶）、工班完整資料與營收。`/api/workers/{id}/**` 的「本人或 admin」亦涵蓋後台人員。**合作社的限制**：只能重設農場主與工人的密碼（重設 `admin_tech`／`coop_admin` 回 403）、帳號清單不含後台帳號、證件號碼維持遮罩、不能做上一條「僅 admin_tech」的項目。合作社的 demo token 不視同 admin（`isAdmin()` 排除 `coop_admin`），示範時看得到限制。登入失敗鎖定的測試期開關（`login-lock-admin-only`）一併涵蓋 `coop_admin`。測試：`CoopRoleTest`。
- **LINE 官方帳號（農場主／工作者雙帳號，規格 `05_LINE官方帳號拆分規格.md` 第伍、陸章）**：
  - 內部端點 `POST /api/line/internal/identity`、`/follow-state`、`/query` 只給 LINE Bot 呼叫：`X-Internal-Key` 須與 `AGRI_LINEBOT_INTERNAL_KEY` 相同（constant-time 比對），**未設定金鑰時整組回 403**。`query` 依帳號與身分限制：農場主帳號 `orders` 限已綁定且只回本人訂單（`orders.farmerId == line_users.ref_id`）、`rates`／`calendar` 公開；工作者帳號目前一律回空清單。不回工班名冊。
  - `POST /api/line/bind`（需登入）`{channel, idToken}`：LINE userId **只取自後端向 LINE 驗證過的 ID token**（`LINE_LOGIN_CHANNEL_ID`）；未設定時 production 回 503，demo 模式接受 `mock:U+32位十六進位` 假 token。身分須與帳號相符（farmer↔`farmer`、worker↔`worker_*`），否則 409；同一個 LINE userId 或同一人（同身分同 refId）在同一帳號只能有一筆綁定，重複 409。
  - `DELETE /api/line/bind/{channel}`：本人解除；`GET /api/line/bindings`、`DELETE /api/line/bindings/{id}`：僅 admin，清單的 LINE userId 只顯示前 6 碼。
  - `/api/line/notify/**`（僅 admin）：`GET status`（工作者帳號推播時段）、`POST tomorrow`（立即發送明日出工通知）、`POST farmer-broadcast`（農場主帳號方案通知；工作者帳號不做 broadcast）。訂單狀態、定金、天候警戒的推播由原本的操作自動觸發，只推給已綁定且未封鎖的本人／出工名單。
  - `GET /api/line/config`（僅 admin）另回 `internalKeyConfigured`、`loginChannelConfigured` 與 `linebot`（後端讀 LINE Bot 公開 `/health`：兩帳號模式、選單是否已上傳、加好友按鈕與共用金鑰是否設定；只有旗標，不含任何金鑰值）。
- **本人或 admin_tech**：`/api/workers/{id}/**`、`/api/attendance/*/{workerId}`、打卡（worker 只能替自己打卡）。
- **工人本人排班 `GET /api/workers/{id}/schedule`（2026-10-12 起，工人行事曆用）**：沿用上一條「本人或 admin_tech」。回傳排給該工人的訂單——訂單有出工名單（`crewWorkerIds`）時只看名單是否有本人，不論訂單掛哪個工班；沒有名單時看 `assignedTeamId` 是否為本人工班；排除已取消與未排工期。每筆只含現場欄位（工期、狀態、作物、面積、派工類別、地點、農場名稱、帶隊對口與電話、交辦事項、車輛、司機、遇雨改派），**不含金額、定金、方案與農場主電話**，demo 模式也一樣。可帶 `from`、`to`（YYYY-MM-DD，取與區間重疊的工作；格式錯、`to` 早於 `from`、區間超過 366 天回 400）。工人端的行事曆與今日任務卡（含近期紀錄、本週班表）都用這支，不讀 `GET /api/orders`；本國領班頁的組員名冊改讀 `GET /api/workers/{id}/team-members`（2026-10-12 起；同樣本人或 admin_tech）：只回同工班其他成員的工號、姓名、中文名、國籍、身分別 `tier`、工作型態 `employmentType` 與是否領班，不含證號、電話、薪資與保險資料；讀不到時名冊沿用示範文字，任務卡照常顯示。工人端不再讀 `GET /api/workers`（完整名冊僅 admin）。
- **farmer**：只能查詢 / 付款自己的訂單（`order.farmerId == token.ref`；舊資料無 farmerId 時 demo 模式放行）。建立訂單時傳入的 `assignedTeamId` 一律忽略，工班由系統建議或 admin 指派。
- **worker（2026-10-08 起）**：`GET /api/orders`、`GET /api/orders/{id}`（及沿用同一檢查的 `GET /api/ratings/orders/{id}`）只限自己工班承接（`order.assignedTeamId == 工人的 teamId`）或出工名單有自己的訂單，其餘 403；demo 模式維持全部可見（路演本勞工作台一頁呈現三種身分）。
- **公開端點的資料範圍（2026-10-08 起）**：`GET /api/dispatch/overview` 未登入或非 admin 不回 `securedRevenue`，工班只回 id、名稱、人數、駐點與避險地點、狀態（不含領班、領班電話、車輛、司機）；需登入的 `GET /api/dispatch/teams`、`/teams/{id}` 自 2026-10-12 起對非 admin 也只回同樣的公開欄位；`GET /api/brochures?all=true`（含未上架草稿）只對 admin 生效。
- **天候戰情氣象（2026-10-12 起）**：`GET /api/weather/towns[?force=true]`（需登入，任一角色）由後端代理中央氣象署開放資料：鄉鎮天氣預報（F-D0047-093，22 縣市分 5 批）、一週預報的紫外線指數、自動氣象站陣風（O-A0001-001）與雨量站雨量（O-A0002-001，依測站所屬鄉鎮取最大值，鄉鎮內無站取 10 公里內最近站），授權碼只放環境變數 `CWA_API_KEY`、不回傳前端也不寫入日誌；未設定時回 503，上游失敗時沿用上一份快取並標 `stale`，完全沒有資料時回 502。快取 `AGRI_WEATHER_CACHE_MINUTES`（預設 30 分鐘），`force=true` 仍受 1 分鐘最短間隔限制以保護配額。前端 demo／純前端 Mock 在 503 或離線時改用 Open-Meteo（限非商業，production 模式不使用）。
- **其他登入端點**：`GET /api/notifications` 非 admin 只回全體公告與 `target` 為自己（`token.ref`）的通知，忽略 `target` 參數；`POST /api/line/simulate-push`（LINE 體驗頁）正式模式僅限 admin。
- **金流通知防少付**：綠界通知除了比對該交易金額，還須等於訂單**目前**的定金、訂單未取消且尚未付款；否則交易記為已付（款項已入帳，回 `1|OK` 避免重送）但訂單不標已付定金，記 log 待人工退款或補收。mock 金流在交易金額與目前定金不同時回 409。
- **尾款收款登錄（2026-10-13 起，僅 admin）**：尾款由合作社另行收款（轉帳、現金等），收到後由後台登錄。`POST /api/payments/{orderId}/balance` body `{amount?, paidDate?, paymentMethod?, invoiceNo?, note?}`：訂單須已完工（否則 409）；`amount` 省略＝全部未結清尾款，可分次登錄但不得超過未結清金額（400），已結清再登錄回 409；`paidDate`（yyyy-MM-dd，預設今天）不可晚於今天；`paymentMethod` 限 `bank_transfer`／`cash`／`check`／`other`；`invoiceNo` 可稍後補登，格式 2 碼英文＋8 碼數字（存成 `AB-12345678`）。建立 `kind = balance`、`provider = offline`、`status = PAID` 的交易。`PUT /api/payments/transactions/{txId}/invoice` `{invoiceNo}` 補登／更正已付款交易（定金或尾款）的發票號碼，非已付款回 409。`POST /api/payments/transactions/{txId}/void` `{reason}` 作廢尾款登錄（保留紀錄、`status = VOID`、原因寫入 `note`、不計入已收）；定金交易不可作廢（409）。`GET /api/payments/{orderId}/status`（擁有者或 admin）另回 `depositCredit`（已付定金：訂單 `depositPaid` 時為 `depositAmount`）、`balancePaid`（已登錄且未作廢的尾款）、`balanceOutstanding`（＝總額 − 已付定金 − 已收尾款，最低 0）；`transactions` 每筆含 `kind`（舊資料視同 `deposit`）與 `note`。定金的冪等回應（已付款再結帳）只取定金交易，不會回尾款登錄。
- **輸入長度**：訂單農場主姓名／現場對口姓名 100 字、電話 30 字、農地位置／現場交辦 255 字，人才報名經歷 255 字、電話 30 字，超過回 400；其餘資料庫約束錯誤（`DataIntegrityViolationException`）統一回 400，不回 500。
- **我的農場 `/api/farmers/me/farms`（2026-10-12 起，預約派工第一步，可登錄多個）**：僅 farmer（其餘角色含 admin 一律 403），只能讀寫自己的農場（`farms.farmer_id = token.ref`）。`GET` 回本人農場清單 `[{id, farmName, contactName, phone, address, farmType}]`（依登錄先後；清單為空且有自助註冊時的 `farmers` 資料，自動轉成第一個農場；種子帳號第一次預約前登錄）；`POST` 新增（每帳號最多 20 個，超過 409）；`PUT /{id}` 修改、`DELETE /{id}` 刪除（不是自己的農場一律 404，不透露是否存在；刪除不影響既有訂單）。必填：農場名稱 100 字、現場聯絡人 50 字、地址／地號 255 字，電話去掉 `-`／空白後須為 `0` 開頭 9～10 碼。前台 farmer.html 有農場就直接顯示預約單並預選上次用的農場，訂單的聯絡人、電話、預設作業地點由所選農場帶入。訂單記錄所選農場：`POST /api/orders` 可帶 `farmId`（選填；須為下單農場主自己的農場，否則 400；admin 代建且未給 `farmerId` 時以農場的主人為準），後端存 `farmId` 與下單當時的農場名稱快照 `farmName`（前端傳的 `farmName` 不採用；農場之後改名或刪除，訂單仍顯示當時名稱）；`PUT /api/orders/{id}` 不能改農場。schema：`farms` 表、`orders.farm_id`／`farm_name`（既有資料庫以 `Software/database/migrations/2026-10-12_farms.sql`、`2026-10-12b_orders_farm.sql` 補建）。
- **指名回頭工人 `GET /api/farmers/me/past-workers?start=&end=`（2026-10-12 起）**：僅 farmer（其餘角色含 admin 一律 403）。只回「曾到本人訂單出工」的工人（本人未取消訂單有出勤紀錄，或已完工訂單出工名單有他；外籍移工下架時不列），欄位限 `workerId、name、leader、teamId、teamName、timesWorked、lastWorkedOn`，給工期時加 `available`／`unavailableReason`（只說「該期間已有排班」或「暫時無法排班」，不透露其他農場訂單、保險或證件狀況）。`POST /api/orders` 可帶 `preferredWorkerId`：farmer 限上述名單內的工人（否則 400）、須有工期（400）、該工期未被佔用且可派工（409）；admin 代建不限合作過的工人。`preferredWorkerName` 由後端帶入、前端傳入一律忽略；建立後不可修改。未排出工名單的指名訂單視同佔用指名工人該工期；媒合時指名工人排在出工名單第一位，不合格時回 `preferredWarning` 由調度改派。指名不加價（指名費待 F2 財務確認）。
- **半日時段 `timeSlot`（2026-10-12 起）**：`POST /api/orders/calculate`、`POST/PUT /api/orders` 可帶 `timeSlot`＝`full_day`（預設，舊資料 NULL 視同整天）／`morning`（上午 07:00–11:00）／`afternoon`（下午 13:00–17:00），其他值 400；工時包（`packageId`）只限整天（400）。半日每人每天 4 小時，天數＝作業工時 ÷（人數 × 4）無條件進位，**最低計費每人每天 4 小時**：計費工時 `totalHours`＝人數 × 天數 × 4，試算另回 `workHours`（作業工時）與 `minimumTopUpHours`（補足時數）【待確認：F2 尚未納入半日最低計費情境】。撞期（派工媒合、指名工人、`past-workers?timeSlot=`）須工期重疊且時段重疊：上午與下午不衝突，整天與任一時段衝突。時段變更規則同工期（農場主只能在待付定金時改，已排定須 admin；已付定金 409），變更後重算金額。打卡在同一天有上午、下午兩張單時，依打卡時間（12:00 前算上午）連到時段相符的訂單。兩個農場間的移動時間是否計入工時、交通由誰負擔【待確認】。
- **社區共享班表 `/api/rosters`（2026-10-12 起）**：皆需登入，工人角色一律 403。`GET /api/rosters`、`GET /api/rosters/{id}`：admin 看全部（含固定出工名單、各時段認領農戶與訂單編號），farmer 只看開放認領／已開班的班表，時段格只標 `free`／`taken`／`mine`／`closed`，別人認領的格不回農戶姓名與訂單編號。`POST /api/rosters`（admin）開班：名單須為該工班成員、目前開放派工、工作許可有效、完整加保，且整段期間沒有其他訂單或班表（409）；期間最長 31 天、開工日須在今天之後、截止日須早於開工日（預設開工前 3 天）、門檻 1～100%（預設 60%）、不接受防颱搶收（400）。`POST /api/rosters/{id}/claims`（僅 farmer）認領 `morning`／`afternoon`／`full_day`（整天佔兩格），最早明天、須在期間內（400），開放中須在截止日前、時段未被認領（409）；每次認領成立一張訂單（`orders.shared_roster_id`），工班與名單沿用班表，金額＝名單人數 × 時段工時 × 方案時薪（早鳥 315／20% 定金、彈性 310／免定金；成果統包 400），拼團不打折。班表訂單不可改作物、面積、方案、人數、工期、時段或工班（409），只能改聯絡資訊與現場交辦；退出時段以 `DELETE /api/orders/{id}` 取消。`POST /{id}/confirm`（admin）須已達門檻（409）；`POST /{id}/cancel`（admin）取消班表與未施工的認領訂單（有施工中訂單 409），回傳待退定金筆數【待確認：退款流程】。每天 00:05 排程：過截止日的開放班表達標自動開班、未達標自動取消；已開班且期間已過改為已結束。開放／已開班的班表整段期間佔用名單（撞期檢查同派工媒合），只有該班表自己的認領訂單不受影響。
- **工班評價 `/api/ratings`**（皆需登入，權限於 Controller/Service 檢查）：`PUT /orders/{orderId}` 限訂單擁有者或 admin，且訂單須為 `in_progress`／`completed`（否則 409）；`GET /orders/{orderId}` 權限同訂單讀取；`GET /teams`、`/teams/{teamId}`、`/tags` 任何登入者。回應不含評分者編號，訂單編號只回給 admin。
- **工人請假（2026-10-13 起）**：`GET /api/leaves/types` 任何登入者（12 種假別）。`GET`／`POST /api/workers/{id}/leaves` 本人或 admin（沿用 `/api/workers/{id}/**` 規則）：申請 `{leaveType, startDate, endDate?, timeSlot?, reason?}`，單次最多 31 天、開始日最早 30 天前（補請）、半天假（`morning`／`afternoon`）限單日、與本人待審核或已核准的假重疊 409。`GET /api/leaves?status=`、`POST /api/leaves/{id}/approve`、`POST /api/leaves/{id}/reject`（須填 `note`）僅 admin（AuthInterceptor）；`POST /api/leaves/{id}/cancel` 本人或 admin：待審核皆可取消，已核准本人只能在開始日前取消，admin 隨時可取消。已核准的假在派工媒合視同撞期（出工名單、指名、共享班表一併排除，半天假只佔該時段）；申請與核准回傳 `conflicts`（本人已排入的重疊訂單／班表），由合作社改派，系統不自動移出名單。`GET /api/workers/{id}/schedule` 另回本人待審核與已核准的 `leaves`。通知：工人本人申請 → App 推播給 admin_tech；核准、駁回、admin 取消已核准的假 → LINE 工作者帳號 `leave_decided` 與 App 推播給工人本人。假別給薪與額度【待確認：勞基法、勞工請假規則、性平法】，系統只記錄。
- **出勤補登／修正與月結算（2026-10-13 起）**：`/api/payroll/**` 一律僅 admin（AuthInterceptor 的 ADMIN_ONLY，`PayrollController` 另以 `requireAdmin` 再檢查）。`GET /api/payroll/months`（本月與前 11 個月狀態）、`GET /api/payroll/months/{YYYY-MM}`（總覽：待修正出勤、每位工人工時與薪資）、`POST /api/payroll/months/{YYYY-MM}/settle`（結算：只能已過完的月份、有未下班出勤 409）、`POST /api/payroll/months/{YYYY-MM}/reopen` `{reason}`（必填）；`GET /api/payroll/attendance?month=&workerId=`、`POST /api/payroll/attendance` 補登 `{workerId, workDate, clockIn, clockOut, orderId?, reason}`、`PUT /api/payroll/attendance/{id}` 修正 `{clockIn?, clockOut?, reason}`、`GET /api/payroll/attendance/{id}/corrections`（歷程）。已結算月份的出勤補登、修正回 409；每次補登、修正留操作人（token sub）與原因。規則見 `02_資料庫實體關聯圖與資料字典(ERD).md` 第參章第 15 點。
- **保險登錄 `/api/insurance`**（皆需登入，權限於 Controller/Service 檢查）：`GET /workers`、`POST /workers/{id}/enroll`、`POST /records/{id}/withdraw` 僅 admin；`GET /workers/{id}` 本人或 admin；`GET /teams/{teamId}` 任何登入者，但缺保名單 `gaps` 只回給 admin。
- **合法用工證明**：`GET /api/certificates/orders/{orderId}` 限訂單擁有者或 admin，依該訂單出勤逐日核對工作許可效期與加保，全部符合才附查驗碼；`GET /api/public/certificates/{certificateNo}?code=` 公開查驗（屬 `/api/public/**`），只回摘要、不含工人姓名。查驗碼為 `AGRI_TOKEN_SECRET` 簽出的 HMAC，未固定密鑰時重啟即改變。
- **工班合法化（黑轉白）`/api/legalize`**：`GET /types` 公開；其餘皆需登入——`GET /roster` 農場主只回自己的清冊（`?farmerId=` 對農場主無效），admin 回全部並可篩選；`POST /roster`、`PUT /roster/{id}`、`DELETE /roster/{id}` 限清冊所屬農場主或 admin（admin 新增須帶 `farmerId`），工人角色一律 403；`GET /summary`（各農場進度、補位缺口、罰鍰暴露）僅 admin（AuthInterceptor）；`POST /roster/reset` 僅 demo 模式（非 demo 回 404）。借用／失聯移工類型不接受也不回傳姓名。
- **熟手標籤 `/api/skills`**（皆需登入）：`GET /workers` 與 `POST /workers/{id}/{skill}/restore`（暫停複核恢復）僅 admin；`GET /workers/{id}` 本人或 admin；`GET /teams/{teamId}` 任何登入者，只回人數不列姓名。規則常數集中於 `SkillService`。
- **領班逐人評核 `/api/evaluations`**（皆需登入）：`GET /teams/{teamId}?date=`、`PUT /attendance/{id}`（`rating`＝excellent／ok／needs_improvement）限 admin 或「與受評者同工班且 isLeader 的工人」；不可評自己、須已下班簽核、領班只能評 7 天內班次。評核只用於技能標籤與媒合，不得作為扣薪或解僱依據【待確認】。
- **派工媒合 `/api/matching/orders/{orderId}`**（僅 admin）：`GET` 工班排序與出工名單建議、`GET /crew?teamId=` 指定工班名單、`PUT {teamId, crew:[工號]}` 確認派工（名單須為該工班成員且工作許可有效、完整加保，否則 400／409；已完工或取消的訂單 409）。出工名單只能由此設定，`POST/PUT /api/orders` 不接受 `crewWorkerIds`。訂單有出工名單時，轉 `in_progress` 的加保檢查與打卡自動連結皆以名單為準。
- **訂單工期**：`POST/PUT /api/orders` 可帶 `startDate`、`endDate`（yyyy-MM-dd，須同時提供、完工不早於開工、最長一年，否則 400）；已完工或取消的訂單不可改工期（409）；農場主只能在「待付定金」時改，已排定後須由 admin 調整（409）。派工媒合會排除已排入「工期重疊」訂單出工名單的工人；打卡自動連結在出工名單無法判定時，改取今天落在工期內的施工中訂單。
- **打卡連結訂單**：`POST /api/workers/{id}/clock-in`、`POST /api/attendance/clock-in` 可帶 `orderId`（須為施工中且工人屬該訂單工班，否則 409／400）；未帶時，工人所屬工班恰有一張施工中訂單才自動連結。
- **派工前加保檢查**：`PUT /api/orders/{id}/status` 轉為 `in_progress` 時，已分派工班的每位成員須於當日完整加保（本國：勞保／就保／職保／健保；外籍：勞保／職保／健保【待確認】），否則 409 並列出缺保人員。
- **登入次數限制（2026-10-08 起）**：`POST /api/auth/login` 同一帳號識別碼（不分大小寫）15 分鐘內失敗 5 次、或同一來源 IP 15 分鐘內失敗 30 次 → 之後 15 分鐘一律回 429 `{success:false, error:"嘗試次數過多，請 15 分鐘後再試"}`（密碼正確也一樣）。檢查在 PBKDF2 之前，鎖定期間不驗密碼、不查資料庫；登入成功清除該帳號計數（IP 計數不清）。記憶體實作（單一實例，重啟歸零），門檻可用 `agri.security.login-max-failures`／`login-ip-max-failures`／`login-window-minutes`／`login-lock-minutes` 覆寫。來源 IP：後端預設 `server.forward-headers-strategy=native`（2026-10-13 起），經本機或內網反向代理（Nginx）時採用 `X-Forwarded-For` 中最右邊的非代理位址，直接對外連線時偽造的標頭無效（`ForwardedClientIpTest`）；代理須以內網 IP 轉送，見 `09_正式環境部署規劃(Oracle Cloud).md`。
- **舊 token 失效（2026-10-08 起）**：`users`、`workers` 各有 `token_version`（預設 0）。本人改密碼（`/api/auth/change-password`，含當次使用的 token）、本人登出所有裝置（`/api/auth/logout-all`）、admin 重設密碼（`/api/auth/reset-password`）、變更角色（`users.role`；農工 `tier` 改變）時 +1，該帳號已簽發的 token 立即 401；帳號刪除後查無版本，其 token 同樣 401。demo-login 簽發的 demo token 沿用原規則（僅 demo 模式有效，不比對版本）。
- **舊 LINE webhook 停用**：`POST /api/line/webhook` 一律回 410 `{success:false, error:"GONE", message:"此端點已停用，LINE webhook 改由 LINE Bot 服務（/callback/farmer、/callback/worker）處理"}`，不讀取 body、不處理訊息、不查訂單。LINE Bot 需要後端資料時呼叫 `/api/line/internal/*`。
- **工班新增／刪除（2026-10-12 起）**：`POST /api/dispatch/teams` 名稱、領班、領班電話必填（各限 50／50／30 字），`id` 未給時自動編 `team_01`、`team_02`…，給了只能用英數字、底線、連字號（最多 32 字），重複回 409；車輛、司機、晴雨天地點與任務未填存「待安排」（Oracle 為 NOT NULL）；`status` 只能是 `active_field`／`hedged_indoor`／`standby`，預設 `active_field`。`DELETE /api/dispatch/teams/{id}` 仍有工人、訂單（`assigned_team_id`）或評價掛在該工班時回 409，不存在回 404。
- **管理端 PUT 為部分更新**：`PUT /api/crops/{id}`、`/api/dispatch/teams/{id}`、`/api/talents/{id}`、`/api/brochures/{id}`（及原本就是部分更新的 `/api/workers/{id}`、`/api/orders/{id}`）只改請求中有帶且非 null 的欄位，沒帶的欄位（含數字、布林）維持原值；body 內的 `id` 不改主鍵，人才 `registeredAt` 不可改。名稱類必填欄位（作物／工班 `name`、人才 `name`、文宣 `title`）明確傳空字串回 400。前端送完整物件時行為不變。
- `/api/dev/**` 另需 demo 模式，否則 404。
- 未帶或無效 token → 401 `{success:false, error:"UNAUTHORIZED"}`；權限不足 → 403 `{success:false, error:"FORBIDDEN"}`。
- demo token（`demo:true`）在 demo 模式下視同 admin_tech 權限，方便路演切換角色；production 模式一律拒絕 demo token。

## 6. 個資遮罩

API 回傳的 `arcNumber`、`passportNumber` 一律遮罩為前 2 碼 + `****` + 末 2 碼（例：`AD****51`）；只有 admin_tech 呼叫 `GET /api/workers/{id}` 取得完整值。

## 7. 前端行為（`js/api-client.js`）

- 包裝 `window.fetch`：對同源 `/api/` 或 `API_BASE` 開頭的請求自動加 `Authorization`。
- demo 模式：若無 token，先呼叫 `/api/auth/demo-login`（依頁面角色）取得 token；後端離線時維持現有離線假資料 fallback。
- production 模式：收到 401 → 清除 session 並導向 `login.html`；角色頁面載入時若無 token 或角色不符 → 導向對應登入頁。

## 8. 其他安全設定

- H2 console：預設關閉；demo 模式開啟但 `web-allow-others: false`（僅本機）。
- CORS：只允許 `agri.cors.allowed-origins`（環境變數 `AGRI_CORS_ALLOWED_ORIGINS`；預設 `http://localhost:3000,http://localhost:5000,http://127.0.0.1:3000,https://appassets.androidplatform.net`）。`https://appassets.androidplatform.net` 是 Android App 以 WebViewAssetLoader 載入內建網頁的固定來源（見 06 Android 規格第陸章）；原本 `file:///android_asset/` 的來源是 `null`，一律被擋。來源為 `null` 或不在名單內的預檢請求回 403（2026-10-08 模擬器實測）。
- 密碼以 PBKDF2-HMAC-SHA256（310,000 次、16 bytes salt）雜湊儲存，格式 `pbkdf2$<iter>$<saltB64>$<hashB64>`；啟動時自動把舊明碼升級為雜湊。
- 管理員初始密碼：環境變數 `AGRI_ADMIN_PASSWORD`；demo 模式沿用示範密碼；兩者皆無時隨機產生並只在啟動 log 顯示一次。
