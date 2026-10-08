#!/usr/bin/env bash
# =============================================================================
# 智農派工 DEV 主機一次性設定（Oracle Cloud Always Free：Oracle Linux 9、Ampere ARM）
# 用法：sudo bash server-setup.sh <DEV 網域，例如 dev.1-2-3-4.sslip.io>
# 可重複執行（已完成的步驟會略過）。完成後以 server-deploy.sh 部署程式，再以 enable-https.sh 申請憑證。
#
# 安裝：Java 17、Nginx、Podman、Oracle Database 23ai Free（容器，固定內部 IP 10.88.0.50，不對外）
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
# 資料庫容器固定內部 IP（podman 預設網路 10.88.0.0/16），後端直接連此位址。
# 不用 -p 127.0.0.1:1521 轉送：主機 route_localnet=0 時，轉送到 127.0.0.1 的連線不會進容器，JDBC 會卡在握手
ORA_IP=10.88.0.50

log() { echo -e "\n[setup] $*"; }
rand() { openssl rand -base64 48 | tr -dc 'A-Za-z0-9' | head -c "${1:-24}"; }

log "1/7 套件：Java 17、Nginx、Podman、certbot"
dnf install -y java-17-openjdk-headless nginx podman openssl tar policycoreutils-python-utils >/dev/null
if ! rpm -q oracle-epel-release-el9 >/dev/null 2>&1; then dnf install -y oracle-epel-release-el9 >/dev/null; fi
# Oracle Linux 的 EPEL 套件庫預設停用，只在安裝 certbot 時臨時啟用
dnf install -y --enablerepo=ol9_developer_EPEL certbot python3-certbot-nginx >/dev/null

log "2/7 防火牆與 SELinux（開放 80/443；允許 Nginx 轉送到後端）"
systemctl enable --now firewalld >/dev/null 2>&1 || true
# 只有規則有變才 reload：firewalld reload 會清掉 Podman 容器網路的 iptables 規則，之後須以 podman network reload 補回
if ! firewall-cmd --permanent --query-service=http >/dev/null || ! firewall-cmd --permanent --query-service=https >/dev/null; then
  firewall-cmd --permanent --add-service=http --add-service=https >/dev/null
  firewall-cmd --reload >/dev/null
  podman network reload --all >/dev/null 2>&1 || true
fi
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
AGRI_DB_URL=jdbc:oracle:thin:@//$ORA_IP:1521/FREEPDB1
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
# 舊版設定連 127.0.0.1:1521 → 改為容器 IP
sed -i "s#@//127.0.0.1:1521/#@//$ORA_IP:1521/#" "$ENV_FILE"
chown root:agri "$ENV_FILE"; chmod 640 "$ENV_FILE"; chmod 600 "$ORA_ENV"

log "5/7 Oracle Database 23ai Free（容器）"
# 舊版以 -p 轉送建立的容器：重建為固定 IP（資料在 agri-oradata 磁碟區，不會遺失）
if podman container exists $ORA_NAME && [ "$(podman inspect -f '{{.NetworkSettings.IPAddress}}' $ORA_NAME)" != "$ORA_IP" ]; then
  podman rm -f $ORA_NAME >/dev/null
fi
if ! podman container exists $ORA_NAME; then
  podman pull $ORA_IMAGE
  podman run -d --name $ORA_NAME --restart=always \
    --network podman --ip $ORA_IP \
    -e ORACLE_PWD="$ORACLE_PWD" \
    -v agri-oradata:/opt/oracle/oradata:Z \
    $ORA_IMAGE
fi
systemctl enable --now podman-restart.service >/dev/null 2>&1 || true
echo -n "等待資料庫就緒"
for _ in $(seq 1 120); do
  # 不用 grep -q：pipefail 下 grep 提早結束會讓 podman logs 收到 SIGPIPE，整條管線被判失敗
  if podman logs $ORA_NAME 2>&1 | grep "DATABASE IS READY TO USE" >/dev/null; then echo " ✓"; break; fi
  echo -n "."; sleep 5
done
podman logs $ORA_NAME 2>&1 | grep "DATABASE IS READY TO USE" >/dev/null || { echo; echo "資料庫未就緒，請看 podman logs $ORA_NAME"; exit 1; }

log "6/7 建立應用程式帳號 AGRIDISPATCH（已存在則略過）"
podman exec -i $ORA_NAME sqlplus -s /nolog <<EOF
whenever sqlerror exit failure
connect sys/"$ORACLE_PWD"@//localhost:1521/FREEPDB1 as sysdba
set serveroutput on
declare n number;
begin
  -- lite 映像檔的 FREEPDB1 沒有 USERS 表空間：不存在就建立（資料檔與其他檔案同目錄，自動擴充）
  select count(*) into n from dba_tablespaces where tablespace_name = 'USERS';
  if n = 0 then
    execute immediate q'[create tablespace USERS datafile '/opt/oracle/oradata/FREE/FREEPDB1/users01.dbf' size 200m autoextend on next 100m maxsize 10g]';
    dbms_output.put_line('USERS 表空間已建立');
  end if;
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
# log 交給 journald（SELinux 不允許 systemd 直接寫 /opt 下的檔案）：journalctl -u agridispatch
StandardOutput=journal
StandardError=journal
SyslogIdentifier=agridispatch

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

# journald 保留上限（DEV）：最多 500MB
mkdir -p /etc/systemd/journald.conf.d
printf '[Journal]
SystemMaxUse=500M
' > /etc/systemd/journald.conf.d/agridispatch.conf
systemctl restart systemd-journald
rm -f /etc/logrotate.d/agridispatch

log "完成。下一步：上傳程式並執行 server-deploy.sh；確認 http://$DOMAIN/web/ 可開後，再執行 enable-https.sh。"
