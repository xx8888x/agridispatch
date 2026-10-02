# 智農派工 Android 雙端 App 規格書（Server 端／客戶端）

> **文件版本**：v1.0.0（2026-10-02）
> **適用範圍**：`Software/android/`（Java WebView 殼）
> **相關文件**：`01_系統分析與架構設計規格書.md`（7.1 技術組成、11.2 測試）、`03_認證與API安全規格.md`（前後端認證）、`05_LINE官方帳號拆分規格.md`（同一套身分代碼）、`Software/android/docs/角色與人力分類表.md`（分類表開發者版）、`Software/android/README.md`（建置環境）
> **資料來源**：`農場派遣人力成本分析/農場派遣人力成本分析_試算表.xlsx`（2026 年 10 月版）之「人力類型比較」「國別比較」「價目表」工作表
> **狀態**：程式已實作並通過建置、單元測試與 lintVital；**尚未在實機或模擬器上執行**。

---

## 壹、 背景與目標

### 1.1 現況問題

原本只有一支 Android App，啟動後直接開前台首頁，農場主、本勞、外勞與科技後台的頁面全部打包在同一支 APK。

| # | 問題 | 影響 |
|---|---|---|
| 1 | 後台頁面（`科技後台/`）跟著公開 App 一起發佈 | 任何下載 App 的人都拿得到後台頁面與登入入口 |
| 2 | App 不知道使用者是誰 | 外勞每次都要自己切語言；本勞進不到自己的軌別（勞務／帶班／機耕） |
| 3 | 國別只存在前端示範資料 | 原生通知、語言判斷無法依國別處理 |

### 1.2 目標

1. 依「端」拆成兩支 App：**Server 端＝科技後台**、**客戶端＝農場主與工人**。
2. 客戶端依人力分類（農場主／本勞／外勞，外勞再分國別）決定啟動頁與介面語言。
3. 分類資料表與後端 `users.role`、LINE 雙帳號規格使用同一套代碼。

### 1.3 上架方案比較（決策紀錄，2026-10-02 定案方案 A）

| 方案 | Play 公開上架數 | 評估 |
|---|---|---|
| **A. 客戶端一支 App，App 內選身分（定案）** | 1 支，加上後台走內部測試 | 上架、審查、維運只需一份；外勞由仲介或領班轉傳連結也不會裝錯版本 |
| B. 農場主、工人各一支 | 2 支 | 功能確實不同，政策上可行；維運成本加倍。保留為日後選項，只要多加一個 flavor 並固定身分 |
| C. 每類（含每個國別）各一支 | 4～6 支 | ❌ 各國別版本只差語言，可能被 Google Play「垃圾內容與最低功能」政策視為重複 App；每支都要分別填資料安全表單、內容分級、送審 |

- 國別差異一律用 **App 內語言切換＋Play 商店頁多語系**（zh-TW、vi、id）處理，不拆 App。
- **後台 App 不公開上架**，只走 Play Console **內部測試軌**（名單制，最多 100 人），只發給科技公司代管人員。
- 個人開發者帳號新 App 須先完成封閉測試（目前公告為 12 位測試者連續 14 天）才能申請正式版；組織帳號是否豁免，以送件當時 Play Console 公告為準【待確認】。

---

## 貳、 雙端架構

### 2.1 Product flavor

Gradle `flavorDimensions = ['side']`，同一份程式碼產出兩支 APK：

| 項目 | Server 端 `server` | 客戶端 `client` |
|---|---|---|
| App 名稱 | 智農派工後台（vi／id：AgriDispatch Admin） | 智農派工 |
| applicationId | `com.agridispatch.app.admin` | `com.agridispatch.app`（沿用原值） |
| versionName | `1.0.0-admin` | `1.0.0` |
| `BuildConfig.APP_SIDE` | `"server"` | `"client"` |
| 啟動 Activity | `MainActivity` | `RoleSelectActivity` |
| 啟動頁 | `www/科技後台/admin-login.html` | 依身分（見參、肆） |
| 打包的前端 | `assets/www/` 全部 | 排除 `assets/www/科技後台/`（`ignoreAssetsPatterns`） |
| 發佈 | Play 內部測試軌 | Play 正式版公開上架 |

兩支 App 的 applicationId 不同，可以裝在同一支手機上（例如代管人員同時測試客戶端）。

### 2.2 原始碼配置

| 路徑 | 內容 |
|---|---|
| `app/src/main/` | 共用程式：`MainActivity`、`WebAppInterface`、`RoleSelectActivity`、`profile/` 分類資料表 |
| `app/src/server/AndroidManifest.xml` | 宣告 `MainActivity` 為啟動 Activity |
| `app/src/server/res/values*/strings.xml` | 後台 App 名稱 |
| `app/src/client/AndroidManifest.xml` | 宣告 `RoleSelectActivity` 為啟動 Activity（只有客戶端 APK 含此 Activity 宣告） |
| `app/src/test/.../UserProfileTest.java` | 分類對應單元測試 |

主 `AndroidManifest.xml` 的 `MainActivity` 為 `exported="false"`、無 intent-filter；兩個 flavor 各自宣告自己的啟動入口。

### 2.3 FCM

兩支 App 套件名稱不同，`app/google-services.json` 必須在同一個 Firebase 專案下同時註冊 `com.agridispatch.app` 與 `com.agridispatch.app.admin`，否則 google-services 外掛建置會失敗（目前未放此檔，建置會略過外掛，推播不運作）。

---

## 參、 身分與人力分類資料表

價格僅供對照，**App 程式不寫死任何價格**；報價以財務定版 F2 與要派勞務服務契約為準。

### 3.1 表一：客戶端身分（`ClientRole`）

| 代碼 | 後端 `users.role` | 身分 | 登入頁（`www/農業派遣主網站/`） | 下一層 |
|---|---|---|---|---|
| `FARMER` | `farmer` | 農場主 | `farmer-login.html` | — |
| `WORKER_LOCAL` | `worker_local` | 本地工人（本勞） | `worker-local-login.html?role=<軌別>` | 表二 |
| `WORKER_FOREIGN` | `worker_foreign` | 外籍移工（外勞，外展移工） | `worker-foreign-login.html` | 表三 |
| （Server 端） | `admin_tech` | 科技公司代管人員 | `www/科技後台/admin-login.html` | — |

### 3.2 表二：本地工人類型（`LocalWorkerType`）

| 代碼 | 類型 | 前端軌別 `role=` | 計價（參考） | 適合作業 | 可排時段 | 注意事項 |
|---|---|---|---|---|---|---|
| `SENIOR` | 中高齡／銀髮（45 歲以上） | `labor` | $300~320/小時 | 冷鏈集貨場分級、清洗、貼標、裝箱；溫室挑菜包裝；樹下套袋、落果清理 | 平日上午或下午班，每日 4～6 小時 | 不安排攀高梯、正午曝曬、搬運超過 20 公斤 |
| `RETURNING_WOMEN` | 二度就業婦女 | `labor` | $300~320/小時 | 分級包裝、挑菜、套袋等細工 | 配合家庭作息（排班預約制） | 同中高齡之友善工種原則 |
| `STUDENT` | 大專生（寒暑假） | `labor` | $300~320/小時 | 採收、搬運、田間紀錄與手機回報 | 暑假約 6 月中～9 月中、寒假 | 非寒暑假期間供給有限 |
| `FOREMAN` | 本土帶班師傅 | `foreman` | $315/小時（下田工時） | 技術示範、品質把關、現場帶班 | 隨工班 | 約每 10 名移工 1 名，另由資深移工組長協助 |
| `MACHINERY` | 農機機耕師 | `machinery` | 依前端機耕軌 | 農機操作 | 依排班 | 試算表未列，沿用前端既有「機耕」軌別 |

### 3.3 表三：外籍移工國別（`Nationality`）

同工同酬、不以國籍差別定價：外展移工不論國籍皆依最低工資（2026：月 29,500 元）與法定保險計薪，雇主法定成本約 36,312 元/月，平台收費 $315/小時。國別只影響介面語言與排班提醒。

| 代碼 | 國別 | App 狀態 | 介面語言 | 引進專長方向 | 生活作息提醒 | 供應狀況 |
|---|---|---|---|---|---|---|
| `VN` | 越南 | ✅ 開放 | `vi` 越南語＋中文 | 果樹栽培、嫁接剪枝、套袋熟手 | 農曆春節返鄉需求較高，產季排班宜提早協調 | 主要來源；境內轉聘優先 |
| `ID` | 印尼 | ✅ 開放 | `id` 印尼語＋中文 | 溫室設施栽培、細工作業 | 多數為穆斯林：清真飲食、禮拜時間、齋戒月作息調整（因人而異） | 主要來源；境內轉聘優先 |
| `TH` | 泰國 | ⛔ 停用（按鈕顯示但不可點） | 系統尚未支援 | — | — | 未規劃引進【待確認】 |
| `PH` | 菲律賓 | ⛔ 停用（按鈕顯示但不可點） | 系統尚未支援 | — | — | 未規劃引進【待確認】 |

開放泰國、菲律賓時：`Nationality.java` 的 `offered` 改為 `true`、填入語言代碼，新增 `values-th`／`values-tl` 字串與前端語系；LINE 端同步新增選單（見 05 規格 3.1）。

---

## 肆、 客戶端流程

### 4.1 首次啟動

```mermaid
flowchart TD
    A[開啟 智農派工 App] --> B{已選身分?}
    B -- 是 --> M[MainActivity 載入對應登入頁]
    B -- 否 --> R[RoleSelectActivity：請選擇您的身分]
    R --> F[農場主] --> S[(ProfileStore 儲存)]
    R --> L[本地工人] --> LT[選類型：中高齡／二度就業／大專生／帶班師傅／機耕師] --> S
    R --> W[外籍移工] --> N[選國別：越南／印尼（泰國、菲律賓停用）] --> S
    S --> M
```

- 選擇畫面為原生畫面，文字依系統語系顯示（zh、vi、id）；國名同時列出中文與母語（例如「越南 · Việt Nam」）。
- 返回鍵：在第二步時回到第一步；在第一步時離開 App。

### 4.2 已選身分後

| 情境 | 行為 |
|---|---|
| 再次開啟 App | 直接載入對應登入頁，不再詢問 |
| 網頁點到 `農業派遣主網站/login.html`（各頁的「切換角色」） | 攔截並回到原生身分選擇畫面 |
| 網頁呼叫 `AgriNativeBridge.switchRole()` | 同上 |
| 網頁點到 `科技後台/…` 連結 | 攔截，提示「科技後台請使用『智農派工後台』App」，不開啟 |
| 儲存的國別後來被停用（`offered=false`） | 視同未選，下次開啟要求重選 |

### 4.3 Server 端

不顯示身分選擇，固定以 `admin_tech` 身分開啟 `科技後台/admin-login.html`；後台內連到前台頁面（農場主、工人頁預覽）照常可開。

---

## 伍、 原生橋接 API（`window.AgriNativeBridge`）

### 5.1 本次新增或修改

| 方法 | 回傳 | 說明 |
|---|---|---|
| `getAppProfile()` | JSON 字串 | 目前的端與身分，格式見 5.2 |
| `switchRole()` | — | 回到原生身分選擇畫面；Server 端無作用 |
| `getSystemLanguage()`（修改） | `zh`／`vi`／`id` | 外籍移工優先回傳所選國別的語言，其餘仍依系統語系 |

### 5.2 身分資料格式

頁面載入完成後，App 會注入 `window.AGRI_APP_PROFILE` 並觸發 `agri:app-profile` 事件（`event.detail` 為同一物件）：

```json
{
  "side": "client",
  "role": "WORKER_FOREIGN",
  "platformRole": "worker_foreign",
  "localType": "",
  "localTrack": "",
  "nationality": "VN",
  "language": "vi"
}
```

| 欄位 | 值 |
|---|---|
| `side` | `client`／`server` |
| `role` | `FARMER`／`WORKER_LOCAL`／`WORKER_FOREIGN`／`ADMIN` |
| `platformRole` | 後端 `users.role`：`farmer`／`worker_local`／`worker_foreign`／`admin_tech` |
| `localType`、`localTrack` | 本勞類型與前端軌別，其餘身分為空字串 |
| `nationality` | `VN`／`ID`，其餘身分為空字串 |
| `language` | 介面語言 |

外籍移工工單頁 `worker-foreign.html` 會在載入後自動呼叫頁面的 `setLang('vi'|'id')`。

### 5.3 既有方法（不變）

`showToast`、`vibrate`、`alertHarvest`、`sendSystemNotification`、`sendWeatherAlert`、`sendDispatchNotification`、`getFcmToken`、`getFcmTokenAsync`、`requestGpsLocation`、`saveOfflineData`、`getOfflineData`。

---

## 陸、 安全

| 項目 | 設計 |
|---|---|
| 後台頁面外流 | 客戶端 APK 不含 `科技後台/` 的 27 個檔案（建置後以 APK 內容比對確認）；後台 App 不公開上架 |
| 身分竄改 | 身分存在獨立的 SharedPreferences `AgriDispatchProfile`，與網頁可寫入的 `AgriDispatchCache`（`saveOfflineData`）分開，網頁無法改寫 |
| 身分不等於授權 | App 端的身分只決定啟動頁與語言，**不是授權依據**；API 權限仍以後端登入 token 與 `users.role` 判斷（見 03 認證規格） |
| 既有防護（不變） | WebView 只信任內建 assets 與 `allowed_web_domain`（HTTPS）；外部連結交給系統瀏覽器；敏感橋接方法只在受信任頁面回應；明文 HTTP 僅限開發位址 |

---

## 柒、 建置、測試與發佈

### 7.1 指令

專案路徑含中文與括號，須先 `subst` 對應英文磁碟代號（見 `Software/android/README.md`）。

| 項目 | 指令 | 產出 |
|---|---|---|
| 客戶端 debug | `gradlew.bat assembleClientDebug` | `app/build/outputs/apk/client/debug/app-client-debug.apk` |
| Server 端 debug | `gradlew.bat assembleServerDebug` | `app/build/outputs/apk/server/debug/app-server-debug.apk` |
| Release | `gradlew.bat assembleClientRelease assembleServerRelease` | `apk/<flavor>/release/` |
| 單元測試 | `gradlew.bat testClientDebugUnitTest testServerDebugUnitTest` | — |
| 安裝到手機 | `install_apk.bat`（客戶端）／`install_apk.bat -Flavor server` | — |

### 7.2 驗證結果（2026-10-02）

| 項目 | 結果 |
|---|---|
| 兩支 debug、release APK 建置 | ✅ 成功（client debug 約 8.0 MB、server debug 約 8.8 MB） |
| 套件名稱、啟動 Activity、App 名稱 | ✅ 以 `aapt dump badging` 確認 |
| 客戶端排除科技後台 | ✅ client 的 `assets/www` 64 個檔案、server 91 個，差額正好是 `科技後台/` 的 27 個檔案 |
| 單元測試 `UserProfileTest` | ✅ client、server 各 5/5 通過 |
| Release lintVital | ✅ 兩個 flavor 通過 |
| 安裝腳本 | ✅ 兩個 flavor 都能找到對應 APK（本機無裝置，未實際安裝） |
| 實機或模擬器執行 | ⚠️ 未做（本機無模擬器、無連線裝置） |

### 7.3 Play Console 發佈設定

| 項目 | 客戶端 | 後台 |
|---|---|---|
| 測試軌 | 封閉測試 → 正式版 | 內部測試（僅代管人員名單） |
| 商店頁語系 | zh-TW、vi、id（各自截圖與說明） | 不需公開商店頁 |
| 資料安全表單 | 位置（GPS 打卡）、裝置 ID（FCM）、使用者提供之帳號資料 | 同左，另含後台管理之個資 |
| 簽章 | 兩支 App 可共用同一把上傳金鑰（`keystore.properties`），但各自是獨立的 Play 應用程式 | |

---

## 捌、 待辦與跨工作階段配合

| # | 事項 | 負責 |
|---|---|---|
| 1 | 實機或模擬器測試：身分選擇三條路徑、切換角色、科技後台連結攔截、越南／印尼工單頁自動切語言 | Android |
| 2 | `worker-foreign-login.html`（Vue）預設語言固定為 `vi`，尚未讀 `AGRI_APP_PROFILE`；建議前端在 `agri:app-profile` 事件或 `AgriNativeBridge.getAppProfile()` 取得 `language` 後設定預設語言 | 前端（`Software/demo/`） |
| 3 | 前端「切換角色」可改呼叫 `AgriNativeBridge.switchRole()`（現行由 App 攔截 `login.html` 處理，網頁版不受影響） | 前端，選做 |
| 4 | Firebase 專案同時註冊兩個套件名稱並放入 `google-services.json` | 營運 |
| 5 | 泰國、菲律賓是否開放【待確認】；開放後依 3.3 調整 | 營運→Android、前端、LINE |
| 6 | 若日後改採方案 B（農場主、工人分開上架）：新增 flavor 並在該 flavor 固定 `ClientRole` | Android |
