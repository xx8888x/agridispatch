#!/usr/bin/env bash
# =============================================================================
# 智農派工 DEV 主機一次性設定（Oracle Cloud Always Free：Oracle Linux 9、Ampere ARM）
# 用法：sudo bash server-setup.sh <DEV 網域，例如 dev.1-2-3-4.sslip.io>
# 可重複執行（已完成的步驟會略過）。完成後以 server-deploy.sh 部署程式，再以 enable-https.sh 申請憑證。
#
# 安裝：Java 17、Nginx、Podman、Oracle Database 23ai Free（容器，只聽本機 127.0.0.1:1521）
# 產生：/etc/agridispatch/agri-dev.env（後端環境變數與隨機密碼，僅 root／agri 可讀，不離開主機）
# =============================================================================
set -euo pipefail

DOMAIN="${1:?請指定 DEV 網域，例如 dev.1-2-3-4.sslip.io}"
APP_DIR=/opt/agridispatch
ETC_DIR=/etc/agridispatch
ENV_FILE=$ETC_DIR/agri-dev.env
ORA_ENV=$ETC_DIR/oracle.env
ORA_IMAGE=container-registry.oracle.com/database/free:latest-lite
ORA_NAME=agri-oracle

log() { echo -e "\n[setup] $*"; }
rand() { openssl rand -base64 48 | tr -dc 'A-Za-z0-9' | head -c "${1:-24}"; }

log "1/7 套件：Java 17、Nginx、Podman、certbot"
dnf install -y java-17-openjdk-headless nginx podman openssl tar policycoreutils-python-utils >/dev/null
if ! rpm -q oracle-epel-release-el9 >/dev/null 2>&1; then dnf install -y oracle-epel-release-el9 >/dev/null; fi
dnf install -y certbot python3-certbot-nginx >/dev/null

log "2/7 防火牆與 SELinux（開放 80/443；允許 Nginx 轉送到後端）"
systemctl enable --now firewalld >/dev/null 2>&1 || true
firewall-cmd --permanent --add-service=http --add-service=https >/dev/null
firewall-cmd --reload >/dev/null
setsebool -P httpd_can_network_connect 1

log "3/7 系統帳號與目錄"
id agri >/dev/null 2>&1 || useradd --system --home-dir $APP_DIR --shell /sbin/nologin agri
mkdir -p $APP_DIR/web $APP_DIR/data $APP_DIR/logs $ETC_DIR /tmp/agri-deploy
chown -R agri:agri $APP_DIR

log "4/7 環境變數與密碼（已存在則保留）"
if [ ! -f "$ORA_ENV" ]; then
  umask 077
  cat > "$ORA_ENV" <<EOF
ORACLE_PWD=$(rand 20)Aa1
AGRI_DB_PASSWORD=$(rand 20)Bb2
EOF
fi
# shellcheck disable=SC1090
source "$ORA_ENV"
if [ ! -f "$ENV_FILE" ]; then
  umask 077
  cat > "$ENV_FILE" <<EOF
# 智農派工 DEV 後端環境變數（由 server-setup.sh 產生；含密碼，勿外流）
AGRI_DB_URL=jdbc:oracle:thin:@//127.0.0.1:1521/FREEPDB1
AGRI_DB_USERNAME=AGRIDISPATCH
AGRI_DB_PASSWORD=$AGRI_DB_PASSWORD
AGRI_TOKEN_SECRET=$(rand 64)
AGRI_ADMIN_PASSWORD=$(rand 16)Cc3
# DEV 需要示範資料可測；正式環境不得設 true
AGRI_SEED_DEMO_DATA=true
AGRI_CORS_ALLOWED_ORIGINS=https://$DOMAIN,https://appassets.androidplatform.net
# 綠界「測試環境」公開測試商店（payment-stage），只能刷測試卡，不會真的扣款
AGRI_PAYMENT_PROVIDER=ecpay
ECPAY_MERCHANT_ID=3002607
ECPAY_HASH_KEY=pwFHCqoQZGmho4w6
ECPAY_HASH_IV=EkRm7iFT261dpevs
ECPAY_RETURN_URL=https://$DOMAIN/api/payments/ecpay/notify
ECPAY_CLIENT_BACK_URL=https://$DOMAIN/web/
# 在 Nginx 後面：以 X-Forwarded-For 取得使用者 IP（登入次數限制用）
SERVER_FORWARD_HEADERS_STRATEGY=native
EOF
fi
chown root:agri "$ENV_FILE"; chmod 640 "$ENV_FILE"; chmod 600 "$ORA_ENV"

log "5/7 Oracle Database 23ai Free（容器）"
if ! podman container exists $ORA_NAME; then
  podman pull $ORA_IMAGE
  podman run -d --name $ORA_NAME --restart=always \
    -p 127.0.0.1:1521:1521 \
    -e ORACLE_PWD="$ORACLE_PWD" \
    -v agri-oradata:/opt/oracle/oradata \
    $ORA_IMAGE
fi
systemctl enable --now podman-restart.service >/dev/null 2>&1 || true
echo -n "等待資料庫就緒"
for _ in $(seq 1 120); do
  if podman logs $ORA_NAME 2>&1 | grep -q "DATABASE IS READY TO USE"; then echo " ✓"; break; fi
  echo -n "."; sleep 5
done
podman logs $ORA_NAME 2>&1 | grep -q "DATABASE IS READY TO USE" || { echo; echo "資料庫未就緒，請看 podman logs $ORA_NAME"; exit 1; }

log "6/7 建立應用程式帳號 AGRIDISPATCH（已存在則略過）"
podman exec -i $ORA_NAME sqlplus -s /nolog <<EOF
whenever sqlerror exit failure
connect sys/"$ORACLE_PWD"@//localhost:1521/FREEPDB1 as sysdba
set serveroutput on
declare n number;
begin
  select count(*) into n from dba_users where username = 'AGRIDISPATCH';
  if n = 0 then
    execute immediate 'create user AGRIDISPATCH identified by "$AGRI_DB_PASSWORD" default tablespace users quota unlimited on users';
    execute immediate 'grant create session, create table, create sequence, create view, create trigger, create procedure to AGRIDISPATCH';
    dbms_output.put_line('AGRIDISPATCH 已建立');
  else
    dbms_output.put_line('AGRIDISPATCH 已存在');
  end if;
end;
/
exit
EOF

log "7/7 systemd 服務與 Nginx（HTTP；憑證由 enable-https.sh 申請）"
cat > /etc/systemd/system/agridispatch.service <<EOF
[Unit]
Description=AgriDispatch backend (DEV)
After=network-online.target podman-restart.service
Wants=network-online.target

[Service]
User=agri
WorkingDirectory=$APP_DIR
EnvironmentFile=$ENV_FILE
ExecStart=/usr/bin/java -Xms256m -Xmx1g -Dfile.encoding=UTF-8 -jar $APP_DIR/app.jar --spring.profiles.active=oracle --server.port=3000 --server.address=127.0.0.1
Restart=on-failure
RestartSec=10
StandardOutput=append:$APP_DIR/logs/app.log
StandardError=append:$APP_DIR/logs/app.log

[Install]
WantedBy=multi-user.target
EOF
systemctl daemon-reload
systemctl enable agridispatch.service >/dev/null

cat > /etc/nginx/conf.d/agridispatch.conf <<EOF
server {
    listen 80;
    server_name $DOMAIN;
    client_max_body_size 10m;          # 文宣 DM 封面圖（base64）
    location = / { return 302 /web/; }
    location / {
        proxy_pass http://127.0.0.1:3000;
        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto \$scheme;
        proxy_read_timeout 60s;
    }
}
EOF
nginx -t
systemctl enable --now nginx >/dev/null
systemctl reload nginx

cat > /etc/logrotate.d/agridispatch <<EOF
$APP_DIR/logs/*.log {
    weekly
    rotate 8
    compress
    missingok
    copytruncate
}
EOF

log "完成。下一步：上傳程式並執行 server-deploy.sh；確認 http://$DOMAIN/web/ 可開後，再執行 enable-https.sh。"
