#!/usr/bin/env bash
# =============================================================================
# 智農派工 DEV：部署（每次更新都執行）
# 用法：sudo bash server-deploy.sh
# 前提：deploy.ps1 已把 app.jar、web.tar.gz、schema-ddl.sql（以及選用的 accounts.sql）上傳到 /tmp/agri-deploy/
# 第一次部署：資料表不存在時以 schema-ddl.sql 建表（只有建表，不含示範種子；示範資料由後端 AGRI_SEED_DEMO_DATA 寫入）
# =============================================================================
set -euo pipefail

APP_DIR=/opt/agridispatch
UP=/tmp/agri-deploy
ORA_NAME=agri-oracle
# shellcheck disable=SC1091
source /etc/agridispatch/oracle.env

sql_app() {   # 以 AGRIDISPATCH 執行 stdin 的 SQL
  { echo "whenever sqlerror exit failure"; echo "connect AGRIDISPATCH/\"$AGRI_DB_PASSWORD\"@//localhost:1521/FREEPDB1"; echo "set define off"; cat; echo "exit"; } \
    | podman exec -i $ORA_NAME sqlplus -s /nolog
}

echo "[deploy] 1/4 資料表"
TABLES=$(echo "set heading off feedback off pagesize 0
select count(*) from user_tables where table_name = 'ORDERS';" | sql_app | tr -d '[:space:]')
if [ "$TABLES" = "0" ]; then
  echo "  第一次部署：建立資料表"
  sql_app < $UP/schema-ddl.sql > $UP/schema.log 2>&1 || { tail -20 $UP/schema.log; exit 1; }
  grep -c "Table created" $UP/schema.log | xargs echo "  建立資料表數："
else
  echo "  資料表已存在，略過（schema 變更請另以 migration 處理）"
fi

echo "[deploy] 2/4 程式與網頁"
install -o agri -g agri -m 644 $UP/app.jar $APP_DIR/app.jar.new
mv -f $APP_DIR/app.jar.new $APP_DIR/app.jar
rm -rf $APP_DIR/web.new && mkdir -p $APP_DIR/web.new
tar -xzf $UP/web.tar.gz -C $APP_DIR/web.new
rm -rf $APP_DIR/web.old; [ -d $APP_DIR/web ] && mv $APP_DIR/web $APP_DIR/web.old
mv $APP_DIR/web.new $APP_DIR/web
chown -R agri:agri $APP_DIR/web

echo "[deploy] 3/4 重新啟動後端"
systemctl restart agridispatch.service
for _ in $(seq 1 60); do
  if curl -fs -o /dev/null http://127.0.0.1:3000/api/crops; then break; fi
  sleep 3
done
curl -fs -o /dev/null http://127.0.0.1:3000/api/crops && echo "  後端已啟動" || { echo "  後端未啟動，最後 40 行 log："; tail -40 $APP_DIR/logs/app.log; exit 1; }

echo "[deploy] 4/4 帳號（選用）"
if [ -f $UP/accounts.sql ]; then
  sleep 5   # 等啟動時的種子寫完（管理員帳號）
  sql_app < $UP/accounts.sql | grep -c "merged" | xargs echo "  帳號 merged："
  shred -u $UP/accounts.sql 2>/dev/null || rm -f $UP/accounts.sql
fi
rm -f $UP/app.jar $UP/web.tar.gz
echo "[deploy] 完成"
