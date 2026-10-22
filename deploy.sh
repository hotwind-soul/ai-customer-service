#!/bin/bash
# AI 客服生产部署脚本（服务器用）
# 用法：把本文件放到 /opt/ai-customer-service/，然后 bash deploy.sh
# 注意：项目名必须锁成 aics（对应卷 aics_pgdata/aics_redisdata/aics_uploads），否则会建空卷丢数据。
set -euo pipefail

cd "$(dirname "$0")"

wait_for_health() {
  local attempts="${1:-24}"
  for i in $(seq 1 "$attempts"); do
    if curl -sf http://localhost:8081/actuator/health >/dev/null 2>&1; then
      return 0
    fi
    sleep 5
  done
  return 1
}

previous_app_image=""
previous_app_ref=""
if docker inspect aics-app >/dev/null 2>&1; then
  previous_app_image="$(docker inspect --format '{{.Image}}' aics-app)"
  previous_app_ref="$(docker inspect --format '{{.Config.Image}}' aics-app)"
fi

rollback_app() {
  if [ -z "$previous_app_image" ] || [ -z "$previous_app_ref" ]; then
    echo "⚠️ 没有可用的旧应用镜像，无法自动回滚"
    return 1
  fi
  echo "==> 正在回滚旧应用镜像 $previous_app_image"
  docker compose -p aics -f docker-compose.prod.yml down || true
  docker image tag "$previous_app_image" "$previous_app_ref"
  docker compose -p aics -f docker-compose.prod.yml up -d --no-build
  if wait_for_health 12; then
    echo "✅ 已恢复到旧版本；本次部署仍按失败退出"
    return 0
  fi
  echo "❌ 旧版本回滚后仍未通过健康检查"
  docker compose -p aics -f docker-compose.prod.yml logs --tail=200 app || true
  return 1
}

echo "==> [0] 校验 .env 必需字段（compose 插值在先，缺 AICS_PUBLIC_BASE_URL 连 ps 都会失败）"
# 仅校验启动级/插值级变量；模型 API Key（DEEPSEEK/DASHSCOPE 等）由 admin 设置页存 DB、env 仅兜底，不在此列。
for key in POSTGRES_PASSWORD AICS_CONFIG_ENCRYPTION_KEY AICS_WS_ALLOWED_ORIGINS AICS_PUBLIC_BASE_URL; do
  if ! grep -qE "^${key}=.+" .env; then
    echo "❌ .env 缺少非空字段 $key，停止部署"
    exit 1
  fi
done

echo "==> [1] 确认 compose 能识别旧项目 aics"
docker compose -p aics -f docker-compose.prod.yml ps

echo "==> [2] 拉最新代码"
git pull origin main

echo "==> [3] 先构建新应用镜像（构建失败不影响当前线上容器）"
docker compose -p aics -f docker-compose.prod.yml build app

echo "==> [4] 切换到新镜像（保留卷，不加 -v）"
docker compose -p aics -f docker-compose.prod.yml down
if ! docker compose -p aics -f docker-compose.prod.yml up -d; then
  echo "❌ 新容器启动失败"
  rollback_app || true
  exit 1
fi

echo "==> [5] 等待启动（最多 120s）"
if wait_for_health 24; then
  echo "✅ 服务健康 UP"
else
  echo "❌ 服务在 120 秒内未通过健康检查"
  docker compose -p aics -f docker-compose.prod.yml ps || true
  docker compose -p aics -f docker-compose.prod.yml logs --tail=200 app || true
  rollback_app || true
  exit 1
fi

echo "==> [6] 容器状态"
docker ps --format '{{.Names}}\t{{.Status}}'

echo "==> [7] 确认新前端由 jar 提供（非空即生效；OffscreenCanvas/reconnect 仅在 index.html）"
curl -s http://localhost:8080/index.html | grep -oE 'OffscreenCanvas|reconnect' | head || true

echo "==> [8] Flyway 迁移确认（V35~V42 应出现 Successfully applied）"
docker logs aics-app 2>&1 | grep -iE "Successfully (applied|validated)" | tail -10 || true

echo "==> 完成。浏览器请硬刷新（Ctrl+Shift+R）验证 WebSocket 重连 / 暗色模式。"
