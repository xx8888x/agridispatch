#!/usr/bin/env bash
# =============================================================================
# 智農派工 DEV：資料庫備份（agri-backup.timer 每天 03:30 台北時間；手動：sudo /usr/local/sbin/agri-backup）
#
# 容器是 lite 映像檔：沒有 expdp/impdp/rman，也沒有 XDB（Data Pump 匯出不了 DDL、匯入完全不能用），所以備兩份：
#   1. 冷備份 agri_oradata_<時間>.tar.gz：資料庫 shutdown immediate → 以 reflink 複製整個資料磁碟區（瞬間完成）→ startup，
#      停機約 1～2 分鐘；之後才在背景壓縮。可在本機完整還原。保留最近 3 份。
#   2. 資料匯出 agri_data_<時間>.dmp.gz：DBMS_DATAPUMP 只匯資料（建表用版本庫的 schema 與 migrations），
#      供搬到完整版 Oracle（正式環境）時匯入。保留 7 天。
# 成功時更新 last-success（健康檢查據此告警）。還原方式見 Software/deploy/dev/README.md「備份與還原」。
# =============================================================================
set -euo pipefail

ORA_NAME=agri-oracle
DEST=/var/lib/agridispatch/backups
STAGE=/var/lib/agridispatch/backup-stage
LOCK=/run/agri-backup.lock
KEEP_DUMP_DAYS=7
KEEP_COLD=3
STAMP=$(date +%Y%m%d_%H%M%S)
# shellcheck disable=SC1091
source /etc/agridispatch/oracle.env

sql_system() {   # 以 SYSTEM 連 FREEPDB1 執行 stdin 的 SQL
  { echo "whenever sqlerror exit failure"; echo "connect system/\"$ORACLE_PWD\"@//localhost:1521/FREEPDB1"; cat; echo "exit"; } \
    | podman exec -i -e NLS_LANG=AMERICAN_AMERICA.AL32UTF8 $ORA_NAME sqlplus -s /nolog
}

wait_db() {      # 等 FREEPDB1 可連線（最多 10 分鐘）
  for _ in $(seq 120); do
    if echo "select 1 from dual;" | sql_system >/dev/null 2>&1; then return 0; fi
    sleep 5
  done
  echo "[backup] 資料庫重啟後 10 分鐘仍無法連線" >&2
  return 1
}

mkdir -p "$DEST"
chmod 700 "$DEST"

# ---- 1. 冷備份（正常關閉 → reflink 複製 → 重啟 → 背景壓縮） ----
echo "[backup] 1/2 冷備份：關閉資料庫"
VOL=$(podman volume inspect agri-oradata -f '{{.Mountpoint}}')
rm -rf "$STAGE"
date +%s > "$LOCK"   # 健康檢查看到此檔就略過資料庫與後端檢查
# 重新啟動資料庫：容器還在跑就在容器內 startup；容器停了才用 systemd-run --scope 啟動
# （conmon 不能留在 agri-backup.service 的 cgroup 裡，否則服務結束時 systemd 會連資料庫容器一起殺掉）
restart_db() {
  if [ "$(podman inspect -f '{{.State.Running}}' $ORA_NAME 2>/dev/null)" = "true" ]; then
    echo "startup" | podman exec -i $ORA_NAME sqlplus -s / as sysdba || true
  else
    systemd-run --scope --quiet --collect podman start $ORA_NAME >/dev/null || true
  fi
}
trap 'restart_db; rm -f "$LOCK"' EXIT
echo "shutdown immediate" | podman exec -i $ORA_NAME sqlplus -s / as sysdba
cp -a --reflink=always "$VOL" "$STAGE"
restart_db
wait_db
rm -f "$LOCK"
trap - EXIT
echo "[backup] 資料庫已重新啟動，壓縮冷備份"
COLD=$DEST/agri_oradata_$STAMP.tar.gz
tar -C "$STAGE" -cf - . | pigz -p 2 > "$COLD.part"
mv "$COLD.part" "$COLD"
chmod 600 "$COLD"
rm -rf "$STAGE"

# ---- 2. 資料匯出（Data Pump，只匯資料） ----
NAME=agri_data_$STAMP
DPDIR=$(echo "set heading off feedback off pagesize 0
select directory_path from all_directories where directory_name = 'DATA_PUMP_DIR';" | sql_system | tr -d '[:space:]')
[ -n "$DPDIR" ] || { echo "[backup] 找不到 DATA_PUMP_DIR" >&2; exit 1; }
echo "[backup] 2/2 資料匯出 AGRIDISPATCH → $NAME.dmp"
sql_system <<EOF
set serveroutput on
declare
  h  number;
  st varchar2(30);
begin
  h := dbms_datapump.open(operation => 'EXPORT', job_mode => 'SCHEMA', job_name => 'AGRI_BK_$STAMP');
  dbms_datapump.add_file(h, '$NAME.dmp', 'DATA_PUMP_DIR');
  dbms_datapump.add_file(h, '$NAME.log', 'DATA_PUMP_DIR', filetype => dbms_datapump.ku\$_file_type_log_file);
  dbms_datapump.metadata_filter(h, 'SCHEMA_EXPR', 'IN (''AGRIDISPATCH'')');
  dbms_datapump.set_parameter(h, 'INCLUDE_METADATA', 0);
  dbms_datapump.start_job(h);
  dbms_datapump.wait_for_job(h, st);
  dbms_output.put_line('Data Pump 狀態：' || st);
  if st <> 'COMPLETED' then
    -- 失敗的工作會留下 master table，清掉免得累積
    execute immediate 'drop table "AGRI_BK_$STAMP" purge';
    raise_application_error(-20001, 'Data Pump 未完成：' || st);
  end if;
end;
/
EOF
podman cp "$ORA_NAME:$DPDIR/$NAME.dmp" "$DEST/"
podman cp "$ORA_NAME:$DPDIR/$NAME.log" "$DEST/"
podman exec $ORA_NAME rm -f "$DPDIR/$NAME.dmp" "$DPDIR/$NAME.log"
gzip -f "$DEST/$NAME.dmp"
chmod 600 "$DEST/$NAME.dmp.gz" "$DEST/$NAME.log"
grep -q "successfully completed" "$DEST/$NAME.log" || { echo "[backup] 匯出記錄沒有 successfully completed：$DEST/$NAME.log" >&2; exit 1; }

# ---- 保留期限 ----
find "$DEST" -maxdepth 1 \( -name 'agri_data_*' -o -name 'agri_*.part' \) -mtime +$KEEP_DUMP_DAYS -delete
ls -1t "$DEST"/agri_oradata_*.tar.gz 2>/dev/null | tail -n +$((KEEP_COLD + 1)) | xargs -r rm -f

date -Iseconds > "$DEST/last-success"
echo "[backup] 完成：$(du -h "$DEST/$NAME.dmp.gz" | cut -f1) 資料匯出、$(du -h "$COLD" | cut -f1) 冷備份"
