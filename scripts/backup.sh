#!/usr/bin/env bash
# AI 客服系统每日备份脚本（服务器上运行）
# 备份内容：PostgreSQL 全库 + aics_uploads 图片卷（不备份含明文密钥的 .env）
# 保留最近 7 天，超期自动删除
#
# 安装（服务器上执行一次）：
#   chmod +x backup.sh
#   crontab -e  添加一行（每天凌晨 3 点备份）：
#   0 3 * * * /root/ai-customer-service/scripts/backup.sh >> /root/backup.log 2>&1

set -euo pipefail

BACKUP_DIR="${BACKUP_DIR:-/root/backups}"
RETENTION_DAYS="${RETENTION_DAYS:-7}"
DB_CONTAINER="${DB_CONTAINER:-aics-postgres}"
DB_NAME="${DB_NAME:-aics}"
DB_USER="${DB_USER:-postgres}"
UPLOADS_VOLUME="${UPLOADS_VOLUME:-aics_uploads}"
WEBHOOK="${WEBHOOK:-}"
DATE=$(date +%Y%m%d_%H%M%S)
DEST="$BACKUP_DIR/$DATE"

notify() {
  local msg="$1"
  echo "$(date '+%F %T') [ALERT] $msg"
  if [ -n "$WEBHOOK" ]; then
    curl -s -o /dev/null -X POST "$WEBHOOK" \
      -H 'Content-Type: application/json' \
      -d "{\"msgtype\":\"text\",\"text\":{\"content\":\"[AICS 备份告警] $msg\"}}" || true
  fi
}

mkdir -p "$DEST"
DEST="$(cd "$DEST" && pwd)"

# 1. 数据库（pg_dump 在 postgres 容器内执行）
if ! docker ps --format '{{.Names}}' | grep -qx "$DB_CONTAINER"; then
  notify "数据库容器 $DB_CONTAINER 未运行，跳过备份"
  exit 1
fi
if ! docker exec "$DB_CONTAINER" pg_dump -U "$DB_USER" "$DB_NAME" | gzip > "$DEST/database.sql.gz"; then
  rm -f "$DEST/database.sql.gz"
  notify "pg_dump 失败，请检查 $DB_CONTAINER 状态"
  exit 1
fi

# 2. 图片素材位于 Docker 命名卷；不存在时失败，避免静默生成空卷备份。
if ! docker volume inspect "$UPLOADS_VOLUME" >/dev/null 2>&1; then
  notify "上传图片卷 $UPLOADS_VOLUME 不存在，备份不完整"
  exit 1
fi
if ! docker run --rm -v "$UPLOADS_VOLUME:/data:ro" -v "$DEST:/backup" alpine:3.20 \
    tar czf /backup/uploads.tar.gz -C /data .; then
  rm -f "$DEST/uploads.tar.gz"
  notify "上传图片卷 $UPLOADS_VOLUME 备份失败"
  exit 1
fi

# 3. 清理过期备份目录；不复制 .env，避免明文密钥进入普通备份。
find "$BACKUP_DIR" -mindepth 1 -maxdepth 1 -type d -mtime +"$RETENTION_DAYS" -exec rm -rf {} \;

echo "[$DATE] 备份完成 → $DEST ($(du -sh $DEST | cut -f1))"

# 恢复方法（需要时）：
#   gunzip -c database.sql.gz | docker exec -i aics-postgres psql -U postgres aics
#   docker run --rm -v aics_uploads:/data -v "$PWD":/backup alpine:3.20 \
#     tar xzf /backup/uploads.tar.gz -C /data
