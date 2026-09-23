# 智農派工 (AgriDispatch) - Java LINE Bot 服務引擎

本專案為「智農派工 (AgriDispatch)」的專屬 LINE 官方帳號後端服務，採用 **Java 17+ (原生零第三方相依)** 開發，支援 LINE Messaging API Webhook 簽章驗證（HMAC-SHA256）、六宮格圖文選單（Rich Menu）、8 大核心情境 Flex Message 卡片生成，以及繁中、🇻🇳 越南語、🇮🇩 印尼語多語系動態切換。

---

## 📁 目錄結構

```
Software\Line Bot\
├── src/                               # Java 核心原始碼
│   └── com/agridispatch/linebot/
│       ├── LineBotServer.java         # 主伺服器 (內建 HttpServer，監聽 :5000)
│       ├── WebhookHandler.java        # Webhook 處理器 (HMAC-SHA256 簽章防偽與事件分發)
│       ├── LineBotDispatcher.java     # 智慧對話大腦 (多語系與關鍵字派工邏輯)
│       ├── FlexMessageBuilder.java    # 8 大情境 Flex Message 卡片構造器
│       ├── LineApiClient.java         # LINE Messaging API HTTP 客戶端 (Reply / Push / Broadcast)
│       ├── Config.java                # 設定檔讀取器 (支援 properties 與環境變數)
│       ├── TestSimulator.java         # 本地離線模擬測試工具 (免聯網即測即看)
│       └── RichMenuUploader.java      # 六宮格 Rich Menu 一鍵上傳發布工具
├── flex_templates/                    # 8 大情境 Flex Message JSON 範本 (相容 LINE Bot Designer)
│   ├── 01_farmer_order.json           # 枋山愛文芒果派工確認單
│   ├── 02_worker_task_zh.json         # 移工每日任務卡 (繁體中文)
│   ├── 03_worker_task_vi.json         # 移工每日任務卡 (🇻🇳 越南語)
│   ├── 04_worker_task_id.json         # 移工每日任務卡 (🇮🇩 印尼語)
│   ├── 05_weather_hedge.json          # 突發暴雨避險・專車轉移通報
│   ├── 06_early_bird.json             # 產季早鳥保證工時包 (9折優惠)
│   ├── 07_machinery_task.json         # 本國機耕師與大疆 T50 代噴工單
│   └── 08_salary_bonus.json           # 農業部 8,800 元就業獎勵與薪資透明卡
├── rich_menu/                         # 六宮格圖文選單設計
│   ├── rich_menu.json                 # LINE 官方 Rich Menu 結構定義 (2500x1686)
│   └── rich_menu.svg                  # 高解析向量視覺底圖
├── config.properties                  # 伺服器與 LINE 金鑰設定檔
├── pom.xml                            # Maven 專案設定檔 (亦可直接使用 bat 編譯)
├── compile.bat                        # Windows 一鍵編譯腳本
├── run.bat                            # Windows 一鍵啟動 LINE Bot 伺服器
├── test_local.bat                     # Windows 一鍵本地離線測試工具
└── README.md                          # 本說明文件
```

---

## 🚀 如何啟動與查看

我們提供 **4 種查看方式**，您可以依據目前手邊的環境與需求選擇：

### 方式一：本地離線模擬測試（最快、免任何帳號）
如果您尚未申請 LINE Developers 官方帳號，或者只想快速查看所有卡片與回覆效果：
1. 直接雙擊執行 `test_local.bat`。
2. 系統會自動編譯並啟動測試模擬器。
3. 模擬器會先自動執行核心指令檢測，接著進入互動模式。
4. 您可以直接輸入以下關鍵字測試：
   - 輸入 `派工`：查看農場主履約派工單。
   - 輸入 `避險`：查看突發暴雨冷鏈避險快訊。
   - 輸入 `早鳥`：查看產季早鳥 9 折保證工時包。
   - 輸入 `機耕`：查看本國機耕師代噴工單。
   - 輸入 `薪資`：查看農業部 8,800 元獎勵與保障薪資。
   - 輸入 `越南語` 接著輸入 `nhiệm vụ`：切換越南語並查看越語出工任務卡。
   - 輸入 `印尼語` 接著輸入 `tugas`：切換印尼語並查看印尼語出工任務卡。
   - 輸入 `帳號綁定`：查看 LINE 帳號身分綁定卡片。

---

### 方式二：啟動 Java LINE Bot 伺服器
1. 直接雙擊執行 `run.bat`。
2. 伺服器將在 `http://localhost:5000` 啟動。
3. 控制台將顯示：
   - 📡 Webhook 路徑：`http://localhost:5000/callback`
   - 🛠️ 本地除錯路徑：`http://localhost:5000/mock/chat`
   - 🔍 健康檢查路徑：`http://localhost:5000/health`

您可以打開瀏覽器或使用 PowerShell 測試伺服器是否正常：
```powershell
Invoke-RestMethod -Uri "http://localhost:5000/health"
```

---

### 方式三：網頁視覺化體驗館（手機擬真介面）
本專案的 `Software/web/line.html` 內建完整的手機 LINE 聊天室視覺化模擬器：
1. 使用瀏覽器直接開啟：
   `D:\派遣專案網站(農事及其它)\Software\web\line.html`
2. 介面具備：
   - 📱 擬真智慧型手機外框與對話氣泡
   - 🎛️ 六宮格圖文選單點擊互動
   - 🌐 繁中 / 越語 / 印尼語切換按鈕
   - ⚡ 突發暴雨避險一鍵推播展示

---

### 方式四：連接真實 LINE 官方帳號（線上實機測試）
若您已在 [LINE Developers Console](https://developers.line.biz/) 建立了 Messaging API Channel：
1. **設定金鑰**：
   編輯 `config.properties`：
   ```properties
   line.channel.access.token=您的_Channel_Access_Token
   line.channel.secret=您的_Channel_Secret
   ```
2. **啟動本機伺服器**：
   雙擊 `run.bat`（監聽 Port 5000）。
3. **建立外網穿透 (透過 ngrok)**：
   在終端機執行：
   ```bash
   ngrok http 5000
   ```
   取得 HTTPS 轉發網址（例如：`https://xxxx.ngrok-free.app`）。
4. **設定 LINE Developers Webhook**：
   - 進入 LINE Developers Console -> 您的 Channel -> **Messaging API**。
   - **Webhook URL** 填入：`https://xxxx.ngrok-free.app/callback`
   - 啟用 **Use webhook**。
   - 點擊 **Verify** 確認回傳 Success。
5. **在手機 LINE 上測試**：
   拿起手機加入官方帳號好友，發送「派工」、「避險」或點選選單，即可在真實 LINE 收到精美的 Flex Message！

---

## 🎛️ 六宮格圖文選單 (Rich Menu)

選單規格：`2500 x 1686` 像素，配置如下：

| 位置 | 功能名稱 | 觸發關鍵字 | 功能說明 |
| :---: | :--- | :--- | :--- |
| **左上 (1)** | 📋 今日派工卡 | `今日派工` | 農場主派工單 / 移工出勤任務卡 |
| **中上 (2)** | 🌧️ 暴雨天候避險 | `天候避險` | 突發暴雨冷鏈避險通報、專車接駁進度 |
| **右上 (3)** | ⚡ 早鳥工時包 | `早鳥工時包` | 預付 20% 定金享 9 折、保證出工承諾 |
| **左下 (4)** | 🌐 切換語言 | `切換語言` | 繁體中文 ╳ 🇻🇳 越南語 ╳ 🇮🇩 印尼語 |
| **中下 (5)** | 🚜 機耕代噴 | `機耕代噴` | 大疆 T50 植保機雙證照代噴、曳引機整地 |
| **右下 (6)** | 👤 帳號綁定 | `帳號綁定` | 綁定智農派工帳號、查詢個人津貼明細 |

若要一鍵將 Rich Menu 上傳發布至真實 LINE 帳號：
- 先將 `rich_menu/rich_menu.svg` 匯出為 `rich_menu/rich_menu.png`。
- 執行 `java -cp bin com.agridispatch.linebot.RichMenuUploader` 即可自動註冊並設為預設。

---

## 💡 Flex Message 範本與設計相容性

`flex_templates/` 目錄下的 8 個 `.json` 檔案均為標準 LINE Flex Message 規格：
- 可直接複製內容貼入官方 [LINE Flex Message Simulator](https://developers.line.biz/flex-simulator/) 進行即時視覺微調。
- 完全由 Java 原生代碼動態讀取與填入參數，兼具彈性與高效能。
