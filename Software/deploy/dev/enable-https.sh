#!/usr/bin/env bash
# =============================================================================
# 智農派工 DEV：以 Let's Encrypt 申請 HTTPS 憑證（certbot 會改寫 Nginx 設定並自動續期）
# 用法：sudo bash enable-https.sh <DEV 網域> [通知 email]
# 執行即表示同意 Let's Encrypt 訂閱者協議（https://letsencrypt.org/repository/）。
# 前提：網域已指向本機公開 IP，且 http://<網域>/web/ 可以從外部開啟（雲端安全清單已開 80/443）。
# =============================================================================
set -euo pipefail
DOMAIN="${1:?請指定 DEV 網域}"
EMAIL="${2:-}"
if [ -n "$EMAIL" ]; then
  certbot --nginx -d "$DOMAIN" --non-interactive --agree-tos -m "$EMAIL" --redirect
else
  certbot --nginx -d "$DOMAIN" --non-interactive --agree-tos --register-unsafely-without-email --redirect
fi
systemctl enable --now certbot-renew.timer >/dev/null 2>&1 || true
curl -fsI "https://$DOMAIN/web/" | head -1
