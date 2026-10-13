# 智農派工 DEV 環境部署（Oracle Cloud）

單機架構：一台 Always Free Ampere（Oracle Linux 9）上跑 **Nginx（對外 80/443）＋ Spring Boot 後端（127.0.0.1:3000）＋ Oracle Database 23ai Free 容器（127.0.0.1:1521）**。後端與資料庫只聽本機，對外只有 Nginx。正式環境規劃見 `文件/07_系統分析及文件/09_正式環境部署規劃(Oracle Cloud).md`。

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
- 備份與健康檢查：每次部署由 `server-ops.sh` 安裝／更新（見下方「五、備份與還原」「六、健康檢查與告警」）。
- LINE Bot：服務 `agri-linebot`（只聽本機 5000），Nginx 以 `/callback/`（官方帳號 webhook）與 `/linebot-api/`（網頁 LINE 體驗頁）轉送；設定在 `/etc/agridispatch/linebot-dev.env`。兩個官方帳號金鑰未設定＝模擬模式；申請帳號後把金鑰加進該檔並 `sudo systemctl restart agri-linebot`，webhook 設為 `https://<DEV 網域>/callback/farmer`、`/callback/worker`。
- ⚠️ 模擬模式下 webhook 不驗簽章、`/linebot-api/mock/*` 對外可用（DEV 測試用）；設定正式金鑰後模擬模式即關閉。

## 四、目前的 DEV 環境（2026-10-08 部署）

| 項目 | 值 |
|---|---|
| 網址 | https://dev.141-147-147-14.sslip.io/web/（Let's Encrypt 憑證，2027-01-06 到期，自動續期） |
| 主機 | Oracle Cloud 大阪 `instance-20261008-1438`，Oracle Linux 9.8 **x86_64**（2 vCPU／11 GB；非 Always Free 的 Ampere，請留意計費） |
| 資料庫 | Oracle 23ai Free（lite 映像檔）容器 `agri-oracle`，固定 IP 10.88.0.50，資料磁碟區 `agri-oradata` |
| 帳號 | 11 個帳號已匯入（帳密清單在本機 `Software/database/local/`，勿外流）；`admin_tech` 密碼在主機 env 檔 |
| App | 1.0.15 正式簽章，`-PagriApiBase=https://dev.141-147-147-14.sslip.io/api`（「我的訂單」讀後端本人訂單、含農場主自助註冊頁、LINE 體驗頁連 DEV LINE Bot；1.0.15 起前端資料夾為 AgriDispatch／TechBackend，含農場主分兩步預約） |
| LINE Bot | 模擬模式，`https://dev.141-147-147-14.sslip.io/linebot-api/health`；與後端共用內部金鑰已連通（backendLinked） |
| 維運 | 2026-10-13 起每日 03:30 冷備份＋資料匯出、每 5 分鐘健康檢查（見第五、六節）；告警 webhook 尚未設定 |
| 已知狀況 | 部分網路（例如家用路由器 DNS）查不到 sslip.io 網域；連不上時把電腦／手機 DNS 改為 8.8.8.8，或日後改用自有網域 |

## 五、備份與還原

`agri-backup.timer` 每天 03:30（台北）執行 `/usr/local/sbin/agri-backup`，檔案在主機 `/var/lib/agridispatch/backups/`（root 才能讀）。DEV 的資料庫容器是 lite 映像檔：沒有 expdp／impdp／rman，也沒有 XDB，Data Pump 匯不出 DDL、也完全不能匯入，所以備兩份：

| 檔案 | 內容 | 保留 | 用途 |
|---|---|---|---|
| `agri_oradata_<時間>.tar.gz`（約 1.2 GB） | 冷備份：`shutdown immediate` → 以 XFS reflink 複製整個資料磁碟區（瞬間）→ `startup`，資料庫停約 5 秒；之後才壓縮 | 最近 3 份 | 在本機完整還原 |
| `agri_data_<時間>.dmp.gz`＋`.log` | `DBMS_DATAPUMP` 只匯 AGRIDISPATCH 的資料（不含 DDL） | 7 天 | 搬到完整版 Oracle（正式環境）：先用 `01_agridispatch_oracle_schema.sql` 建表＋`migrations/`，再以 Data Pump 匯入資料 |

- 手動備份：`sudo systemctl start agri-backup`；紀錄：`journalctl -u agri-backup`。成功時更新 `last-success`，健康檢查超過 26 小時沒有成功備份就告警。
- 還原冷備份（會覆蓋現有資料，先確認要還原的檔名）：

```bash
sudo systemctl stop agridispatch agri-linebot
sudo podman stop -t 120 agri-oracle
V=$(sudo podman volume inspect agri-oradata -f '{{.Mountpoint}}')
sudo mv "$V" "$V.before-restore"; sudo mkdir "$V"
sudo tar -xzf /var/lib/agridispatch/backups/agri_oradata_<時間>.tar.gz -C "$V"
sudo restorecon -R "$V"        # 恢復 SELinux 標籤（容器磁碟區是 :Z）
sudo systemd-run --scope --collect podman start agri-oracle   # 不要在 systemd 服務裡直接 podman start
sudo systemctl start agridispatch agri-linebot
# 確認正常後：sudo rm -rf "$V.before-restore"
```

- 容器一律用 `systemd-run --scope … podman start` 或在容器內 `sqlplus startup` 啟動：在 systemd oneshot 服務裡直接 `podman start` 時，conmon 會留在該服務的 cgroup，服務結束就被 systemd 連同資料庫一起殺掉（2026-10-13 曾因此讓 DEV 資料庫停約 4 分鐘，腳本已修正）。
- 主機層級另可在 Oracle Cloud 主控台對開機磁碟設定 Volume Backup Policy（Always Free 含 5 份磁碟區備份）。

## 六、健康檢查與告警

`agri-healthcheck.timer` 每 5 分鐘檢查：後端 `/api/public/config`、LINE Bot `/health`、Nginx、資料庫容器、根磁碟使用率（預設 85%）、最近一次成功備份（26 小時）、HTTPS 憑證效期（14 天）。冷備份期間略過後端與資料庫檢查。

- 狀態改變（異常／恢復）時告警，持續異常每 6 小時再提醒；告警一律寫 journal：`journalctl -t agri-health -p err`。
- 外部通知：在主機 `/etc/agridispatch/ops.env` 設定 `AGRI_ALERT_WEBHOOK=<網址>`（Slack／Discord／Google Chat／Teams 的傳入 webhook 皆可，送 `{"text","content"}`），不需重啟，下次檢查即生效。**目前尚未設定**，只寫 journal。
- 手動檢查：`sudo systemctl start agri-healthcheck; cat /var/lib/agridispatch/health/state`。
