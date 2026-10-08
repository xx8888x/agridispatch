# 智農派工 DEV 環境部署（Oracle Cloud）

單機架構：一台 Always Free Ampere（Oracle Linux 9）上跑 **Nginx（對外 80/443）＋ Spring Boot 後端（127.0.0.1:3000）＋ Oracle Database 23ai Free 容器（127.0.0.1:1521）**。後端與資料庫只聽本機，對外只有 Nginx。正式環境可再依 `Deployment_Plan_Oracle.md` 拆成多台。

| 項目 | DEV 設定 |
|---|---|
| 網址 | `https://dev.<IP 以 - 連接>.sslip.io`（免費網域，指向主機 IP；換正式網域只要改 Nginx 與環境變數） |
| 模式 | 正式模式（無免密登入）＋ `AGRI_SEED_DEMO_DATA=true`（有示範資料可測） |
| 資料庫 | Oracle 23ai Free 容器，應用帳號 `AGRIDISPATCH`；建表用 `01_agridispatch_oracle_schema.sql` 的建表部分 |
| 金流 | 綠界**測試環境**公開測試商店（只能刷測試卡） |
| 密碼 | 全部在主機上隨機產生，存 `/etc/agridispatch/agri-dev.env`、`oracle.env`（root 才能讀），不進版本庫、不經過聊天 |

## 一、建立主機（Oracle Cloud 主控台，由你操作）

1. Compute → Instances → Create instance：Image **Oracle Linux 9**、Shape **VM.Standard.A1.Flex**（2 OCPU／12 GB 即可）、指派公開 IPv4、開機磁碟 50 GB 以上。
2. Add SSH keys → Paste public key：貼上本機 `~/.ssh/agri_dev_ed25519.pub`。
3. 子網路的 Security List 加 Ingress：來源 `0.0.0.0/0`、TCP、目的埠 80、443。
4. 免費帳號建立 Ampere 主機常出現 "Out of capacity"：稍後重試或換 availability domain。

## 二、部署（本機 PowerShell，於本資料夾）

```powershell
.\deploy.ps1 -HostIp <公開 IP> -Setup     # 第一次：安裝套件、資料庫容器、服務，並部署
.\deploy.ps1 -HostIp <公開 IP>            # 之後每次更新
.\deploy.ps1 -HostIp <公開 IP> -Accounts ..\..\database\local\production-accounts.sql   # 匯入帳號
```

確認 `http://dev.<IP>.sslip.io/web/` 可開後，在主機上申請 HTTPS 憑證（同意 Let's Encrypt 訂閱者協議）：

```bash
sudo bash /tmp/agri-deploy/enable-https.sh dev.<IP 以 - 連接>.sslip.io
```

## 三、部署後

- 管理員 `admin_tech` 的初始密碼在主機 `/etc/agridispatch/agri-dev.env` 的 `AGRI_ADMIN_PASSWORD`（`sudo cat` 查看），登入後請改密碼。
- App：`gradlew assembleClientRelease assembleServerRelease -PagriApiBase=https://<DEV 網域>/api`（版本號會 +1，記得提交 `version.properties`）。
- Log：`sudo journalctl -u agridispatch -f`（journald，上限 500MB）；服務：`sudo systemctl status agridispatch`；資料庫：`sudo podman logs agri-oracle`。
- 備份（DEV 簡易版）：`sudo podman exec agri-oracle ...` 匯出，或對開機磁碟建立 Oracle Cloud 備份。
- LINE Bot：服務 `agri-linebot`（只聽本機 5000），Nginx 以 `/callback/`（官方帳號 webhook）與 `/linebot-api/`（網頁 LINE 體驗頁）轉送；設定在 `/etc/agridispatch/linebot-dev.env`。兩個官方帳號金鑰未設定＝模擬模式；申請帳號後把金鑰加進該檔並 `sudo systemctl restart agri-linebot`，webhook 設為 `https://<DEV 網域>/callback/farmer`、`/callback/worker`。
- ⚠️ 模擬模式下 webhook 不驗簽章、`/linebot-api/mock/*` 對外可用（DEV 測試用）；設定正式金鑰後模擬模式即關閉。

## 四、目前的 DEV 環境（2026-10-08 部署）

| 項目 | 值 |
|---|---|
| 網址 | https://dev.141-147-147-14.sslip.io/web/（Let's Encrypt 憑證，2027-01-06 到期，自動續期） |
| 主機 | Oracle Cloud 大阪 `instance-20261008-1438`，Oracle Linux 9.8 **x86_64**（2 vCPU／11 GB；非 Always Free 的 Ampere，請留意計費） |
| 資料庫 | Oracle 23ai Free（lite 映像檔）容器 `agri-oracle`，固定 IP 10.88.0.50，資料磁碟區 `agri-oradata` |
| 帳號 | 11 個帳號已匯入（帳密清單在本機 `Software/database/local/`，勿外流）；`admin_tech` 密碼在主機 env 檔 |
| App | 1.0.13 正式簽章，`-PagriApiBase=https://dev.141-147-147-14.sslip.io/api`（「我的訂單」讀後端本人訂單、含農場主自助註冊頁、LINE 體驗頁連 DEV LINE Bot） |
| LINE Bot | 模擬模式，`https://dev.141-147-147-14.sslip.io/linebot-api/health`；與後端共用內部金鑰已連通（backendLinked） |
| 已知狀況 | 部分網路（例如家用路由器 DNS）查不到 sslip.io 網域；連不上時把電腦／手機 DNS 改為 8.8.8.8，或日後改用自有網域 |
