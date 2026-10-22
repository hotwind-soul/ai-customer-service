# 监控与告警接入

应用自带两套指标出口，本目录提供 Prometheus 抓取与告警配置。

## 指标出口

| 端点 | 内容 | 暴露 |
|---|---|---|
| `:8081/actuator/prometheus` | JVM/HTTP/连接池 + **AI 网关自定义指标（aics_*）** | 仅宿主机回环（compose 发布 127.0.0.1:8081），不公网暴露 |
| `:8080/api/metrics` | 同源数据的 JSON 视图（含 3 小时趋势） | 需管理员（ai.debug 权限） |
| `:8081/actuator/health` | 健康指示器（数据源/Redis） | 容器健康检查 + 运维脚本用 |

`aics_*` 指标清单（均为 Counter/Gauge，可直接写告警表达式）：

| 指标 | 标签 | 含义 |
|---|---|---|
| `aics_model_calls_total` | model, action | 模型调用次数（chat/stream/quality-judge/vision/non-answer） |
| `aics_token_input_total` / `aics_token_output_total` | model | token 用量（成本 = 各模型 token × 当时单价） |
| `aics_failover_total` | - | 主备降级总次数 |
| `aics_primary_circuit_open` | - | 主模型熔断是否打开（1/0） |
| `aics_circuit_bypass_total` | - | 熔断期间直连备用的次数 |
| `aics_primary_consecutive_failures` | - | 主模型连续失败次数 |
| `aics_faq_bypass_total` | result=hit/eligible | FAQ 零模型直回（命中率 = hit/eligible） |
| `aics_retrieval_candidates_total` | source=vector/keyword | 混合检索各层召回量 |
| `aics_retrieval_source_total` | method | 最终结果来源构成（hybrid/vector/keyword） |
| `aics_rerank_total` | result=ok/fail | 精排成败（失败已自动回退原序） |
| `aics_response_cache_total` | result=hit/lookup | 回答缓存命中 |

## 部署（Prometheus）

1. 抓取配置（加到 prometheus.yml 的 scrape_configs）：

```yaml
  - job_name: aics
    metrics_path: /actuator/prometheus
    static_configs:
      - targets: ["<应用所在主机>:8081"]   # compose 已把 8081 发布到宿主机回环地址
```

2. 告警规则（加到 prometheus.yml）：

```yaml
  rule_files:
    - /etc/prometheus/rules/aics-alerts.yml
```

3. `aics-alerts.yml` 覆盖四组告警：可用性（实例下线/5xx）、模型健康（熔断/降级/空回答）、
   检索（精排失败/零召回）、成本（token 异常消耗/缓存命中率）。阈值按实际业务量调整。

## 安全（已内置：管理端口拆分）

`/actuator/**`（含无鉴权的 prometheus 指标）**已从应用端口 8080 拆到独立管理端口 8081**：

- 公网经 Caddy 只能到达 8080，`/actuator/**` 在 8080 上不存在（404），
  模型名称、调用次数、token 消耗等运营信息不再可读
- 8081 在 compose 里只发布到宿主机回环地址（`127.0.0.1:8081`），公网/内网均不可达
- 容器健康检查同时探测 8081（health 指示器：数据源/Redis）与 8080（业务可服务）

纵深防御建议：若担心 8081 被误发布到公网，仍可在反向代理加一条兜底屏蔽
（Caddy `respond @actuator 403` / Nginx `deny all`）。

## 轻量替代（暂不部署 Prometheus 时）

外部拨测监控（UptimeRobot/阿里云拨测等免费档）改用 **`GET /`**——应用端口 8080
返回 200 即服务可达：零服务器负载，覆盖实例下线这一最重要的告警；
数据源/Redis 健康由 8081 的 health 指示器承担（容器健康检查已覆盖）。
