# 智農派工 (AgriDispatch) - Oracle Database 建置與 SQL Developer 連線指引

本專案已備妥專屬之 Oracle 資料庫建庫腳本、16 大實體資料表結構（含主外鍵與完整中文註解）、20 位在籍移工與訂單初始種子資料，以及 Oracle SQL Developer 連線設定檔。

---

## 壹、 檔案清單說明

所有資料庫相關檔案皆存放於 [`Software/database/`](file:///d:/派遣專案網站(農事及其它)/Software/database/)：

```
D:\派遣專案網站(農事及其它)\Software\database\
├── 01_agridispatch_oracle_schema.sql    # 📋 完整 Oracle DDL 建表與 DML 種子資料腳本 (UTF-8)
├── sqldeveloper_connections.xml         # ⚙️ Oracle SQL Developer 連線匯入檔 (含 XE 與 23ai Free 配置)
└── README_ORACLE_SETUP.md               # 📖 操作指引與常見問題排查手冊
```

---

## 貳、 電腦環境檢測現況

系統已完成對您電腦的自動掃描檢測：

1. **Oracle SQL Developer (客戶端管理工具)**：
   - **狀態**：✅ **已安裝就緒**
   - **執行檔路徑**：[`D:\IDE\SQLDeveloper\sqldeveloper\sqldeveloper.exe`](file:///D:/IDE/SQLDeveloper/sqldeveloper/sqldeveloper.exe)

2. **Oracle Database Server (資料庫服務本體)**：
   - **狀態**：⚠️ **本地尚未啟動 1521 監聽服務**
   - **說明**：
     - 電腦目前無運行中之 Oracle Windows 服務（如 `OracleServiceXE` 或 `OracleOraDB...`）。
     - 若您的 Oracle 資料庫位於**外部伺服器、公司主機或雲端 (OCI/AWS)**，請於 SQL Developer 中將 `localhost` 改為您的資料庫主機 IP 或主機名稱。
     - 若您需要在**這台電腦本機安裝** Oracle 資料庫，推薦使用官方最新且免費的 **Oracle Database 23ai Free** 或 **Oracle Database 21c XE**（參見下方第三節）。

---

## 參、 如何使用 Oracle SQL Developer 開啟與執行

### 步驟 1：啟動 Oracle SQL Developer
您可以直接雙擊執行：
`D:\IDE\SQLDeveloper\sqldeveloper\sqldeveloper.exe`
*(系統亦已為您發送啟動指令)*

### 步驟 2：開啟建庫腳本
1. 進入 SQL Developer 頂部選單：**檔案 (File)** ➔ **開啟 (Open)**。
2. 選擇檔案：
   [`D:\派遣專案網站(農事及其它)\Software\database\01_agridispatch_oracle_schema.sql`](file:///d:/派遣專案網站(農事及其它)/Software/database/01_agridispatch_oracle_schema.sql)

### 步驟 3：匯入連線或建立新連線
- **方法 A（直接匯入連線檔）**：
  在左側「連線 (Connections)」視窗空白處按右鍵 ➔ 選擇 **「匯入連線 (Import Connections)」** ➔ 載入 [`sqldeveloper_connections.xml`](file:///d:/派遣專案網站(農事及其它)/Software/database/sqldeveloper_connections.xml)。
- **方法 B（手動建立連線）**：
  - **連線名稱**：`AgriDispatch_Oracle`
  - **使用者名稱**：`c##agridispatch` (或 `system`)
  - **密碼**：您自行設定的密碼（連線檔不儲存密碼，連線時輸入；請勿把密碼寫進任何檔案）
  - **主機名稱**：`localhost` (或遠端資料庫 IP)
  - **連接埠**：`1521`
  - **服務名稱 (Service Name)**：`XEPDB1` 或 `FREEPDB1` (若為舊版 SID 請選 `XE` 或 `ORCL`)

### 步驟 4：一鍵執行腳本
點擊 SQL 編輯器工具列的 **「以指令碼執行 (Run Script)」** 按鈕（快捷鍵：`F5`），即可自動完成 16 大資料表建立與種子資料匯入！

> ⚠️ 注意：
> - 腳本開頭會 **DROP 再重建** 全部資料表，既有資料會被清除。
> - `01_agridispatch_oracle_schema.sql` 已內含精簡種子，**不要接著再執行 `02_agridispatch_universal_seed.sql`**，否則主鍵會重複。
> - 若要改用 02 的完整種子（26 位農工、12 筆訂單），請只執行 01 的建表部分，再先執行 `ALTER SESSION SET NLS_TIMESTAMP_FORMAT = 'YYYY-MM-DD HH24:MI:SS';`，最後執行 02。
> - 以上流程已於 2026-10-01 在本機 Oracle 26ai Free（FREEPDB1，臨時 schema）實測，兩種流程皆 0 錯誤（同日加入評價、加保紀錄與打卡訂單欄位後再次實測通過）。

---

## 肆、 若本地電腦尚未安裝 Oracle Database Server 之快速安裝指引

若您電腦尚未安裝 Oracle 資料庫本體，建議以下任一種官方免費方式：

1. **官方安裝包 (最推薦 Windows 原生)**：
   - 前往 Oracle 官網下載：[Oracle Database 23ai Free for Windows (x64)](https://www.oracle.com/database/technologies/oracle23ai-free-downloads.html)
   - 下載解壓縮後點擊 `setup.exe`，安裝過程設定一組強密碼（請自行保管，勿寫入專案檔案）。
   - 安裝完成後，服務會自動於背景開機啟動，連接埠即為 `1521`，服務名稱為 `FREEPDB1`。

2. **Docker / Podman 容器化運行 (若有使用容器環境)**：
   ```bash
   docker run -d --name oracle23ai \
     -p 1521:1521 \
     -e ORACLE_PWD="<請自行設定強密碼>" \
     container-registry.oracle.com/database/free:latest
   ```
