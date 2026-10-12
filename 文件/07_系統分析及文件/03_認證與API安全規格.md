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

`admin_tech`（科技公司管理員）、`farmer`（農場主）、`worker_foreign`（外展移工）、`worker_local`（本地工 / 領班 / 機耕 / 勞務員，`subRole` 區分）。

## 4. 認證端點

| Method | Path | 權限 | 說明 |
|---|---|---|---|
| POST | `/api/auth/login` | 公開 | body `{identifier, password}` → 200 `{success:true, token, user}`；失敗 401 `{success:false, error:"帳號或密碼錯誤"}`（**不得**提示預設密碼）；鎖定中 429（見第 5 節「登入次數限制」） |
| POST | `/api/auth/register` | 公開 | 農場主自助註冊（2026-10-08 起）：body `{name, phone, password, address, pdpaConsent:true, farmName?, farmType?}` → 200 同 login 回應（註冊即登入）。帳號＝手機號碼純數字（`09` 開頭 10 碼，輸入可含 `-`／空白），建立 `farmers`（編號 `F###`，與既有 farmer 帳號 refId 不重複）與 `users`（role `farmer`）。姓名 50 字、密碼 8～128 字元、農地地址必填 255 字、農場名稱 100 字；未同意個資蒐集 400；手機已註冊 409；同一來源 IP 每小時 10 次（`agri.security.register-ip-max`／`register-window-minutes`），超過 429。工人不開放自助註冊（須合作社查驗證件後建檔）。個資告知文字【待確認：律師審閱】 |
| POST | `/api/auth/demo-login` | 公開，**僅 demo 模式** | body `{role, refId?}` → 同 login 回應，`user.demo=true`；非 demo 模式回 404 |
| GET | `/api/auth/me` | 需登入 | 200 `{success:true, user}` |
| GET | `/api/auth/quick-profiles` | **僅 demo 模式** | 示範帳號清單，**不含**密碼、居留證號、護照號；非 demo 模式回 404 |
| GET | `/api/public/config` | 公開 | `{success:true, demoEnabled, paymentProvider}` |

`user` 物件欄位：`username, name, role, refId, roleDesc, subRole?, demo?, workerProfile?`（workerProfile 內證件號碼遮罩）。

## 5. 授權規則

- **公開（不需 token）**：`POST /api/auth/login`、`POST /api/auth/register`、`POST /api/auth/demo-login`、`GET /api/public/**`、`GET /api/crops/**`、`GET /api/calendar/**`、`GET /api/brochures/**`、`GET /api/legalize/types`（黑轉白身分類型與法規參考文字）、`GET /api/payments/methods`、`GET /api/orders/categories`、`POST /api/orders/calculate`、`POST /api/talents/register`、`GET /api/dispatch/overview`、`GET /api/dispatch/weather-state`、`POST /api/payments/ecpay/notify`（以 CheckMacValue 驗證）、`POST /api/line/webhook`（已停用，一律回 410，見下方）、`POST /api/line/internal/**`（LINE Bot 內部端點，以 `X-Internal-Key` 驗證，見下方「LINE 官方帳號」）。
- **需登入**：其餘 `/api/**`。
- **僅 admin_tech**：crops / brochures / talents / workers 的新增修改刪除、`/api/dispatch/*` 的 POST/PUT、`/api/line/config`、`GET /api/line/bindings`、`DELETE /api/line/bindings/{id}`、`/api/notifications/send`、`GET /api/workers`（完整名冊）、`/api/dev/**`。
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
- **公開端點的資料範圍（2026-10-08 起）**：`GET /api/dispatch/overview` 未登入或非 admin 不回 `securedRevenue`，工班只回 id、名稱、人數、駐點與避險地點、狀態（不含領班、領班電話、車輛、司機）；`GET /api/brochures?all=true`（含未上架草稿）只對 admin 生效。
- **天候戰情氣象（2026-10-12 起）**：`GET /api/weather/towns[?force=true]`（需登入，任一角色）由後端代理中央氣象署鄉鎮天氣預報（F-D0047-093，22 縣市分 5 批），授權碼只放環境變數 `CWA_API_KEY`、不回傳前端也不寫入日誌；未設定時回 503，上游失敗時沿用上一份快取並標 `stale`，完全沒有資料時回 502。快取 `AGRI_WEATHER_CACHE_MINUTES`（預設 30 分鐘），`force=true` 仍受 1 分鐘最短間隔限制以保護配額。前端 demo／純前端 Mock 在 503 或離線時改用 Open-Meteo（限非商業，production 模式不使用）。
- **其他登入端點**：`GET /api/notifications` 非 admin 只回全體公告與 `target` 為自己（`token.ref`）的通知，忽略 `target` 參數；`POST /api/line/simulate-push`（LINE 體驗頁）正式模式僅限 admin。
- **金流通知防少付**：綠界通知除了比對該交易金額，還須等於訂單**目前**的定金、訂單未取消且尚未付款；否則交易記為已付（款項已入帳，回 `1|OK` 避免重送）但訂單不標已付定金，記 log 待人工退款或補收。mock 金流在交易金額與目前定金不同時回 409。
- **輸入長度**：訂單農場主姓名／現場對口姓名 100 字、電話 30 字、農地位置／現場交辦 255 字，人才報名經歷 255 字、電話 30 字，超過回 400；其餘資料庫約束錯誤（`DataIntegrityViolationException`）統一回 400，不回 500。
- **我的農場 `/api/farmers/me/farms`（2026-10-12 起，預約派工第一步，可登錄多個）**：僅 farmer（其餘角色含 admin 一律 403），只能讀寫自己的農場（`farms.farmer_id = token.ref`）。`GET` 回本人農場清單 `[{id, farmName, contactName, phone, address, farmType}]`（依登錄先後；清單為空且有自助註冊時的 `farmers` 資料，自動轉成第一個農場；種子帳號第一次預約前登錄）；`POST` 新增（每帳號最多 20 個，超過 409）；`PUT /{id}` 修改、`DELETE /{id}` 刪除（不是自己的農場一律 404，不透露是否存在；刪除不影響既有訂單）。必填：農場名稱 100 字、現場聯絡人 50 字、地址／地號 255 字，電話去掉 `-`／空白後須為 `0` 開頭 9～10 碼。前台 farmer.html 有農場就直接顯示預約單並預選上次用的農場，訂單的聯絡人、電話、預設作業地點由所選農場帶入。訂單記錄所選農場：`POST /api/orders` 可帶 `farmId`（選填；須為下單農場主自己的農場，否則 400；admin 代建且未給 `farmerId` 時以農場的主人為準），後端存 `farmId` 與下單當時的農場名稱快照 `farmName`（前端傳的 `farmName` 不採用；農場之後改名或刪除，訂單仍顯示當時名稱）；`PUT /api/orders/{id}` 不能改農場。schema：`farms` 表、`orders.farm_id`／`farm_name`（既有資料庫以 `Software/database/migrations/2026-10-12_farms.sql`、`2026-10-12b_orders_farm.sql` 補建）。
- **工班評價 `/api/ratings`**（皆需登入，權限於 Controller/Service 檢查）：`PUT /orders/{orderId}` 限訂單擁有者或 admin，且訂單須為 `in_progress`／`completed`（否則 409）；`GET /orders/{orderId}` 權限同訂單讀取；`GET /teams`、`/teams/{teamId}`、`/tags` 任何登入者。回應不含評分者編號，訂單編號只回給 admin。
- **保險登錄 `/api/insurance`**（皆需登入，權限於 Controller/Service 檢查）：`GET /workers`、`POST /workers/{id}/enroll`、`POST /records/{id}/withdraw` 僅 admin；`GET /workers/{id}` 本人或 admin；`GET /teams/{teamId}` 任何登入者，但缺保名單 `gaps` 只回給 admin。
- **合法用工證明**：`GET /api/certificates/orders/{orderId}` 限訂單擁有者或 admin，依該訂單出勤逐日核對工作許可效期與加保，全部符合才附查驗碼；`GET /api/public/certificates/{certificateNo}?code=` 公開查驗（屬 `/api/public/**`），只回摘要、不含工人姓名。查驗碼為 `AGRI_TOKEN_SECRET` 簽出的 HMAC，未固定密鑰時重啟即改變。
- **工班合法化（黑轉白）`/api/legalize`**：`GET /types` 公開；其餘皆需登入——`GET /roster` 農場主只回自己的清冊（`?farmerId=` 對農場主無效），admin 回全部並可篩選；`POST /roster`、`PUT /roster/{id}`、`DELETE /roster/{id}` 限清冊所屬農場主或 admin（admin 新增須帶 `farmerId`），工人角色一律 403；`GET /summary`（各農場進度、補位缺口、罰鍰暴露）僅 admin（AuthInterceptor）；`POST /roster/reset` 僅 demo 模式（非 demo 回 404）。借用／失聯移工類型不接受也不回傳姓名。
- **熟手標籤 `/api/skills`**（皆需登入）：`GET /workers` 與 `POST /workers/{id}/{skill}/restore`（暫停複核恢復）僅 admin；`GET /workers/{id}` 本人或 admin；`GET /teams/{teamId}` 任何登入者，只回人數不列姓名。規則常數集中於 `SkillService`。
- **領班逐人評核 `/api/evaluations`**（皆需登入）：`GET /teams/{teamId}?date=`、`PUT /attendance/{id}`（`rating`＝excellent／ok／needs_improvement）限 admin 或「與受評者同工班且 isLeader 的工人」；不可評自己、須已下班簽核、領班只能評 7 天內班次。評核只用於技能標籤與媒合，不得作為扣薪或解僱依據【待確認】。
- **派工媒合 `/api/matching/orders/{orderId}`**（僅 admin）：`GET` 工班排序與出工名單建議、`GET /crew?teamId=` 指定工班名單、`PUT {teamId, crew:[工號]}` 確認派工（名單須為該工班成員且工作許可有效、完整加保，否則 400／409；已完工或取消的訂單 409）。出工名單只能由此設定，`POST/PUT /api/orders` 不接受 `crewWorkerIds`。訂單有出工名單時，轉 `in_progress` 的加保檢查與打卡自動連結皆以名單為準。
- **訂單工期**：`POST/PUT /api/orders` 可帶 `startDate`、`endDate`（yyyy-MM-dd，須同時提供、完工不早於開工、最長一年，否則 400）；已完工或取消的訂單不可改工期（409）；農場主只能在「待付定金」時改，已排定後須由 admin 調整（409）。派工媒合會排除已排入「工期重疊」訂單出工名單的工人；打卡自動連結在出工名單無法判定時，改取今天落在工期內的施工中訂單。
- **打卡連結訂單**：`POST /api/workers/{id}/clock-in`、`POST /api/attendance/clock-in` 可帶 `orderId`（須為施工中且工人屬該訂單工班，否則 409／400）；未帶時，工人所屬工班恰有一張施工中訂單才自動連結。
- **派工前加保檢查**：`PUT /api/orders/{id}/status` 轉為 `in_progress` 時，已分派工班的每位成員須於當日完整加保（本國：勞保／就保／職保／健保；外籍：勞保／職保／健保【待確認】），否則 409 並列出缺保人員。
- **登入次數限制（2026-10-08 起）**：`POST /api/auth/login` 同一帳號識別碼（不分大小寫）15 分鐘內失敗 5 次、或同一來源 IP 15 分鐘內失敗 30 次 → 之後 15 分鐘一律回 429 `{success:false, error:"嘗試次數過多，請 15 分鐘後再試"}`（密碼正確也一樣）。檢查在 PBKDF2 之前，鎖定期間不驗密碼、不查資料庫；登入成功清除該帳號計數（IP 計數不清）。記憶體實作（單一實例，重啟歸零），門檻可用 `agri.security.login-max-failures`／`login-ip-max-failures`／`login-window-minutes`／`login-lock-minutes` 覆寫。來源 IP 取連線端位址，部署在反向代理後須設定 `server.forward-headers-strategy`【待確認：正式環境代理架構】。
- **舊 token 失效（2026-10-08 起）**：`users`、`workers` 各有 `token_version`（預設 0）。本人改密碼（`/api/auth/change-password`，含當次使用的 token）、admin 重設密碼（`/api/auth/reset-password`）、變更角色（`users.role`；農工 `tier` 改變）時 +1，該帳號已簽發的 token 立即 401；帳號刪除後查無版本，其 token 同樣 401。demo-login 簽發的 demo token 沿用原規則（僅 demo 模式有效，不比對版本）。
- **舊 LINE webhook 停用**：`POST /api/line/webhook` 一律回 410 `{success:false, error:"GONE", message:"此端點已停用，LINE webhook 改由 LINE Bot 服務（/callback/farmer、/callback/worker）處理"}`，不讀取 body、不處理訊息、不查訂單。LINE Bot 需要後端資料時呼叫 `/api/line/internal/*`。
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
