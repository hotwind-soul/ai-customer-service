# AI 客服系统（ai-customer-service）

面向医美/轻医美连锁门店的 AI 客服系统：客户从抖音投流进入企业微信（获客助手/微信客服），由 AI 完成项目咨询、报价、门店推荐、预约与转人工的完整接待链路。

> 结论先行的设计原则、根因实锤的排错纪律、上线前多轮回归取证，是本项目的三条工程底线。

## 功能特性

- **四层决策引擎**：意图识别 → FAQ 旁路（向量距离 ≤ 0.17 直接命中，不进大模型）→ RAG 知识库检索问答 → AI 兜底 + 医疗红线拒答 / 转人工
- **结构化报价与门店检索**：价格以 `project_offer` 表为权威源，门店数据覆盖 30+ 城市，录入自动 geocoding（腾讯地图 WebService）
- **质检模块**：QualityRuleEngine 规则风险扫描（零成本）+ AI 裁判语义评分（merge 取各维度 cap）+ 规则低分自动升级 AI 复核，只有语义评分或人工复核才计入质量分统计
- **运营指标**：响应耗时统计、回复方式构成、近 N 天消息趋势、热点问题 Top（报表 Tab 即运营看板）
- **企微对接**：WeCom Kf 渠道包（回调加解密 / Token 管理 / sync_msg 拉取 / send_msg 回复 / service_state 转人工 / 诊断与运行时监控），人工接待由企微原生承接（接待成员与接待规则在企微后台配置），网页端仅 AI
- **管理后台**：知识库管理、图片素材库、门店/项目管理、质检规则编辑、AI 配置中心（全部 API Key 集中单页、热重载）、密钥管理、会话管理

## 技术栈

| 层 | 选型 |
|---|---|
| 后端 | Java 17 · Spring Boot 3.5 · LangChain4j · Flyway |
| 模型 | ModelGateway 统一网关：DeepSeek（主聊天）+ 通义千问（向量 text-embedding-v3 / 视觉 qwen-vl-max），带超时、failover、熔断 |
| 存储 | PostgreSQL 16 + pgvector（RAG）· Redis 7（短期会话记忆 StateStore） |
| 前端 | Vue 3（CDN IIFE）· admin / index / agent 三端 · SVG 图表 |
| 部署 | Docker Compose（dev / prod 双轨）· 宝塔 Nginx 反代 · HTTPS |

## 架构要点

- **模块化单体**：所有 LLM 调用必须经 `ModelGateway`，业务状态只存 Redis `StateStore`，禁止直连模型 SDK
- **密钥优先级**：`system_config` 表（admin 设置页填写，AES-GCM 加密，热生效）→ `.env` 环境变量兜底；`.env` 一律不入库
- **配置热重载**：`ConfigService` 带 ConcurrentHashMap 缓存，改配置走后台 UI 或 reload 接口，裸 SQL 改库不生效

```
src/main/java/com/example/aics/
├── chat/        # 聊天引擎：ChatEngine、Prompt 模块化组装（PromptAssembler 等）、TurnTrace 决策留痕
├── ai/          # ModelGateway、模型管理、Embedding 切换
├── knowledge/   # RAG：向量检索、OCR、知识文档
├── quality/     # 质检：规则引擎、AI 裁判、批量评测、自动升级
├── report/      # 运营指标：ReportMetrics、响应耗时、报表 API
├── channel/     # 企微 Kf 渠道包（回调/收发/转人工/诊断）
├── agent/       # 坐席端账号与鉴权
├── config/      # 配置中心、密钥管理、权限目录、拦截器
├── store/       # 门店与项目管理（geocoding）
├── image/       # 图片素材库
├── state/       # Redis StateStore
├── audit/       # 审计日志
└── message/     # 消息持久化
```

前端三端（`src/main/resources/static/`）：`admin.html`（当前唯一生产入口）· `index.html`（AI 对话测试工具）· `agent.html`（坐席端，备用）。

## 快速开始（本地开发）

```bash
# 1. 配置环境变量（参考模板，填入数据库密码等）
cp .env.example .env

# 2. 启动 PostgreSQL(pgvector) + Redis
docker compose up -d

# 3. IDEA 中直接运行 AiCustomerServiceApplication（端口 8080）
#    Flyway 自动执行 db/migration 全部迁移（V1~V47）
```

打开 `http://localhost:8080/admin.html`，首次进入后台「设置」页配置模型 API Key（DeepSeek / 通义千问 / 腾讯地图），热重载即时生效。

## 生产部署

```bash
# 服务器 /opt/ai-customer-service 下一键部署：
bash deploy.sh
```

脚本内置：`.env` 启动级变量预检 → 拉取 main → **先构建新镜像**（构建失败不影响线上）→ 切换容器（数据卷保留）→ 健康检查 120s → **失败自动回滚旧镜像** → Flyway 迁移确认。

> 生产 compose 必须带 `-p aics`（容器名/卷名锁定，避免旧容器残留）；`.env` 必填项：`POSTGRES_PASSWORD`、`AICS_CONFIG_ENCRYPTION_KEY`、`AICS_WS_ALLOWED_ORIGINS`、`AICS_PUBLIC_BASE_URL`。

## 测试与评测

- `src/test/`：JUnit 5 单测（Spring MVC 切片 + Mockito）
- `eval/`：离线评测体系——`comprehensive_test.js` 全量回归（65 题）、`eval_run.py` 评测执行、`regress.sh` 回归入口；RAG 评测基线 ~97.9% 准确率 / 0% 幻觉
- `render_admin.js`：admin.html 全 Tab 真实渲染验证（jsdom + Vue 3）
- `scripts/check_frontend.js`：前端静态体检

## 安全约定

- `.env`、密钥、数据 dump、上传文件一律不入库（见 `.gitignore`）
- AI 回复可能被客户截图作为投诉证据，措辞与输出必须严谨可控
- 生产反代 / HTTPS 由宝塔 Nginx 承担；应用与管理端口仅发布到宿主机回环（8080 业务 / 8081 管理，`/actuator/**` 不在业务端口暴露）
- 监控与告警：`deploy/monitoring/`（Prometheus 抓取配置 + 四组告警规则），`/actuator/prometheus` 暴露 `aics_*` 业务指标
