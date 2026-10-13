# 智農派工 (AgriDispatch) - Java LINE Bot 服務引擎

本專案為「智農派工 (AgriDispatch)」的 LINE 官方帳號後端服務。依 `07_系統分析及文件/05_LINE官方帳號拆分規格.md`，同一支程式服務**兩個官方帳號**：「智農派工（農場主）」與「智農派工（工作者）」（本勞與外勞共用、多語），各自一條 webhook 路徑與一組金鑰。採用 **Java 17+ (原生零第三方相依)** 開發，支援 LINE Messaging API Webhook 簽章驗證（HMAC-SHA256）、六宮格圖文選單（Rich Menu）、8 大核心情境 Flex Message 卡片生成，以及繁中、🇻🇳 越南語、🇮🇩 印尼語多語系動態切換。

---

## 📁 目錄結構

```
Software\Line Bot\
├── src/                               # Java 核心原始碼
│   └── com/agridispatch/linebot/
│       ├── LineBotServer.java         # 主伺服器 (內建 HttpServer，監聽 :5000)
│       ├── Channel.java               # 兩個官方帳號（farmer／worker）與各自的金鑰、Mock 判斷
│       ├── WebhookHandler.java        # Webhook 處理器 (每帳號一個實例，以該帳號 secret 驗 HMAC-SHA256 簽章)
│       ├── MockChatHandler.java       # /mock/chat 本地模擬發言 (僅 Mock 模式)
│       ├── BotEvent.java              # 事件模型 (follow／unfollow／文字／postback)
│       ├── LineBotDispatcher.java     # 分發入口：依帳號交給下列兩個分派器
│       ├── FarmerDispatcher.java      # 農場主帳號功能 F-01～F-08
│       ├── WorkerDispatcher.java      # 工作者帳號功能 W-01～W-08 (多語)
│       ├── WorkerCards.java           # W-01／W-03／W-04 本人資料卡（後端 internal/query 的 data，依語言組卡）
│       ├── Identity.java、IdentityStore.java  # 使用者身分與語言 (存後端 line_users；後端不可用時暫存記憶體)
│       ├── BackendClient.java         # 呼叫後端 /api/line/internal/* (X-Internal-Key)
│       ├── InternalHandler.java       # 後端呼叫的 /internal/richmenu/link (X-Internal-Key)
│       ├── RichMenus.java             # 選單代碼、richMenuId 對照與 per-user 連結
│       ├── RichMenuUploader.java      # 圖文選單檢核 (--check) 與上傳工具
│       ├── I18n.java                  # 工作者帳號多語文字 (讀 i18n/*.properties)
│       ├── FlexMessageBuilder.java    # Flex Message 卡片構造器
│       ├── LineApiClient.java         # LINE Messaging API HTTP 客戶端 (每帳號一個；工作者帳號禁止 Broadcast)
│       ├── Config.java                # 設定檔讀取器 (支援 properties 與環境變數)
│       ├── TestSimulator.java         # 本地離線模擬測試工具 (免聯網即測即看)
│       └── RichMenuUploader.java      # 六宮格 Rich Menu 一鍵上傳發布工具
├── i18n/                              # 工作者帳號文字：worker_zh／vi／id.properties (UTF-8；越、印尼語待母語校對)
├── flex_templates/                    # 8 大情境 Flex Message JSON 範本 (相容 LINE Bot Designer)
│   ├── F-01_zh.json                   # 枋山愛文芒果派工確認單（檔名規則 <功能代碼>_<lang>.json，規格第玖章）
│   ├── F-03_zh.json                   # 產季早鳥保證工時包 (315 元/hr・鎖定名額)
│   ├── F-04_zh.json                   # 突發暴雨避險・專車轉移通報
│   ├── W-01_zh.json                   # 移工每日任務卡 (繁體中文)
│   ├── W-01_vi.json                   # 移工每日任務卡 (🇻🇳 越南語)
│   ├── W-01_id.json                   # 移工每日任務卡 (🇮🇩 印尼語)
│   ├── W-01-machinery_zh.json         # 本國機耕師與大疆 T50 代噴工單
│   └── W-04_zh.json                   # 農業部 8,800 元就業獎勵與薪資透明卡
├── rich_menu/                         # 兩帳號共 6 份圖文選單（<代碼>.json + .jpg，由 tools/make_rich_menus.py 產生）
├── tools/make_rich_menus.py           # 圖文選單產生器（選單按鈕與文字的原始定義）
├── config.properties                  # 伺服器與 LINE 金鑰設定檔 (已列於 .gitignore，不會被提交)
├── config.properties.example          # 設定檔範本 (含 admin.key / CORS 白名單說明)
├── .env / .env.example                # 環境變數文件範本 (Java 不會自動讀取 .env，詳見下方說明)
├── pom.xml                            # Maven 專案設定檔 (亦可直接使用 bat 編譯)
├── compile.bat                        # Windows 一鍵編譯腳本
├── run.bat                            # Windows 一鍵啟動 LINE Bot 伺服器
├── test_local.bat                     # Windows 一鍵本地離線測試工具
├── start_tunnel.bat                   # 轉呼根目錄 start_line_tunnel.bat 的 HTTPS 穿透捷徑
└── README.md                          # 本說明文件
```

---

## 🚀 如何啟動與查看

我們提供 **4 種查看方式**，您可以依據目前手邊的環境與需求選擇：

### 方式一：本地離線模擬測試（最快、免任何帳號）
如果您尚未申請 LINE Developers 官方帳號，或者只想快速查看所有卡片與回覆效果：
1. 直接雙擊執行 `test_local.bat`。
2. 系統會自動編譯並啟動測試模擬器。
3. 模擬器會先執行兩個帳號的自動檢核（約 40 項，只跑檢核可用 `java -cp bin com.agridispatch.linebot.TestSimulator --check`，失敗時結束碼為 1），接著進入互動模式。
4. 互動模式預設在**農場主帳號**，可用指令切換：
   - `/farmer`、`/worker`：切換帳號。
   - `/as <身分>`：設定示範身分 `farmer`、`local`（中高齡本勞）、`foreman`（帶班師傅）、`machinery`（機耕師）、`machinery_leader`（機耕隊組長）、`vn`、`id`、`th`、`unbound`（未綁定）。
   - `/follow [語言]`：模擬加好友，可帶 LINE 介面語言（例 `/follow vi`）。
   - `/pb <data>`：模擬圖文選單 postback（例 `/pb a=lang&v=vi`）。
5. 農場主帳號可輸入：`派工`（派工確認單）、`黑轉白`（身分分流；點身分按鈕送出 `黑轉白：失聯移工` 等訊息。**2026-10-08 起暫時下架**：預設 `feature.legalize=false`，輸入黑轉白只回服務導覽）、`早鳥`、`避險`、`機耕`（代噴預約）、`費率`／`產曆`（轉後端查詢）、`帳號綁定`。
   Mock 模式下未指定身分的使用者視為已綁定的示範農場主。卡片按鈕連到 `web.base.url`（預設 `http://localhost:3000/demo`），實機 LINE 須改成公開 https 網址。
6. **2026-10-12 起外籍移工功能暫時下架**：預設 `feature.foreign-workers=false`（環境變數 `FEATURE_FOREIGN_WORKERS`），工作者帳號只有本國籍綁定入口、一律繁體中文，`/as vn`、`/as id` 等外籍身分只回「外籍工作者服務暫停中」；改 `true` 即恢復下列多語行為（圖文選單另需 `make_rich_menus.py` 的 `FEATURE_FOREIGN_WORKERS=True` 重產上傳）。
   工作者帳號（先 `/worker`、`/as vn`）可輸入：`nhiệm vụ`／`任務`（今日任務）、`chấm công`／`打卡`、`thời tiết`／`天氣`（集合通知）、`lương`／`薪資`、`出勤`、`切換語言`、`帳號綁定`。
7. 在一個帳號問另一個帳號的功能（例如工作者帳號輸入「黑轉白」「早鳥」「費率」），只會收到「請加入另一個帳號」的引導，不會回覆該功能內容。
8. **工作者帳號語音訊息**（規格 05 第 5.5 節）：預設關閉，`feature.worker-voice=true` 且設定 `OPENAI_API_KEY`（`stt.api.key`）才啟用；語音送 OpenAI Whisper 轉文字後回確認卡（「我聽到：…」＋動作按鈕），不直接執行。模擬器輸入 `/voice 今天的任務` 可直接給辨識結果測試（不呼叫 Whisper）。

---

### 方式二：啟動 Java LINE Bot 伺服器
1. 直接雙擊執行 `run.bat`。
2. 伺服器將在 `http://localhost:5000` 啟動。
3. 控制台將顯示：
   - 📡 Webhook 路徑：農場主帳號 `http://localhost:5000/callback/farmer`、工作者帳號 `http://localhost:5000/callback/worker`（舊的 `/callback`、`/webhook` 過渡期視同農場主帳號）
   - 🛠️ 本地除錯路徑：`http://localhost:5000/mock/chat`
   - 🔍 健康檢查路徑：`http://localhost:5000/health`

您可以打開瀏覽器或使用 PowerShell 測試伺服器是否正常：
```powershell
Invoke-RestMethod -Uri "http://localhost:5000/health"
```
回應的 `channels` 列出兩個帳號各自的模式（`MOCK`／`PRODUCTION`），`mode` 為總覽（兩個都正式才是 `PRODUCTION`，只有一個正式為 `MIXED`）。

---

### 方式三：網頁視覺化體驗館（手機擬真介面）
本專案的 `Software/web/linebot/line.html`（demo 版：`Software/demo/linebot/line.html`） 內建完整的手機 LINE 聊天室視覺化模擬器：
1. 使用瀏覽器直接開啟：
   `D:\派遣專案網站(農事及其它)\Software\demo\linebot\line.html`（或啟動後端後開 `http://localhost:3000/demo/linebot/line.html`）
2. 介面具備：
   - 📱 擬真智慧型手機外框與對話氣泡
   - 🎛️ 六宮格圖文選單點擊互動
   - 🌐 繁中 / 越語 / 印尼語切換按鈕
   - ⚡ 突發暴雨避險一鍵推播展示

---

### 方式四：連接真實 LINE 官方帳號（線上實機測試）
若您已在 [LINE Developers Console](https://developers.line.biz/) 的**同一個 Provider** 底下建立了農場主、工作者兩個 Messaging API Channel：
1. **設定金鑰**：
   複製 `config.properties.example` 為 `config.properties`（該檔已列於 .gitignore，不會被提交），並填入：
   ```properties
   line.farmer.channel.secret=農場主帳號的_Channel_Secret
   line.farmer.channel.access.token=農場主帳號的_Channel_Access_Token
   line.worker.channel.secret=工作者帳號的_Channel_Secret
   line.worker.channel.access.token=工作者帳號的_Channel_Access_Token
   line.farmer.basic.id=@農場主帳號BasicID   # 選填：「請加入另一個帳號」按鈕
   line.worker.basic.id=@工作者帳號BasicID
   admin.key=您自訂的高強度管理金鑰   # 用於保護 /api/config，詳見下方「管理端點與金鑰」
   ```
   （也可改用環境變數 `LINE_FARMER_CHANNEL_SECRET`、`LINE_FARMER_CHANNEL_ACCESS_TOKEN`、`LINE_WORKER_CHANNEL_SECRET`、`LINE_WORKER_CHANNEL_ACCESS_TOKEN`、`LINE_FARMER_BASIC_ID`、`LINE_WORKER_BASIC_ID`、`LINEBOT_ADMIN_KEY`，優先權高於 config.properties；請注意 Java 程式**不會**自動讀取 `.env` 檔，`.env`/`.env.example` 僅作為應設定哪些變數的文件參考。）
   舊的 `line.channel.*`／`LINE_CHANNEL_*` 仍可用，會被視為農場主帳號並在啟動時警告；未填金鑰的帳號維持 Mock 模式。
2. **啟動本機伺服器**：
   雙擊 `run.bat`（監聽 Port 5000，可用環境變數 `PORT` 覆寫）。
3. **建立外網穿透**：
   - 方式 A：於專案根目錄雙擊 `..\..\start_line_tunnel.bat`（使用 `npx localtunnel`，免額外帳號）。
   - 方式 B：或自行執行 `ngrok http 5000` 取得 HTTPS 轉發網址（例如：`https://xxxx.ngrok-free.app`）。
4. **設定 LINE Developers Webhook**：
   - 進入 LINE Developers Console -> 各 Channel -> **Messaging API**。
   - **Webhook URL**：農場主帳號填 `https://xxxx.loca.lt/callback/farmer`，工作者帳號填 `https://xxxx.loca.lt/callback/worker`（或您的 ngrok 網址）。
   - 關閉 LINE Official Account Manager 的「自動回應訊息」與「加入好友的歡迎訊息」，改由本程式回覆。
   - 啟用 **Use webhook**。
   - 點擊 **Verify** 確認回傳 Success。
5. **在手機 LINE 上測試**：
   拿起手機加入官方帳號好友，發送「派工」、「避險」或點選選單，即可在真實 LINE 收到精美的 Flex Message！

---

## 🗄️ 身分、語言與帳號綁定（後端 line_users）

- 加好友／封鎖、使用者選的語言、綁定狀態都存在後端 `line_users`（規格 6.5），重啟不遺失。Line Bot 以 `X-Internal-Key` 呼叫後端 `POST /api/line/internal/identity`、`/follow-state`、`/query`。
- 金鑰：環境變數 `AGRI_LINEBOT_INTERNAL_KEY`（或 `config.properties` 的 `internal.key`），**後端與 Line Bot 必須相同**。`start_all.bat` 未設定時會自動產生一組給兩邊共用。
- 未設定金鑰或後端離線時，Line Bot 退回本機記憶體（重啟後遺失）、訂單／費率／產曆改用本地卡片，啟動 log 會標示「身分與語言來源」。
- 綁定頁：`Software/demo/AgriDispatch/line-bind.html`（LIFF ID 設在前端 `js/app-config.js` 的 `liffBindId`；Line Bot 的 `LIFF_BIND_ID` 要填同一個）。使用者在綁定頁登入平台帳號後呼叫後端 `POST /api/line/bind`；後端綁定或解除後會呼叫 Line Bot `POST /internal/richmenu/link` 切換圖文選單（見下方「圖文選單」）。
- 本機測試綁定（後端 demo 模式、未設定 `LINE_LOGIN_CHANNEL_ID`）：以平台帳號登入取得 token 後，`POST /api/line/bind`，body `{"channel":"worker","idToken":"mock:U0123456789abcdef0123456789abcdef"}`；之後用同一個 userId 呼叫 `/mock/chat`（不帶 `as`）即可看到依綁定身分回覆。

## 📨 主動推播（規格第柒章）

後端決定推給誰，Line Bot 依收件人語言產生訊息並 multicast（每批 500 人，失敗重試 1 次）：

| 通知 | 帳號 | 觸發 |
| :--- | :--- | :--- |
| 訂單狀態更新 `order_status` | 農場主 | 科技後台變更訂單狀態 |
| 已收到定金 `deposit_paid` | 農場主 | 定金付款成功 |
| 天候警戒 `weather_farmer` | 農場主 | 科技後台切到警戒等級的天候情境 |
| 明日出工 `tomorrow_task` | 工作者（三語） | 每天 18:00 排程，或科技後台「立即發送」 |
| 天候與集合 `weather_worker` | 工作者（三語） | 同天候警戒；依工班是否改到室內分兩種內容 |
| 方案通知 | 農場主（broadcast） | 科技後台手動；工作者帳號不做 broadcast |

- 內部端點：`POST /internal/notify`（`{channel, type, params, recipients:[{lineUserId, lang}]}`）、`POST /internal/push`（原始訊息；`broadcast:true` 只限農場主帳號），都要 `X-Internal-Key`。Mock 模式只印 log、不呼叫 LINE。
- 工作者帳號只在後端設定的時段推播（`AGRI_LINE_WORKER_HOURS`，預設 07:00-20:00【待確認】）。
- 訊息範本：`src/.../NotifyMessages.java`；工作者帳號文字：`i18n/worker_*.properties` 的 `notify.*`。

## ✅ 模擬環境驗收

不需要 LINE 帳號，自動啟動隔離的後端（demo、暫存資料庫）與 Line Bot（模擬模式），跑規格第拾參章的 15 條驗收情境：

```
python tools/acceptance_mock.py --report 驗收結果.md
```

- 使用埠 3071、5071～5073，結束時自動關閉程序並刪除暫存資料；開發用資料庫與 `config.properties` 不受影響。
- 推播在模擬模式不呼叫 LINE，內容記在 `GET /mock/outbox?since=序號`（只在模擬模式開放），可確認推給誰、哪種語言。
- 帳號申請下來後的設定與手機實機驗收：`文件/07_系統分析及文件/07_LINE官方帳號上線與實機驗收手冊.md`。

## 🔐 管理端點與金鑰（/api/config）

`/api/config` 可讀取（遮罩後）與更新 LINE 金鑰、後台 API 位址等設定，屬於高權限端點，因此：

- **未設定管理金鑰時，此 API 會整個停用並回應 403**（啟動時的 console log 會提示如何設定）。
- 設定方式：環境變數 `LINEBOT_ADMIN_KEY=<您的金鑰>`，或 `config.properties` 加入 `admin.key=<您的金鑰>`（環境變數優先）。
- 呼叫時需於 HTTP 標頭帶上 `X-Admin-Key: <您的金鑰>`，伺服器以 constant-time 比對驗證，不會因為金鑰部分正確而洩漏比對進度。
- **⚠️ 重要**：透過 localtunnel / ngrok 對外穿透時，所有外部請求在本機端的來源位址一律顯示為 `127.0.0.1`，因此「僅允許本機連線」**不是**有效的保護機制，請務必設定 `admin.key`／`LINEBOT_ADMIN_KEY`，且不要使用範例值。
- `GET /api/config` 一律只回傳**遮罩後**的金鑰片段（例如 `abc...xyz`），依 `channels.farmer`、`channels.worker` 分列，不會回傳完整明文金鑰。
- `POST /api/config` 的 body 以 `channel`（`farmer`／`worker`，預設 `farmer`）指定要更新哪個帳號：`{"channel":"worker","channelSecret":"…","channelAccessToken":"…"}`。

### CORS 白名單
`Access-Control-Allow-Origin` 已從先前的 `*`（允許任意網域）改為白名單機制：
- 預設允許 `http://localhost:3000`、`http://127.0.0.1:3000`（本地前端開發用）。
- 可用環境變數 `CORS_ALLOWED_ORIGINS`，或 `config.properties` 的 `cors.allowed.origins` 覆寫（以逗號分隔多個網域）。
- 正式上線請改為您實際的前端網域（建議使用 `https://`）。

### `/mock/chat` 除錯端點
此端點僅供本地離線模擬測試使用，**只有在所指定的帳號為模擬模式（Mock Mode，金鑰未填或仍為範例值）時才會回應**；該帳號填入真實金鑰後，此端點對該帳號回應 403，避免正式環境曝露內部對話邏輯供外部匿名呼叫。

Body 欄位（JSON）：`channel`（`farmer` 預設／`worker`）、`userId`、`text`、`postback`（例 `a=lang&v=vi`）、`event`（`follow`／`unfollow`）、`as`（示範身分，同 TestSimulator 的 `/as`）、`lineLanguage`（模擬加好友時的 LINE 介面語言）、`voice`（模擬語音訊息的辨識結果）／`voiceError`（`too_long`、`failed`）、`multi`（`true` 時回傳 `{"channel":…,"messages":[…]}`，否則只回第一則訊息物件，相容舊版模擬器）。

---

## 🎛️ 圖文選單 (Rich Menu)

依規格第捌章，兩個帳號共 6 份選單，按鈕一律送 postback（`a=動作`，規格 5.2）；需要依身分決定網址的功能（綁定、打卡、預約）由回覆的卡片提供連結。

| 選單代碼 | 帳號 | 對象 | 版型 | 按鈕 |
| :--- | :--- | :--- | :--- | :--- |
| `farmer_guest`（帳號預設） | 農場主 | 未綁定 | 3×2 | 早鳥工時包、費率試算、產曆查詢、機耕代噴預約、帳號綁定、服務導覽 |
| `farmer_main` | 農場主 | 已綁定農場主 | 4×2 | 我的訂單、早鳥工時包、天候避險、機耕代噴預約、費率試算、產曆查詢、我的帳號、服務導覽（黑轉白下架期間；`make_rich_menus.py` 的 `FEATURE_LEGALIZE=True` 時為：我的訂單、現有工班黑轉白、早鳥工時包、天候避險、機耕代噴預約、費率與產曆、我的帳號、服務導覽） |
| `worker_guest`（帳號預設） | 工作者 | 未綁定 | 3×2（三語） | 繁體中文、Tiếng Việt、Bahasa Indonesia、帳號綁定、使用說明、聯絡合作社 |
| `worker_main_zh`／`_vi`／`_id` | 工作者 | 已綁定（依語言） | 3×2 | 今日任務、上下班打卡、天候與集合、薪資明細、我的出勤、語言・說明（越、印尼語版附中文小字） |

- **原始定義**：`tools/make_rich_menus.py`（一份定義同時產生 `rich_menu/<代碼>.json` 與 `.jpg`）。改按鈕或文字只改這支，執行 `python tools/make_rich_menus.py`（需 Pillow，使用 Windows 內建的微軟正黑體、Segoe UI）。格子圖示是 lucide 線條圖示（`tools/lucide_icons.py`，與前端設計系統同一套，不用 emoji），顏色依 04_UI設計系統規範：綠＝主要動作、灰＝資訊與設定、琥珀＝錢、藍＝天候、玫瑰＝求助。
- **檢核**：`java -cp bin com.agridispatch.linebot.RichMenuUploader --check`（JSON 結構、按鈕鋪滿不重疊、動作都在分派器支援清單、底圖 2500×1686 且 ≤1MB）；`TestSimulator --check` 也會跑這項，並實際送出每個按鈕的 postback。
- **上傳**：`java -cp bin com.agridispatch.linebot.RichMenuUploader farmer|worker|all [--delete-old]`
  - 該帳號須已填真實 channel access token（Mock 模式只檢核）。
  - 會先呼叫 LINE 驗證結構，再建立選單、上傳底圖、把 guest 選單設為帳號預設；任一步失敗就刪掉本次建立的選單。
  - richMenuId 記在 `rich_menu/richmenu_ids.properties`（各環境不同，已列入 `rich_menu/.gitignore`）；Line Bot 會自動讀新檔。
  - 舊選單預設保留；確認新選單正常後再加 `--delete-old` 上傳一次，或到 LINE 後台刪除。
- **切換時機**：每次使用者與帳號互動時，Line Bot 依目前身分與語言確認個人選單（已綁定 → main、未綁定 → 回到預設 guest），結果快取在記憶體；後端綁定或解除時也會呼叫 `/internal/richmenu/link` 立即切換。所以綁定、解除、改語言之後，選單會馬上換掉；重新上傳選單後，使用者下一次互動時會補連結。
- **科技後台**：`Software/demo/TechBackend/admin-line.html` 顯示兩帳號狀態（模式、選單上傳、加好友按鈕、共用金鑰、綁定頁 LINE 登入）與綁定清單，可強制解除綁定。
- **前端 LINE 模擬器**（`Software/demo/linebot/line.html`）：手機頂部顯示目前的官方帳號；工人可選細分身分；「實際綁定」模式以固定的模擬 LINE 帳號互動，按「開啟綁定頁」綁定後，回覆與選單就依後端綁定。
- **網頁模擬器**（`http://localhost:5000/`）：手機畫面下方顯示目前帳號與身分的選單底圖，點格子即送出該格的 postback，可以直接檢查選單切換。

---

## 💡 Flex Message 範本與設計相容性

`flex_templates/` 目錄下的 8 個 `.json` 檔案均為標準 LINE Flex Message 規格：
- 可直接複製內容貼入官方 [LINE Flex Message Simulator](https://developers.line.biz/flex-simulator/) 進行即時視覺微調。
- 完全由 Java 原生代碼動態讀取與填入參數，兼具彈性與高效能。
