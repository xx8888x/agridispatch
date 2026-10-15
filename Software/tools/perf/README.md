# 智農派工 效能測試腳本

2026-10-15「網站整體性能測試」使用的腳本（報告見 `文件/07_系統分析及文件/12_網站效能測試報告_20261015.md`）。
**只用於本機或你自己的暫存環境；對 DEV／正式環境施壓前先取得同意。** 腳本不屬於 `demo/`，不會被同步進 `web/` 或 Android。

## 前置條件

- Node.js 20 以上、Google Chrome（路徑不同時設環境變數 `CHROME`）。
- 在本資料夾執行一次 `npm install`（只裝 `playwright-core`，不下載瀏覽器）。
- 後端以 **demo profile** 啟動（腳本靠 `/api/auth/demo-login` 取得各角色 token）。
  建議用 `git archive HEAD` 取出已提交版本到暫存資料夾、用**暫存資料庫**啟動，避免弄髒 `Software/backend/data/`。
  `run_load.mjs` 的最後一階段會建立約 1,500 筆訂單；跑完用 `node reseed.mjs` 重灌回標準示範資料。

## 腳本

| 檔案 | 用途 |
|---|---|
| `perf.mjs` | 共用：HTTP keep-alive 客戶端、固定並發壓測 `runLoad`、百分位數彙總 |
| `probe.mjs` | 逐一探測約 40 支端點（狀態碼、大小、冷／暖延遲） |
| `run_load.mjs` | A～G 壓測：單端點、混合流量階梯、重量端點、登入、靜態檔、浸泡 60 秒、寫入與資料成長 |
| `sampler.ps1` | 壓測期間約每 2 秒取樣 JVM 的 CPU、記憶體、執行緒數 |
| `browser_test.mjs` | 桌面頁面（冷／暖載入）與手機節流（4G、CPU 4×）量測並截圖；`MODE=desktop\|mobile\|all`、`SITES=demo,web`、`ONLY=<正規表示式>` |
| `block_test.mjs` | 逐一封鎖 Tailwind／lucide／Chart.js／Google Fonts，量主執行緒成本 |
| `gzip_proxy.mjs` | 在後端前加 gzip 反向代理（:3100），驗證壓縮效益；搭配 `BASE=http://localhost:3100` 跑 `browser_test.mjs` |
| `sqlcount.mjs` | 讀後端 `--spring.jpa.show-sql=true` 的 log，統計各 API 每次請求的 SQL 數 |
| `reseed.mjs` | 呼叫 `/api/dev/seed?cleanFirst=true` 重灌示範資料 |
| `summarize.mjs` | 把結果 JSON 轉成報告用 Markdown 表格（`tables.md`） |

## 執行順序（範例）

```bash
# 後端已在 http://localhost:3000 以 demo profile 啟動
export OUT=/path/to/results && mkdir -p $OUT
node probe.mjs                      # → $OUT/probe.json
powershell -File sampler.ps1 -Out $OUT/resources.csv   # 另開視窗，與下一行同時跑
node run_load.mjs                   # → $OUT/load.json（約 14 分鐘）
node reseed.mjs                     # 寫入測試後重灌資料
SHOTS=/path/to/shots node browser_test.mjs   # → $OUT/browser.json 與截圖
node block_test.mjs                 # → $OUT/block.json
LOG=/path/to/backend.log node sqlcount.mjs   # 需以 show-sql 啟動後端
node summarize.mjs                  # → $OUT/tables.md
```

結果檔結構：`load.json`、`probe.json`、`browser.json`、`gzip/browser.json`（gzip 對照）、`block.json`、`sqlcount.json`、`resources.csv`；2026-10-15 的實際結果在 `文件/08_測試圖片/效能測試_20261015/raw/`。
