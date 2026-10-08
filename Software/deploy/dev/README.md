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
- 尚未部署 LINE Bot（DEV 先用網頁的 LINE 體驗頁；要部署時加 `/callback/` 轉送與服務）。
