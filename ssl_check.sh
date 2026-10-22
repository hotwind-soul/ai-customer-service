#!/usr/bin/env bash
# 第 33 周：SSL 证书到期监控
# 用法：
#   手动：  bash ssl_check.sh
#   定时：  crontab -e 加  `0 8 * * *  cd /opt/ai-customer-service && bash ssl_check.sh >> /opt/backups/aics/ssl.log 2>&1`
#
# 说明：
#   - 用 openssl 取证书 notAfter，计算剩余天数；< WARN_DAYS 则告警（默认 15 天）。
#   - 必须在服务器运行（依赖 openssl）。DOMAIN 填真实对外域名（如 ai.zhenmeiqq.online）。
#
# 环境变量（可选覆盖）：
#   DOMAIN / PORT / WARN_DAYS / WEBHOOK

set -euo pipefail

DOMAIN="${DOMAIN:-ai.zhenmeiqq.online}"
PORT="${PORT:-443}"
WARN_DAYS="${WARN_DAYS:-15}"
WEBHOOK="${WEBHOOK:-}"

notify() {
  local msg="$1"
  echo "$(date '+%F %T') [ALERT] $msg"
  if [ -n "$WEBHOOK" ]; then
    curl -s -o /dev/null -X POST "$WEBHOOK" \
      -H 'Content-Type: application/json' \
      -d "{\"msgtype\":\"text\",\"text\":{\"content\":\"[AICS SSL 告警] $msg\"}}" || true
  fi
}

# 取证书过期时间
NOT_AFTER="$(echo | openssl s_client -servername "$DOMAIN" -connect "$DOMAIN:$PORT" 2>/dev/null \
  | openssl x509 -noout -enddate 2>/dev/null | cut -d= -f2)"

if [ -z "$NOT_AFTER" ]; then
  notify "无法获取 $DOMAIN:$PORT 证书（域名解析/网络/证书均可能异常）"
  exit 1
fi

# 计算剩余天数（date 兼容 GNU/Linux）
NOW_TS="$(date +%s)"
EXP_TS="$(date -d "$NOT_AFTER" +%s)"
LEFT_DAYS=$(( (EXP_TS - NOW_TS) / 86400 ))

echo "$(date '+%F %T') $DOMAIN 证书到期: $NOT_AFTER  剩余: ${LEFT_DAYS} 天"

if [ "$LEFT_DAYS" -lt "$WARN_DAYS" ]; then
  notify "$DOMAIN 证书将在 ${LEFT_DAYS} 天后到期（<${WARN_DAYS}天），请尽快续期"
  exit 1
fi
