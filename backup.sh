#!/usr/bin/env bash
# 第 33 周：PostgreSQL 与上传图片卷自动备份（防数据归零）
# 用法：
#   手动跑：  bash backup.sh
#   定时：    crontab -e 加  `30 3 * * *  cd /opt/ai-customer-service && bash backup.sh >> /opt/backups/aics/backup.log 2>&1`
#
# 说明：
#   - 备份在宿主机用 `docker exec aics-postgres pg_dump` 完成（容器内自带客户端，无需宿主机装 PG）。
#   - 数据库和 aics_uploads 卷分别压缩，保留最近 7 天（RETENTION_DAYS）。
#   - 失败会调用 notify()（默认仅写日志；配了 WEBHOOK 则发企微机器人）。
#
# 环境变量（可选覆盖）：
#   BACKUP_DIR / RETENTION_DAYS / DB_CONTAINER / DB_NAME / DB_USER / UPLOADS_VOLUME / WEBHOOK

set -euo pipefail

BACKUP_DIR="${BACKUP_DIR:-/opt/backups/aics}"
RETENTION_DAYS="${RETENTION_DAYS:-7}"
DB_CONTAINER="${DB_CONTAINER:-aics-postgres}"
DB_NAME="${DB_NAME:-aics}"
DB_USER="${DB_USER:-postgres}"
UPLOADS_VOLUME="${UPLOADS_VOLUME:-aics_uploads}"
WEBHOOK="${WEBHOOK:-}"

notify() {
  local msg="$1"
  echo "$(date '+%F %T') [ALERT] $msg"
  if [ -n "$WEBHOOK" ]; then
    curl -s -o /dev/null -X POST "$WEBHOOK" \
      -H 'Content-Type: application/json' \
      -d "{\"msgtype\":\"text\",\"text\":{\"content\":\"[AICS 备份告警] $msg\"}}" || true
  fi
}

mkdir -p "$BACKUP_DIR"
BACKUP_DIR="$(cd "$BACKUP_DIR" && pwd)"
TS="$(date +%Y%m%d_%H%M%S)"
OUT="$BACKUP_DIR/aics_$TS.sql.gz"
UPLOADS_OUT="$BACKUP_DIR/uploads_$TS.tar.gz"

# 容器是否存活
if ! docker ps --format '{{.Names}}' | grep -qx "$DB_CONTAINER"; then
  notify "数据库容器 $DB_CONTAINER 未运行，跳过备份"
  exit 1
fi

# 导出并压缩
if docker exec "$DB_CONTAINER" pg_dump -U "$DB_USER" "$DB_NAME" | gzip > "$OUT"; then
  SIZE="$(du -h "$OUT" | cut -f1)"
  echo "$(date '+%F %T') backup ok: $OUT ($SIZE)"
else
  rm -f "$OUT"
  notify "pg_dump 失败，请检查 $DB_CONTAINER 状态"
  exit 1
fi

# 上传图片实际存放在 Docker 命名卷，不能从项目目录复制。
# 先检查卷是否存在，避免 docker run 静默创建同名空卷并产出“成功”的空备份。
if ! docker volume inspect "$UPLOADS_VOLUME" >/dev/null 2>&1; then
  notify "上传图片卷 $UPLOADS_VOLUME 不存在，备份不完整"
  exit 1
fi

if docker run --rm -v "$UPLOADS_VOLUME:/data:ro" -v "$BACKUP_DIR:/backup" alpine:3.20 \
    tar czf "/backup/uploads_$TS.tar.gz" -C /data . && [ -s "$UPLOADS_OUT" ]; then
  SIZE="$(du -h "$UPLOADS_OUT" | cut -f1)"
  echo "$(date '+%F %T') uploads backup ok: $UPLOADS_OUT ($SIZE)"
else
  rm -f "$UPLOADS_OUT"
  notify "上传图片卷 $UPLOADS_VOLUME 备份失败"
  exit 1
fi

# 清理过期数据库与图片备份。
find "$BACKUP_DIR" -name 'aics_*.sql.gz' -mtime +"$RETENTION_DAYS" -delete
find "$BACKUP_DIR" -name 'uploads_*.tar.gz' -mtime +"$RETENTION_DAYS" -delete
