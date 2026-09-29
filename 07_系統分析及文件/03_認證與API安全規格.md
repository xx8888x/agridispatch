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
- payload：`{"sub":"<username>","role":"<role>","ref":"<refId>","demo":false,"exp":<epochSeconds>}`，有效期 12 小時。
- 密鑰：環境變數 `AGRI_TOKEN_SECRET`（或 `agri.security.token-secret`）；未設定時每次啟動隨機產生並在 log 警告（重啟後舊 token 失效）。
- 前端存放：`sessionStorage.agri_token`、`sessionStorage.agri_user`（JSON）。
- 呼叫 API 一律帶 `Authorization: Bearer <token>`（由 `js/api-client.js` 自動加上）。

## 3. 角色

`admin_tech`（科技公司管理員）、`farmer`（農場主）、`worker_foreign`（外展移工）、`worker_local`（本地工 / 領班 / 機耕 / 勞務員，`subRole` 區分）。

## 4. 認證端點

| Method | Path | 權限 | 說明 |
|---|---|---|---|
| POST | `/api/auth/login` | 公開 | body `{identifier, password}` → 200 `{success:true, token, user}`；失敗 401 `{success:false, error:"帳號或密碼錯誤"}`（**不得**提示預設密碼） |
| POST | `/api/auth/demo-login` | 公開，**僅 demo 模式** | body `{role, refId?}` → 同 login 回應，`user.demo=true`；非 demo 模式回 404 |
| GET | `/api/auth/me` | 需登入 | 200 `{success:true, user}` |
| GET | `/api/auth/quick-profiles` | **僅 demo 模式** | 示範帳號清單，**不含**密碼、居留證號、護照號；非 demo 模式回 404 |
| GET | `/api/public/config` | 公開 | `{success:true, demoEnabled, paymentProvider}` |

`user` 物件欄位：`username, name, role, refId, roleDesc, subRole?, demo?, workerProfile?`（workerProfile 內證件號碼遮罩）。

## 5. 授權規則

- **公開（不需 token）**：`POST /api/auth/login`、`POST /api/auth/demo-login`、`GET /api/public/**`、`GET /api/crops/**`、`GET /api/calendar/**`、`GET /api/brochures/**`、`GET /api/payments/methods`、`GET /api/orders/categories`、`POST /api/orders/calculate`、`POST /api/talents/register`、`GET /api/dispatch/overview`、`GET /api/dispatch/weather-state`、`POST /api/payments/ecpay/notify`（以 CheckMacValue 驗證）、`POST /api/line/webhook`（以 X-Line-Signature 驗證）。
- **需登入**：其餘 `/api/**`。
- **僅 admin_tech**：crops / brochures / talents / workers 的新增修改刪除、`/api/dispatch/*` 的 POST/PUT、`/api/line/config`、`/api/notifications/send`、`GET /api/workers`（完整名冊）、`/api/dev/**`。
- **本人或 admin_tech**：`/api/workers/{id}/**`、`/api/attendance/*/{workerId}`、打卡（worker 只能替自己打卡）。
- **farmer**：只能查詢 / 付款自己的訂單（`order.farmerId == token.ref`；舊資料無 farmerId 時 demo 模式放行）。
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
- CORS：只允許 `agri.cors.allowed-origins`（預設 `http://localhost:3000,http://localhost:5000,http://127.0.0.1:3000`）。
- 密碼以 PBKDF2-HMAC-SHA256（310,000 次、16 bytes salt）雜湊儲存，格式 `pbkdf2$<iter>$<saltB64>$<hashB64>`；啟動時自動把舊明碼升級為雜湊。
- 管理員初始密碼：環境變數 `AGRI_ADMIN_PASSWORD`；demo 模式沿用示範密碼；兩者皆無時隨機產生並只在啟動 log 顯示一次。
