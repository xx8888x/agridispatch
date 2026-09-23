# 智農派工 AgriDispatch — 專案工作說明

台灣外展農務派工平台（合作社為法定雇主 + 科技公司代管系統）。本資料夾同時包含商業文件與軟體。回覆一律使用繁體中文。

## 目錄

- `00_`～`07_`：商業、財務、契約、文宣、招募、Pitch、系統文件。**.md 是原始檔**，改完用 `python tools/md2docx.py <檔案.md>` 重新產生同名 .docx。
- `Software/backend/`：Spring Boot 3.2 + JPA + H2（port 3000），同時提供 `/demo/*`、`/web/*` 靜態頁。
- `Software/Line Bot/`：純 Java LINE Bot（port 5000）。
- `Software/demo/`：**前端唯一原始碼**（demo 模式，路演用）。
- `Software/web/`：正式版（production 模式），由同步腳本產生。
- `Software/android/`：WebView 殼；`app/src/main/assets/www/` 由同步腳本產生（demo 模式）。
- `Software/Doc/03_認證與API安全規格.md`：前後端認證與授權約定，改 API 前先讀。

## 規則

- 前端只改 `Software/demo/`，改完執行 `node Software/tools/sync-frontend.js`；`web/js/app-config.js` 與 `demo-accounts.js` 不同步（正式版不得含示範帳密）。
- 前端改完執行 `node Software/tools/check-frontend.js Software/demo` 檢查語法與連結，並用瀏覽器實際開頁確認主控台無錯誤。
- HTML 檔很大（admin.html 約 240KB）：用 grep 定位後局部讀取、局部編輯，不要整份讀或整份重寫。
- 後端：`cd Software/backend && maven\apache-maven-3.9.6\bin\mvn.cmd -o test`；路演用 `--spring.profiles.active=demo`。
- 金鑰只放 `.env` / `config.properties` / 環境變數（皆已 gitignore），不得寫進程式或文件。
- 財務數字以 `02_財務模型與政府補助/03_財務計畫報表2_…` (F2) 為定版；工資基準依勞動部現行最低工資（2026：月 29,500、時 196）。
- 法規疑點標「【待確認】」交由律師/主管機關，不自行下法律結論。
- 每完成一項修正就 commit；回報時區分 ✅ 已驗證 / ⚠️ 已改未驗證 / ❌ 未做。

## 啟動

- `start_all.bat`：啟動後端（3000）與 LINE Bot（5000）。
- `start_line_tunnel.bat`：以 localtunnel 暴露 LINE webhook（僅測試用）。
