#!/usr/bin/env bash
# =============================================================================
# 智農派工 DEV：安裝備份排程與健康檢查（可重複執行；server-deploy.sh 每次部署會呼叫）
# 用法：sudo bash server-ops.sh   （agri-backup.sh、agri-healthcheck.sh 須在同一資料夾）
#   agri-backup.timer       每天 03:30（台北）Data Pump 匯出，保留 7 天 → /var/lib/agridispatch/backups/
#   agri-healthcheck.timer  每 5 分鐘檢查服務、磁碟、備份、憑證；告警設定在 /etc/agridispatch/ops.env
# =============================================================================
set -euo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
OPS_ENV=/etc/agridispatch/ops.env

install -m 750 "$HERE/agri-backup.sh" /usr/local/sbin/agri-backup
install -m 750 "$HERE/agri-healthcheck.sh" /usr/local/sbin/agri-healthcheck
mkdir -p /var/lib/agridispatch/backups /var/lib/agridispatch/health
chmod 700 /var/lib/agridispatch/backups

if [ ! -f "$OPS_ENV" ]; then
  umask 077
  cat > "$OPS_ENV" <<'EOF'
# 智農派工 DEV 維運設定（server-ops.sh 產生；改完不需重啟，下次檢查即生效）
# 告警 webhook（Slack／Discord／Google Chat／Teams 的傳入 webhook 網址）；留空＝只寫 journal
AGRI_ALERT_WEBHOOK=
# 根磁碟使用率告警門檻（%）
AGRI_DISK_WARN_PCT=85
EOF
fi
chmod 600 "$OPS_ENV"

cat > /etc/systemd/system/agri-backup.service <<'EOF'
[Unit]
Description=AgriDispatch DEV database backup (cold copy + Data Pump)
After=podman-restart.service

[Service]
Type=oneshot
ExecStart=/usr/local/sbin/agri-backup
SyslogIdentifier=agri-backup
# oneshot 預設 90 秒逾時；壓縮 8GB 資料磁碟區需數分鐘
TimeoutStartSec=2h
EOF

cat > /etc/systemd/system/agri-backup.timer <<'EOF'
[Unit]
Description=Daily AgriDispatch DEV database backup

[Timer]
OnCalendar=*-*-* 03:30:00 Asia/Taipei
Persistent=true
RandomizedDelaySec=5min

[Install]
WantedBy=timers.target
EOF

cat > /etc/systemd/system/agri-healthcheck.service <<'EOF'
[Unit]
Description=AgriDispatch DEV health check

[Service]
Type=oneshot
ExecStart=/usr/local/sbin/agri-healthcheck
SyslogIdentifier=agri-health
EOF

cat > /etc/systemd/system/agri-healthcheck.timer <<'EOF'
[Unit]
Description=AgriDispatch DEV health check every 5 minutes

[Timer]
OnBootSec=3min
OnUnitActiveSec=5min

[Install]
WantedBy=timers.target
EOF

systemctl daemon-reload
systemctl enable --now agri-backup.timer agri-healthcheck.timer >/dev/null
# 還沒有任何成功備份時先備份一次，健康檢查才不會一開始就告警
if [ ! -f /var/lib/agridispatch/backups/last-success ]; then
  echo "[ops] 第一次備份"
  systemctl start agri-backup.service
fi
echo "[ops] 備份與健康檢查已安裝：systemctl list-timers 'agri-*'"
