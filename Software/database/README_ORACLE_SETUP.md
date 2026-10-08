# 智農派工 (AgriDispatch) - Oracle Database 建置與 SQL Developer 連線指引

本專案已備妥專屬之 Oracle 資料庫建庫腳本、17 大實體資料表結構（含主外鍵與完整中文註解）、20 位在籍移工與訂單初始種子資料，以及 Oracle SQL Developer 連線設定檔。

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
點擊 SQL 編輯器工具列的 **「以指令碼執行 (Run Script)」** 按鈕（快捷鍵：`F5`），即可自動完成 17 大資料表建立與種子資料匯入！

> ⚠️ 注意：
> - 腳本開頭會 **DROP 再重建** 全部資料表，既有資料會被清除。
> - `01_agridispatch_oracle_schema.sql` 已內含精簡種子，**不要接著再執行 `02_agridispatch_universal_seed.sql`**，否則主鍵會重複。
> - 若要改用 02 的完整種子（26 位農工、12 筆訂單），請只執行 01 的建表部分，再先執行 `ALTER SESSION SET NLS_TIMESTAMP_FORMAT = 'YYYY-MM-DD HH24:MI:SS';`，最後執行 02。
> - 以上流程已於 2026-10-01 在本機 Oracle 26ai Free（FREEPDB1，臨時 schema）實測，兩種流程皆 0 錯誤（同日加入評價、加保紀錄、打卡訂單與領班評核欄位、熟手複核表後再次實測通過；本機 AGRIDISPATCH 已用「01 建表＋02 完整種子」重建）。
> - `skill_reviews` 使用 identity 欄位，需 Oracle 12c 以上。
> - 2026-10-08 起 `clock_ins.id` 也是 identity；文字欄位一律為字元語意 `VARCHAR2(n CHAR)`。**既有的 AGRIDISPATCH schema 是舊結構**，要給後端連線前須以新腳本重建（會清除資料，請先備份）。

---

## 參之一、 後端連線 Oracle 與實測（2026-10-08）

後端以 `oracle` profile 連線（`Software/backend/src/main/resources/application-oracle.yml`，`ddl-auto: none`，不會自動改 schema），連線資訊由環境變數提供：

```powershell
$env:AGRI_DB_URL = "jdbc:oracle:thin:@//localhost:1521/FREEPDB1"
$env:AGRI_DB_USERNAME = "AGRIDISPATCH"
$env:AGRI_DB_PASSWORD = "<密碼>"
cd Software\backend
.\maven\apache-maven-3.9.6\bin\mvn.cmd -o spring-boot:run "-Dspring-boot.run.profiles=oracle"
```

正式模式另需 `AGRI_TOKEN_SECRET`、`AGRI_ADMIN_PASSWORD`（見 `文件/07_系統分析及文件/03_認證與API安全規格.md`）。

**示範資料（2026-10-08 起）**：正式模式預設**不寫入**示範工班、工人、訂單、出勤、人才與示範通知，啟動只補齊管理員（`admin_tech`）、作物費率與工時包 DM。測試環境（DEV）若需要示範資料，設 `AGRI_SEED_DEMO_DATA=true`。注意 `01_agridispatch_oracle_schema.sql` 末段本身含精簡示範種子：正式環境請只執行「初始種子資料」標記之前的建表部分，帳號用 `Software/database/local/production-accounts.sql` 建立。

**整合測試**：`src/test/java/com/agridispatch/OracleIntegrationTest.java` 只在設定 `AGRI_DB_URL` 時執行（平常的 `mvn test` 會略過），會寫入並重灌資料，**只能對臨時 schema 執行**。做法：

1. 以 `sqlplus / as sysdba` 進 `FREEPDB1`，建立臨時使用者（例如 `AGRI_ORA_TEST`，給 `create session, create table, create sequence, create view, create trigger, create procedure` 與 `quota unlimited on users`）。
2. 以該使用者執行 `01_agridispatch_oracle_schema.sql`（sqlplus 無法開啟含中文的路徑，先複製到英文路徑）。
3. 設定上面三個環境變數指向臨時使用者，執行 `mvn.cmd -o test "-Dtest=OracleIntegrationTest"`。
4. 測完 `drop user AGRI_ORA_TEST cascade`。

**本機注意**：TNS Listener 服務（`OracleOraDB23Home1TNSListener`）預設停止，啟動服務需系統管理員權限。2026-10-08 實測時改以一般程序執行 `tnslsnr.exe LISTENER`，但非服務身分的 listener 無法把連線交給資料庫（ORA-12518），需暫時開啟共用伺服器（`alter system set shared_servers=2 scope=memory; alter system set dispatchers='(PROTOCOL=TCP)(DISPATCHERS=1)' scope=memory;`，重啟後還原），連線字串加 `(SERVER=SHARED)`：`jdbc:oracle:thin:@(DESCRIPTION=(ADDRESS=(PROTOCOL=TCP)(HOST=localhost)(PORT=1521))(CONNECT_DATA=(SERVICE_NAME=FREEPDB1)(SERVER=SHARED)))`。正式主機請以服務方式啟動 listener。

實測結果（臨時 schema）：建表腳本 0 錯誤；「01 建表＋02 完整種子」0 錯誤；`OracleIntegrationTest` 6/6 通過（兩種種子狀態皆是），涵蓋啟動補種子、中文長字串、Y/N 旗標、TIMESTAMP 往返、綠界付款、打卡 identity、人才報名、天候 CLOB、60KB 封面圖 CLOB、評價、加保、技能複核、派工媒合、黑轉白清冊、依外鍵順序重灌。

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
