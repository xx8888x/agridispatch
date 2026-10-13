#!/usr/bin/env bash
# =============================================================================
# 智農派工 DEV：健康檢查（agri-healthcheck.timer 每 5 分鐘）
# 檢查後端、LINE Bot、Nginx、資料庫容器、磁碟、最近一次備份、HTTPS 憑證效期。
# 狀態改變（出問題／恢復）時告警；問題持續時每 6 小時再提醒一次。
# 告警：一律寫入 journal（journalctl -t agri-health -p err）；/etc/agridispatch/ops.env 設了
#       AGRI_ALERT_WEBHOOK 時另 POST {"text","content"}（Slack／Discord／Google Chat／Teams 傳入 webhook 皆可）
# =============================================================================
set -uo pipefail

STATE_DIR=/var/lib/agridispatch/health
OPS_ENV=/etc/agridispatch/ops.env
REMIND_SECONDS=$((6 * 3600))
AGRI_ALERT_WEBHOOK=""
AGRI_DISK_WARN_PCT=85
# shellcheck disable=SC1090
[ -f "$OPS_ENV" ] && source "$OPS_ENV"
mkdir -p "$STATE_DIR"

problems=()
check_http() { curl -fsS -m 10 -o /dev/null "$2" 2>/dev/null || problems+=("$1 無回應（$2）"); }

check_http "LINE Bot" http://127.0.0.1:5000/health
systemctl is-active -q nginx || problems+=("Nginx 未執行")
# 冷備份期間資料庫會關閉 1～2 分鐘：備份鎖存在且未超過 30 分鐘時略過後端與資料庫
lock=/run/agri-backup.lock
if [ -f "$lock" ] && [ $(( $(date +%s) - $(cat "$lock" 2>/dev/null || echo 0) )) -lt 1800 ]; then
  :
else
  check_http "後端" http://127.0.0.1:3000/api/public/config
  [ "$(podman inspect -f '{{.State.Running}}' agri-oracle 2>/dev/null)" = "true" ] || problems+=("資料庫容器 agri-oracle 未執行")
fi

disk=$(df --output=pcent / | tail -1 | tr -dc '0-9')
[ "${disk:-0}" -ge "$AGRI_DISK_WARN_PCT" ] && problems+=("根磁碟已用 ${disk}%")

last=/var/lib/agridispatch/backups/last-success
if [ ! -f "$last" ] || [ $(( $(date +%s) - $(stat -c %Y "$last") )) -gt $((26 * 3600)) ]; then
  problems+=("超過 26 小時沒有成功的資料庫備份（journalctl -u agri-backup）")
fi

for cert in /etc/letsencrypt/live/*/cert.pem; do
  [ -f "$cert" ] || continue
  openssl x509 -checkend $((14 * 86400)) -noout -in "$cert" >/dev/null || problems+=("HTTPS 憑證 14 天內到期：$cert")
done

host=$(hostname -s)
now=$(date +%s)
if [ ${#problems[@]} -gt 0 ]; then
  current=$(printf '%s；' "${problems[@]}")
else
  current="OK"
fi
previous=$(cat "$STATE_DIR/state" 2>/dev/null || echo "OK")
last_alert=$(cat "$STATE_DIR/last-alert" 2>/dev/null || echo 0)

message=""
if [ "$current" != "$previous" ]; then
  if [ "$current" = "OK" ]; then message="✅ 智農派工 DEV（$host）已恢復正常"; else message="⚠️ 智農派工 DEV（$host）異常：$current"; fi
elif [ "$current" != "OK" ] && [ $((now - last_alert)) -ge $REMIND_SECONDS ]; then
  message="⚠️ 智農派工 DEV（$host）仍異常：$current"
fi
echo "$current" > "$STATE_DIR/state"

if [ -n "$message" ]; then
  if [ "$current" = "OK" ]; then logger -t agri-health -p daemon.notice "$message"; else logger -t agri-health -p daemon.err "$message"; fi
  echo "$now" > "$STATE_DIR/last-alert"
  if [ -n "$AGRI_ALERT_WEBHOOK" ]; then
    body=$(python3 -c 'import json,sys; m=sys.argv[1]; print(json.dumps({"text": m, "content": m}, ensure_ascii=False))' "$message")
    curl -fsS -m 15 -H 'Content-Type: application/json' -d "$body" "$AGRI_ALERT_WEBHOOK" >/dev/null \
      || logger -t agri-health -p daemon.err "告警 webhook 傳送失敗"
  fi
fi
[ "$current" = "OK" ]
