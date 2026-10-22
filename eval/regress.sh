#!/usr/bin/env bash
# 一键 Harness 回归：数据库 ai_regression_case 是唯一用例源，网页和 CLI 共用服务端断言。
# 用法：
#   ./eval/regress.sh            # 普通回归（标签=日期时间）
# 依赖：8080 本地实例在跑、Node 22+，以及管理员鉴权环境变量（见 harness_regression_test.js）。
set -e
cd "$(dirname "$0")/.." || exit 1

NODE="${NODE:-node}"
CUR="eval/harness_regression_results.json"
BASELINE="eval/harness_regression_results.baseline.json"

if [ -f "$CUR" ]; then
  cp "$CUR" "$BASELINE"
  echo "已保存上一轮基线：$BASELINE"
fi

"$NODE" eval/harness_regression_test.js
