# 智農派工 LINE 官方帳號上線與實機驗收手冊

> **文件版本**：v1.0.0（2026-10-02）
> **用途**：LINE 雙帳號（農場主／工作者）程式已完成並通過模擬驗收（P1～P5）。本手冊給營運與技術人員在申請到真實帳號後，照步驟完成設定、上傳圖文選單，並在手機上逐條驗收（規格 P0、P6）。
> **相關文件**：`05_LINE官方帳號拆分規格.md`（規格與決策）、`03_認證與API安全規格.md`（端點權限）、`Software/Line Bot/README.md`（程式說明）
> LINE 後台的選單名稱與流程以 LINE 官方當時的畫面為準；本手冊寫的是要達成的設定結果。

---

## 壹、 申請帳號（P0）

### 1.1 要建立的東西

全部建在 LINE Developers 的**同一個 Provider** 底下（同一 Provider 下，同一個人在兩個官方帳號與 LINE Login 的 userId 相同，後端才能共用一套綁定）。

| # | 項目 | 建立方式 | 取得 |
|---|---|---|---|
| 1 | 官方帳號「智農派工（農場主）」 | 在 LINE Official Account Manager 建立官方帳號，再於帳號設定啟用 Messaging API，選擇上述 Provider | Channel secret、Channel access token（長期）、Basic ID（@ 開頭） |
| 2 | 官方帳號「智農派工（工作者）」 | 同上 | 同上 |
| 3 | LINE Login channel「智農派工 帳號綁定」 | 在 LINE Developers 的同一 Provider 新增 LINE Login channel | Channel ID |
| 4 | LIFF app（掛在第 3 項） | 在 LINE Login channel 新增 LIFF：Size 選 Full、Scope 勾 `openid` 與 `profile`、Endpoint URL 填綁定頁（見 2.3） | LIFF ID |

【待確認】兩個官方帳號以合作社或科技公司名義申請、顯示名稱、是否申請認證帳號（規格第拾肆章第 1 項）。

### 1.2 兩個官方帳號的後台設定

| 設定 | 值 |
|---|---|
| Webhook URL | 農場主：`https://<LINE Bot 公開網域>/callback/farmer`；工作者：`https://<LINE Bot 公開網域>/callback/worker` |
| Use webhook | 開啟 |
| 自動回應訊息 | 關閉（由 LINE Bot 回覆） |
| 加入好友的歡迎訊息 | 關閉（由 LINE Bot 依身分與語言回覆） |
| 聊天（真人客服） | 依營運決定【待確認】 |

### 1.3 LINE Login channel

- LIFF 的 Endpoint URL 必須是 https，並且就是前端正式站的 `AgriDispatch/line-bind.html`。
- channel 在「開發中」狀態時只有 channel 管理員與測試人員能用；實機驗收時把驗收用的 LINE 帳號加為測試人員，正式上線前改為公開。

---

## 貳、 設定值對照

金鑰只放環境變數或已 gitignore 的設定檔，不寫進程式或文件。

### 2.1 LINE Bot（`Software/Line Bot`，環境變數或 `config.properties`）

| 環境變數 | 填什麼 |
|---|---|
| `LINE_FARMER_CHANNEL_SECRET`、`LINE_FARMER_CHANNEL_ACCESS_TOKEN` | 1.1 第 1 項 |
| `LINE_WORKER_CHANNEL_SECRET`、`LINE_WORKER_CHANNEL_ACCESS_TOKEN` | 1.1 第 2 項 |
| `LINE_FARMER_BASIC_ID`、`LINE_WORKER_BASIC_ID` | 兩帳號的 Basic ID（「請加入另一個帳號」按鈕用） |
| `LIFF_BIND_ID` | 1.1 第 4 項 LIFF ID |
| `AGRI_LINEBOT_INTERNAL_KEY` | 自行產生的高強度亂數，**與後端相同**，不得與其他金鑰相同 |
| `BACKEND_API_BASE` | 後端 API 位址，例 `https://<後端網域>/api` |
| `WEB_BASE_URL` | 前端正式站根網址（卡片按鈕連結），例 `https://<前端網域>/web` |
| `COOP_CONTACT_PHONE` | 合作社服務專線（選填） |
| `LINEBOT_ADMIN_KEY` | `/api/config` 管理金鑰（選填） |

### 2.2 後端（`Software/backend`，production 模式）

| 環境變數 | 填什麼 |
|---|---|
| `AGRI_LINEBOT_INTERNAL_KEY` | 與 LINE Bot 相同 |
| `AGRI_LINEBOT_BASE_URL` | LINE Bot 位址（後端可連到即可，不必公開） |
| `LINE_LOGIN_CHANNEL_ID` | 1.1 第 3 項 Channel ID |
| `AGRI_LINE_WORKER_HOURS` | 工作者帳號推播時段，預設 `07:00-20:00`【待確認】 |
| `AGRI_LINE_TOMORROW_CRON` | 明日出工通知時間，預設 `0 0 18 * * *`（每天 18:00）【待確認】 |

其餘（`AGRI_TOKEN_SECRET`、`AGRI_ADMIN_PASSWORD` 等）見 `01_系統分析與架構設計規格書.md` 9.1。

### 2.3 前端正式版

- `Software/web/AgriDispatch/js/app-config.js` 的 `liffBindId` 填 LIFF ID（與 LINE Bot 的 `LIFF_BIND_ID` 相同）。此檔不受同步腳本覆蓋，要直接改這一份。
- 綁定頁呼叫同網域的 `/api`，前端正式站與後端要在同一網域（或另設反向代理）。

### 2.4 網路

- LINE 平台要能以 https 連到 LINE Bot 的 `/callback/farmer`、`/callback/worker`（正式環境用反向代理或雲端服務；`start_line_tunnel.bat` 只供測試）。
- `/internal/*`、`/mock/*` 不要對外公開（前者有內部金鑰保護、後者在正式模式會回 403，但仍建議只開放 `/callback/*`）。

---

## 參、 部署與上傳圖文選單

1. 啟動後端（production）與 LINE Bot，確認 LINE Bot 啟動 log：兩個帳號都是 `【PRODUCTION】`、身分來源為「後端」。
2. 開科技後台 `TechBackend/admin-line.html`：兩個帳號應顯示「正式運作」、「加好友按鈕已設定」，「機器人與後台的共用金鑰」與「綁定頁的 LINE 登入」皆為「正常」。
3. 在兩個帳號的後台按 Webhook 的 Verify，應顯示成功。
4. 上傳圖文選單（於 `Software/Line Bot`）：
   ```
   java -cp bin com.agridispatch.linebot.RichMenuUploader --check
   java -cp bin com.agridispatch.linebot.RichMenuUploader all
   ```
   完成後 `rich_menu/richmenu_ids.properties` 會記下 6 份選單的 ID；科技後台應顯示「選單 2/2、4/4 已上傳」。
5. 用一支沒綁定的手機加兩個帳號好友，下方應分別看到 `farmer_guest`、`worker_guest` 選單。

---

## 肆、 實機驗收（規格第拾參章）

準備：至少 3 個 LINE 帳號（A：農場主、B：越南籍工人、C：印尼籍工人），再加一個「本國籍工人」與一個「機耕師」可用同一支手機先後綁定測試；平台上對應的帳號密碼由合作社提供。

| # | 手機上的操作 | 預期結果 | 模擬驗收（2026-10-02） | 實機結果 |
|---|---|---|---|---|
| 1 | A 未綁定，加農場主帳號好友 | 收到歡迎與「農場主帳號綁定」卡；選單為 `farmer_guest`（早鳥、費率、產曆、機耕、綁定、導覽） | ✅ | |
| 2 | B（LINE 介面設越南語）未綁定，加工作者帳號好友 | 三語歡迎、越語綁定卡；選單為 `worker_guest`（三語並列） | ✅ | |
| 3 | B 點綁定卡的「外籍移工」，在綁定頁登入並勾選同意 | 綁定頁顯示「Đã liên kết」；回 LINE 選單換成越語 `worker_main_vi`；點「Nhiệm vụ hôm nay」回越語任務卡 | ✅ | |
| 4 | B 點「Ngôn ngữ & hướng dẫn」再選「繁體中文」；請技術人員重啟後端與 LINE Bot 後再點「今日任務」 | 選單換成中文 `worker_main_zh`；重啟後仍是中文 | ✅ | |
| 5 | 本國籍工人綁定後點「今日任務」 | 收到本勞任務卡，**不是**農場主派工單 | ✅ | |
| 6 | 機耕師綁定後點「今日任務」 | 收到機耕工單（機耕隊組長另附工班出勤摘要） | ✅ | |
| 7 | B 在工作者帳號輸入「黑轉白」「早鳥」「費率」 | 只收到「此帳號提供工作人員使用」引導與加農場主帳號按鈕 | ✅ | |
| 8 | A 綁定後在農場主帳號輸入「薪資」 | 只收到「此帳號提供農場主使用」引導 | ✅ | |
| 9 | A 從工作者帳號的綁定卡開綁定頁，用農場主帳號登入 | 綁定頁顯示「這個帳號不是工作人員帳號」，未綁定 | ✅ | |
| 10 | 技術人員以 curl 對 `POST /api/line/bind` 送偽造的 idToken（並夾帶他人 lineUserId） | 回 401／503，該 lineUserId 仍未綁定（後端只採信向 LINE 驗證過的 ID token） | ✅（模擬：未設 LINE Login 回 503） | |
| 11 | 技術人員把工作者帳號的 Webhook URL 暫時改成 `/callback/farmer` 後按 Verify，驗完改回 | Verify 失敗（以工作者帳號的 secret 簽章送到農場主路徑會被拒） | ✅（模擬：農場主簽章送 `/callback/worker` 回 403） | |
| 12 | 技術人員以錯誤的 `X-Internal-Key` 呼叫後端 `/api/line/internal/identity` 與 LINE Bot `/internal/notify` | 都回 403 | ✅ | |
| 13 | C 封鎖工作者帳號；科技後台切天候到颱風 | C 收不到；同名單其他人收到 | ✅ | |
| 14 | 讓 B、C 與一位中文工人在同一張今天施工中的訂單出工名單，科技後台切天候（先晴天再颱風） | 三人各收到自己語言的「天候通知」；名單外的人收不到 | ✅（6 人收件：中 4、越 1、印尼 1；名單外 W002 未收到） | |
| 15 | 測試環境只設舊的 `LINE_CHANNEL_SECRET`／`LINE_CHANNEL_ACCESS_TOKEN` 啟動 LINE Bot | 農場主帳號正式、工作者帳號 Mock，啟動 log 有警告 | ✅ | |

補充驗收（P5 推播）：

| 操作 | 預期 | 實機結果 |
|---|---|---|
| 科技後台把 A 的訂單改為「已排定」 | A 收到「訂單狀態更新」 | |
| A 付訂單定金 | A 收到「已收到定金」 | |
| 科技後台 LINE 帳號頁按「立即發送」明日出工通知 | 隔天出工名單上的人收到自己語言的通知；時段外顯示略過人數 | |
| 科技後台發送農場主方案通知 | 所有農場主好友收到；工作者帳號沒有 | |
| 解除綁定（綁定頁或科技後台） | 該使用者的選單回到未綁定選單 | |

---

## 伍、 模擬環境驗收（每次改版後跑）

不需要 LINE 帳號，自動啟動隔離的後端、LINE Bot 與暫存資料庫，跑上表 15 條（實機才能確認的部分由手機驗收補上）：

```
cd "Software/Line Bot"
python tools/acceptance_mock.py --report 驗收結果.md
```

- 約需數分鐘（後端會啟動兩次以驗證重啟後資料保留）；使用埠 3071、5071～5073，結束時自動關閉並刪除暫存資料。
- 2026-10-02 結果：15／15 通過。
- 其他自動檢核：後端 `mvn -o test`（LINE 相關 4 個測試類別）、LINE Bot `TestSimulator --check`、`RichMenuUploader --check`、前端 `node Software/tools/check-frontend.js demo`。

---

## 陸、 上線後要看的

| 現象 | 看哪裡 | 處理 |
|---|---|---|
| 「簽章驗證失敗」 | LINE Bot log | Webhook URL 與帳號是否對調、secret 是否填錯 |
| 「後端無法連線」「改用本地資料」 | LINE Bot log | 後端是否在線、`BACKEND_API_BASE` 與內部金鑰兩邊是否相同 |
| 「尚未上傳圖文選單」 | LINE Bot log | 執行 `RichMenuUploader all` |
| 「推播 … failed／partial」 | LINE Bot log | 對方封鎖、token 失效、推播額度用完（LINE 後台查看） |
| 綁定頁「請從 LINE 官方帳號的選單開啟此頁」 | 使用者回報 | 前端 `liffBindId` 未填或 LIFF Endpoint URL 不符 |
| 「工作者帳號推播時段外，略過」 | 後端 log | 屬設計行為；時段依【待確認】結果調整 `AGRI_LINE_WORKER_HOURS` |

---

## 柒、 上線前要拍板的待確認事項

摘自規格第拾肆章，這幾項會直接影響已實作的行為：

| 事項 | 目前程式的暫定做法 |
|---|---|
| 工作者帳號可推播時段與工時認定 | 07:00-20:00，時段外略過 |
| 明日出工通知發送時間 | 每天 18:00 |
| 外籍移工綁定的個資告知與同意文字 | 綁定頁顯示暫定文字並要求勾選同意 |
| 8,800 元就業獎勵適用對象 | 薪資卡對所有工人顯示原內容 |
| 越語、印尼語文案母語校對 | 程式內為暫定翻譯 |
| 兩帳號申請名義、顯示名稱、認證帳號 | — |
| 真人客服是否開啟 | — |
