#!/usr/bin/env bash
# 第 33 周：基础存活监控（应用健康 + 磁盘 + SSL）
# 用法：
#   定时：  */5 * * * *  cd /opt/ai-customer-service && WEBHOOK="https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=你的key" bash health_monitor.sh >> /opt/backups/aics/monitor.log 2>&1
#
# 说明：
#   - 每 5 分钟探一次 /actuator/health，不可达即告警（容器崩溃/OOM/卡死）。
#   - 磁盘使用率 >= DISK_WARN_PCT 告警。
#   - 末尾调用 ssl_check.sh 复用其证书到期告警。
#   - 配了 WEBHOOK（企业微信群机器人）则发消息；否则仅写日志。
#
# 环境变量（可选覆盖）：
#   WEBHOOK / HEALTH_URL / DISK_PATH / DISK_WARN_PCT / DOMAIN(PORT/WARN_DAYS 见 ssl_check.sh)

set -uo pipefail

WEBHOOK="${WEBHOOK:-}"
HEALTH_URL="${HEALTH_URL:-http://localhost:8081/actuator/health}"
DISK_PATH="${DISK_PATH:-/}"
DISK_WARN_PCT="${DISK_WARN_PCT:-85}"
export WEBHOOK   # 让子进程 ssl_check.sh 也能用

notify() {
  local msg="$1"
  echo "$(date '+%F %T') [ALERT] $msg"
  if [ -n "$WEBHOOK" ]; then
    curl -s -o /dev/null -X POST "$WEBHOOK" \
      -H 'Content-Type: application/json' \
      -d "{\"msgtype\":\"text\",\"text\":{\"content\":\"[AICS 监控] $msg\"}}" || true
  fi
}

# 1) 应用健康检查
if curl -sf "$HEALTH_URL" >/dev/null 2>&1; then
  echo "$(date '+%F %T') health: UP"
else
  notify "应用健康检查失败：$HEALTH_URL 不可达（容器可能崩溃/OOM/卡死）"
fi

# 2) 磁盘使用率
USAGE="$(df -P "$DISK_PATH" 2>/dev/null | awk 'NR==2 {gsub("%","",$5); print $5}')"
if [ -n "$USAGE" ] && [ "$USAGE" -ge "$DISK_WARN_PCT" ]; then
  notify "磁盘 $DISK_PATH 使用率 ${USAGE}%（>=${DISK_WARN_PCT}%），请清理避免写满"
fi

# 3) SSL 证书（复用 ssl_check.sh 自带告警；失败不阻断本脚本）
DIR="$(cd "$(dirname "$0")" && pwd)"
bash "$DIR/ssl_check.sh" || true
