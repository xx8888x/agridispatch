# 智農派工 AgriDispatch — 專案工作說明

台灣外展農務派工平台（合作社為法定雇主 + 科技公司代管系統）。本資料夾同時包含商業文件與軟體。回覆一律使用繁體中文。

## 目錄

- 根目錄只放三個資料夾：`Software/`（軟體）、`文件/`（文件）、`其它/`（其它）；另有 `CLAUDE.md`、`.gitignore`、啟動用 .bat。
- `文件/`：`00_`～`07_` 為商業、財務、契約、文宣、招募、Pitch、系統文件。**.md 是原始檔**，改完用 `python 文件/tools/md2docx.py <檔案.md>` 重新產生同名 .docx；`文件/tools/` 放文件用工具（md2docx、make_charts）。
- `其它/`：非專案主線的資料（外勞仲介名冊 xlsx、農場派遣人力成本分析資料夾與壓縮檔、暫存檔）。
- `Software/backend/`：Spring Boot 3.2 + JPA + H2 **單體**（port 3000），controller 依前端分 `front/admin/linebot/common` 套件；同時提供 `/demo/*`、`/web/*` 靜態頁。（9/29 曾拆成三個微服務，因無法運作已於 e01b140 改回，勿再拆。）
- `Software/Line Bot/`：純 Java LINE Bot（port 5000），一支程式服務「農場主」「工作者」兩個官方帳號（`/callback/farmer`、`/callback/worker`），身分與綁定存後端 `line_users`，兩邊以 `AGRI_LINEBOT_INTERNAL_KEY` 互相呼叫；規格 `文件/07_系統分析及文件/05_LINE官方帳號拆分規格.md`、上線與實機驗收 `07_LINE官方帳號上線與實機驗收手冊.md`。改完跑 `TestSimulator --check`，整體驗收跑 `python tools/acceptance_mock.py`（於 `Software/Line Bot`）。
- `Software/demo/`：**前端唯一原始碼**（demo 模式，路演用），分三個子資料夾：`農業派遣主網站/`（前台與各角色頁）、`科技後台/`（admin、vue-crud）、`linebot/`（line.html），各自有一份 `css/`、`js/`；根目錄 `index.html`、`_redirects` 轉址到主網站。跨資料夾連結用 `../<資料夾>/`。
- `Software/web/`：正式版（production 模式），由同步腳本產生。
- `Software/android/`：WebView 殼，分 `client`（農場主／本勞／外勞，原生身分選擇後進對應登入頁）與 `server`（科技後台，僅 Play 內部測試軌）兩個 flavor，見 `文件/07_系統分析及文件/06_Android雙端App規格書.md`；release 版本號由 `version.properties` 自動遞增（建置後須提交），簽章金鑰在 repo 外、設定在已 gitignore 的 `keystore.properties`；`app/src/main/assets/www/` 由同步腳本產生（demo 模式）。
- `文件/07_系統分析及文件/03_認證與API安全規格.md`：前後端認證與授權約定，改 API 前先讀。

## 規則

- 前端只改 `Software/demo/`，改完執行 `node Software/tools/sync-frontend.js`；`web/*/js/app-config.js` 與 `demo-accounts.js` 不同步（正式版不得含示範帳密）。`demo/` 底下不要留暫存腳本，否則會被同步進 web 與 android。
- 前端改完執行 `node Software/tools/check-frontend.js demo`（從專案根目錄執行，路徑相對於 `Software/`）檢查語法與連結，並用瀏覽器實際開頁確認主控台無錯誤；此腳本不檢查 JS 字串內的連結。
- 用 PowerShell 讀寫中文檔案時一定要指定 UTF-8（無 BOM），否則會變成亂碼（9/29 曾因此損壞 MainActivity.java 與 app-config.js）。
- HTML 檔很大（科技後台/admin.html 約 240KB）：用 grep 定位後局部讀取、局部編輯，不要整份讀或整份重寫。
- 可能有多個 AI 工作階段同時改這個 repo：commit 一律用 `git commit -- <路徑>` 或先確認 `git diff --cached --stat`，不要把別人暫存的變更一起收進去。
- 後端：`cd Software/backend && maven\apache-maven-3.9.6\bin\mvn.cmd -o test`；路演用 `--spring.profiles.active=demo`。
- 金鑰只放 `.env` / `config.properties` / 環境變數（皆已 gitignore），不得寫進程式或文件。
- 財務數字以 `02_財務模型與政府補助/03_財務計畫報表2_…` (F2) 為定版；工資基準依勞動部現行最低工資（2026：月 29,500、時 196）。
- 法規疑點標「【待確認】」交由律師/主管機關，不自行下法律結論。
- 每完成一項修正就 commit；回報時區分 ✅ 已驗證 / ⚠️ 已改未驗證 / ❌ 未做。

## 啟動

- `start_all.bat`：啟動後端（3000）與 LINE Bot（5000）。
- `start_line_tunnel.bat`：以 localtunnel 暴露 LINE webhook（僅測試用）。
