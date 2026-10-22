package com.example.aics.quality;

import com.example.aics.audit.AuditService;
import com.example.aics.config.AdminDataScope;
import com.example.aics.config.ConfigService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Service
// 表结构由 Flyway 在运行时创建；项目不共享含凭据的 IntelliJ Data Source。
// 仅关闭 IDE 的表对象解析误报，SQL 语法和参数检查仍然保留。
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
public class ConversationEvaluationService {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<List<Map<String, Object>>> MAP_LIST = new TypeReference<>() {};
    /**
     * 只有 AI 语义复核成功，或人工明确同意/调整后的记录，才是可用于统计的质量分。
     * 纯规则记录只是确定性风险扫描，不能因为“没有命中正则”就被解释成 100 分质量。
     */
    private static final String QUALITY_SCORED_SQL =
            "(COALESCE(e.review_result IN ('agree','adjusted'),FALSE) OR " +
                    "(e.judge_enabled=TRUE AND e.evaluation_version='semantic-v3' " +
                    "AND e.review_result IS DISTINCT FROM 'disagree'))";
    private final JdbcTemplate jdbc;
    private final ConfigService config;
    private final QualityJudgeService judge;
    private final AuditService audit;
    private final QualityRuleEngine rules;
    private static final Logger log = LoggerFactory.getLogger(ConversationEvaluationService.class);

    public ConversationEvaluationService(JdbcTemplate jdbc, ConfigService config,
                                         QualityJudgeService judge, AuditService audit) {
        this.jdbc = jdbc;
        this.config = config;
        this.rules = new QualityRuleEngine(config);
        this.judge = judge;
        this.audit = audit;
    }

    public Map<String, Object> score(String conversationId, String judgeProvider, String actor) {
        return score(conversationId, judgeProvider, actor, null);
    }

    /** 批量任务传入 taskId，使每次尝试可追溯到所属任务。 */
    Map<String, Object> score(String conversationId, String judgeProvider, String actor, Long batchTaskId) {
        String selectedProvider = normalizeJudgeProvider(judgeProvider);
        assertConversationAccess(conversationId);
        List<QualityRuleEngine.Message> messages = loadMessages(conversationId);
        if (messages.isEmpty()) throw new IllegalArgumentException("会话不存在或没有消息");
        int minimum = Math.max(2, config.getInt("quality.minimumMessages", 2));
        if (messages.size() < minimum) throw new IllegalArgumentException("会话消息不足 " + minimum + " 条，暂不评分");
        if (messages.stream().noneMatch(message -> "assistant".equals(message.role()))) {
            throw new IllegalArgumentException("会话没有 AI 回复，不属于 AI 对话评分范围");
        }

        String judgeModel = null;
        if (!"rules".equals(selectedProvider)) {
            if (!config.getBoolean("quality.aiJudgeEnabled", false)) {
                throw new IllegalArgumentException("AI 裁判复核当前未启用，请先在 AI 实验台的“质量评测”设置中开启");
            }
            judgeModel = judge.modelName(selectedProvider);
            if (judgeModel == null || judgeModel.isBlank()) {
                throw new IllegalArgumentException(judgeRoleName(selectedProvider) + "尚未配置可用模型");
            }
        }
        String promptVersion = "rules".equals(selectedProvider) ? "risk-scan-v3" : judge.promptVersion();
        Double judgeTemperature = "rules".equals(selectedProvider) ? null : judge.temperature();
        long startedNanos = System.nanoTime();
        long attemptId = createAttempt(conversationId, batchTaskId, selectedProvider, judgeModel,
                judgeTemperature, promptVersion, actor);

        QualityRuleEngine.Score ruleScore;
        try {
            ruleScore = rules.evaluate(messages);
        } catch (RuntimeException e) {
            failAttempt(attemptId, null, "rule_engine_error", safeMessage(e), startedNanos);
            throw e;
        }
        // 规则低分自动升级 AI 语义复核：纯规则评分只是确定性风险扫描，低分更可能是真实质量问题，
        // 若 AI 语义复核已启用且自动评测模型可用，则自动切换到语义复核，避免低分被埋没。
        // 升级失败走既有 saveJudgeFailure 路径（保留规则分+失败诊断，不覆盖历史质量分）；
        // 失败记录同样占用评测行，定时任务的 NOT EXISTS 不会对同一会话重复触发，无循环风险。
        boolean autoUpgraded = false;
        if ("rules".equals(selectedProvider)
                && config.getBoolean("quality.ruleLowScoreAiRescoreEnabled", true)
                && config.getBoolean("quality.aiJudgeEnabled", false)
                && ruleScore.total() < Math.max(0, Math.min(100, config.getInt("quality.lowScoreThreshold", 70)))) {
            String upgradeProvider = resolveRuleUpgradeProvider();
            if (upgradeProvider != null) {
                judgeModel = judge.modelName(upgradeProvider);
                selectedProvider = upgradeProvider;
                promptVersion = judge.promptVersion();
                judgeTemperature = judge.temperature();
                autoUpgraded = true;
                log.info("规则风险分低于阈值({})，自动升级 AI 语义复核：conversationId={}, ruleScore={}, provider={}, model={}",
                        config.getInt("quality.lowScoreThreshold", 70), conversationId,
                        ruleScore.total(), upgradeProvider, judgeModel);
            }
        }
        QualityRuleEngine.Score finalScore = ruleScore;
        QualityRuleEngine.Score judgeScore = null;
        JudgeFailure judgeFailure = null;
        if (!"rules".equals(selectedProvider)) {
            try {
                QualityJudgeService.JudgeResult result = judge.judge(messages, selectedProvider);
                judgeScore = result.score();
                selectedProvider = result.provider();
                judgeModel = result.modelName();
                promptVersion = result.promptVersion();
                finalScore = merge(ruleScore, judgeScore);
            } catch (Exception e) {
                judgeFailure = classifyJudgeFailure(e);
                log.warn("AI 语义复核失败：conversationId={}, provider={}, model={}, code={}",
                        conversationId, selectedProvider, judgeModel, judgeFailure.code(), e);
            }
        }
        FailureSaveResult failedSave = null;
        long evaluationId;
        try {
            if (judgeFailure != null) {
                failedSave = saveJudgeFailure(conversationId, ruleScore, selectedProvider, judgeModel, judgeFailure);
                evaluationId = failedSave.evaluationId();
                failAttempt(attemptId, evaluationId, judgeFailure.code(), judgeFailure.userMessage(),
                        startedNanos, ruleScore, promptVersion, selectedProvider, judgeModel);
            } else {
                evaluationId = save(conversationId, ruleScore, judgeScore, finalScore,
                        "rules".equals(selectedProvider) ? null : selectedProvider, judgeModel);
                succeedAttempt(attemptId, evaluationId, ruleScore, judgeScore, finalScore,
                        promptVersion, selectedProvider, judgeModel, startedNanos);
            }
        } catch (RuntimeException e) {
            failAttempt(attemptId, null, "persistence_error", safeMessage(e), startedNanos);
            throw e;
        }
        String auditResult = judgeFailure != null ? "（AI复核失败：" + judgeFailure.code() + "）"
                : judgeScore == null ? "（规则）" : "（规则+AI复核）";
        if (autoUpgraded) auditResult = "（规则低分自动升级AI复核）" + auditResult;
        audit.log("SCORE_CONVERSATION", conversationId + auditResult, actor);
        Map<String, Object> result = detail(evaluationId);
        if (autoUpgraded) result.put("autoUpgradedToAi", true);
        if (judgeFailure != null) {
            String warning = judgeRoleName(selectedProvider) + "复核失败：" + judgeFailure.userMessage()
                    + (failedSave.preservedExistingScore()
                    ? "；已保留上一次成功的语义评分"
                    : "；本次仅保留规则风险扫描，尚未生成质量分");
            result.put("warning", warning);
            result.put("judgeErrorCode", judgeFailure.code());
            result.put("judgeErrorMessage", judgeFailure.userMessage());
            result.put("judgeErrorProvider", selectedProvider);
            result.put("judgeErrorModel", judgeModel);
            result.put("judgeErrorPreservedScore", failedSave.preservedExistingScore());
        }
        return result;
    }

    /** 手动异步批量评测提交前固化候选会话和模型，避免后台线程丢失请求数据范围。 */
    public BatchPlan prepareBatch(List<String> requestedIds, String judgeProvider) {
        String selectedProvider = normalizeJudgeProvider(judgeProvider);
        String modelName = null;
        if (!"rules".equals(selectedProvider)) {
            if (!config.getBoolean("quality.aiJudgeEnabled", false)) {
                throw new IllegalArgumentException("AI 裁判复核当前未启用，请先在 AI 实验台的“质量评测”设置中开启");
            }
            modelName = judge.modelName(selectedProvider);
            if (modelName == null || modelName.isBlank()) {
                throw new IllegalArgumentException(judgeRoleName(selectedProvider) + "尚未配置可用模型");
            }
        }
        int limit = Math.max(1, Math.min(config.getInt("quality.batchLimit", 20), 50));
        List<String> ids = new ArrayList<>();
        if (requestedIds != null) {
            requestedIds.stream().filter(id -> id != null && !id.isBlank())
                    .distinct().limit(limit).forEach(ids::add);
        }
        if (ids.isEmpty()) {
            ids.addAll("rules".equals(selectedProvider)
                    ? selectUnevaluatedConversationIds(limit, 1.0)
                    : selectAutoScoreCandidates(limit, 1.0));
        } else {
            ids.forEach(this::assertConversationAccess);
        }
        String promptVersion = "rules".equals(selectedProvider) ? "risk-scan-v3" : judge.promptVersion();
        Double temperature = "rules".equals(selectedProvider) ? null : judge.temperature();
        return new BatchPlan(List.copyOf(ids), selectedProvider, modelName, promptVersion, temperature);
    }

    public Map<String, Object> batchScore(List<String> requestedIds, String judgeProvider, String actor) {
        String selectedProvider = normalizeJudgeProvider(judgeProvider);
        int limit = Math.max(1, Math.min(config.getInt("quality.batchLimit", 20), 50));
        List<String> ids = new ArrayList<>();
        if (requestedIds != null) {
            requestedIds.stream().filter(id -> id != null && !id.isBlank()).distinct().limit(limit).forEach(ids::add);
        }
        if (ids.isEmpty()) ids.addAll("rules".equals(selectedProvider)
                ? selectUnevaluatedConversationIds(limit, 1.0)
                : selectAutoScoreCandidates(limit, 1.0));
        List<Map<String, Object>> rows = new ArrayList<>();
        List<Map<String, String>> failures = new ArrayList<>();
        long intervalMs = "rules".equals(selectedProvider) ? 0
                : Math.max(0, Math.min(config.getInt("quality.batchScoreIntervalMs", 1000), 10000));
        for (int index = 0; index < ids.size(); index++) {
            String id = ids.get(index);
            try {
                Map<String, Object> row = score(id, selectedProvider, actor);
                rows.add(row);
                if (!"rules".equals(selectedProvider) && !Boolean.TRUE.equals(row.get("qualityScored"))) {
                    failures.add(Map.of("conversationId", id,
                            "error", String.valueOf(row.getOrDefault("warning", "AI 语义复核未成功"))));
                }
            }
            catch (RuntimeException e) { failures.add(Map.of("conversationId", id, "error", safeMessage(e))); }
            if (intervalMs > 0 && index < ids.size() - 1 && !Thread.currentThread().isInterrupted()) {
                try { Thread.sleep(intervalMs); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
            }
        }
        return Map.of("requested", ids.size(), "scored", ids.size() - failures.size(), "failed", failures.size(),
                "rows", rows, "failures", failures);
    }

    /**
     * 自动定时评测：抽取「从未评测」或「仅有规则风险扫描」的会话，
     * 按采样率抽样，逐条评分并限速；所选 AI 模型不可用时保留待评状态。
     * 供 QualityAutoScoreScheduler 调用。受 quality.autoScoreEnabled 开关保护，
     * 模型由 quality.autoScoreProvider 指定（默认 eval），数量由 quality.autoScoreLimit 限制，
     * 到达配置窗口结束时间即停止本轮。
     */
    public Map<String, Object> autoScore() {
        if (!config.getBoolean("quality.autoScoreEnabled", false)) {
            return Map.of("skipped", true, "reason", "autoScoreDisabled");
        }
        // 解析用户选择的评测模型（默认 eval），非法值回退 eval
        String chosen = String.valueOf(config.get("quality.autoScoreProvider", "eval"))
                .trim().toLowerCase(java.util.Locale.ROOT);
        if (!List.of("rules", "primary", "fallback", "eval").contains(chosen)) chosen = "eval";
        String provider;
        if ("rules".equals(chosen)) {
            provider = "rules";
        } else if (!config.getBoolean("quality.aiJudgeEnabled", false)) {
            return Map.of("skipped", true, "reason", "aiJudgeDisabled", "provider", chosen);
        } else {
            try {
                String name = judge.modelName(chosen);
                if (name == null || name.isBlank()) {
                    return Map.of("skipped", true, "reason", "judgeModelUnavailable", "provider", chosen);
                }
                provider = chosen;
            } catch (Exception ignored) {
                return Map.of("skipped", true, "reason", "judgeModelUnavailable", "provider", chosen);
            }
        }
        int limit = Math.max(1, Math.min(config.getInt("quality.autoScoreLimit", 50), 500));
        double sampleRate = Math.max(0.0, Math.min(config.getDouble("quality.autoScoreSampleRate", 0.2), 1.0));
        long intervalMs = Math.max(0, config.getInt("quality.autoScoreIntervalMs", 300));
        LocalDateTime end = autoScoreEndTime();
        List<String> ids = "rules".equals(provider)
                ? selectUnevaluatedConversationIds(limit, sampleRate)
                : selectAutoScoreCandidates(limit, sampleRate);
        int scored = 0;
        List<Map<String, String>> failures = new ArrayList<>();
        for (String id : ids) {
            if (end != null && LocalDateTime.now(ZoneId.of("Asia/Shanghai")).isAfter(end)) break; // 到达结束时间停止
            try {
                Map<String, Object> row = score(id, provider, "scheduler");
                if ("rules".equals(provider) || Boolean.TRUE.equals(row.get("qualityScored"))) scored++;
                else failures.add(Map.of("conversationId", id,
                        "error", String.valueOf(row.getOrDefault("warning", "AI 语义复核未成功"))));
            } catch (RuntimeException e) {
                failures.add(Map.of("conversationId", id, "error", safeMessage(e)));
            }
            if (intervalMs > 0 && !Thread.currentThread().isInterrupted()) {
                try { Thread.sleep(intervalMs); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
            }
        }
        double failRate = ids.isEmpty() ? 0.0 : (double) failures.size() / ids.size();
        boolean alert = failRate >= config.getDouble("quality.autoScoreFailThreshold", 0.05);
        String summary = String.format("autoScore 完成：候选=%d 已评=%d 失败=%d 失败率=%.1f%% 模型=%s%s",
                ids.size(), scored, failures.size(), failRate * 100.0, provider,
                alert ? " [ALERT 失败率超阈值]" : "");
        if (alert) log.warn(summary); else log.info(summary);
        return Map.of("requested", ids.size(), "scored", scored, "failed", failures.size(),
                "provider", provider, "alert", alert, "failRate", failRate);
    }

    /**
     * 自动评测候选：包含「从未评测」或「尚未成功完成 AI / 人工语义评分」的会话，
     * 以便夜间 AI 评测把规则基线升级为 AI 评分（评测记录按会话 upsert，不会重复建行）。
     */
    private List<String> selectAutoScoreCandidates(int limit, double sampleRate) {
        return selectEvaluationCandidates(limit, sampleRate, true);
    }

    private LocalDateTime autoScoreEndTime() {
        int h = config.getInt("quality.autoScoreEndHour", 6);
        int m = config.getInt("quality.autoScoreEndMinute", 0);
        try {
            return LocalDateTime.now(ZoneId.of("Asia/Shanghai"))
                    .withHour(Math.min(23, Math.max(0, h)))
                    .withMinute(Math.min(59, Math.max(0, m)))
                    .withSecond(0).withNano(0);
        } catch (Exception e) { return null; }
    }

    /**
     * 新对话默认风险扫描：为近期有活动、消息达标、且【尚无任何评测记录】的对话自动跑规则检查，
     * 作为每对话的风险基线（零成本、不消耗模型，不是质量分）。受 quality.newConvRuleEvalEnabled 控制。
     * 与夜间自动评测解耦：本方法只创建确定性风险扫描记录，后续由 AI 语义评测流程升级。
     */
    public void ruleScoreNewConversations() {
        if (!config.getBoolean("quality.newConvRuleEvalEnabled", true)) return;
        int minimum = Math.max(2, config.getInt("quality.minimumMessages", 2));
        int scanWindowMinutes = Math.max(1, config.getInt("quality.newConvRuleEvalLookbackMinutes", 15));
        List<String> ids = jdbc.queryForList(
                "SELECT c.conversation_id FROM conversation c " +
                        "WHERE c.update_time >= now() - (? * interval '1 minute') " +
                        "AND (SELECT count(*) FROM message m WHERE m.conversation_id=c.conversation_id) >= ? " +
                        "AND EXISTS (SELECT 1 FROM message m WHERE m.conversation_id=c.conversation_id AND m.role='assistant') " +
                        "AND NOT EXISTS (SELECT 1 FROM ai_conversation_evaluation e WHERE e.conversation_id=c.conversation_id) " +
                        "ORDER BY c.update_time DESC LIMIT 50",
                String.class, scanWindowMinutes, minimum);
        int done = 0;
        for (String id : ids) {
            try { score(id, "rules", "auto-rule"); done++; }
            catch (RuntimeException ignored) { /* 不完整/无回复等跳过 */ }
        }
        if (done > 0) log.info("新对话默认规则评测完成：{} 条", done);
    }

    private List<String> selectUnevaluatedConversationIds(int limit, double sampleRate) {
        return selectEvaluationCandidates(limit, sampleRate, false);
    }

    /** SQL 仅由固定片段、参数占位符和 AdminDataScope 的受控条件构成，不拼接请求值。 */
    @SuppressWarnings("SqlSourceToSinkFlow")
    private List<String> selectEvaluationCandidates(int limit, double sampleRate, boolean includeRiskBaselines) {
        int minimum = Math.max(2, config.getInt("quality.minimumMessages", 2));
        StringBuilder sql = new StringBuilder(
                "SELECT c.conversation_id FROM conversation c " +
                        "WHERE (SELECT count(*) FROM message m WHERE m.conversation_id=c.conversation_id) >= ? " +
                        "AND EXISTS (SELECT 1 FROM message m WHERE m.conversation_id=c.conversation_id AND m.role='assistant') ");
        if (includeRiskBaselines) {
            sql.append("AND (NOT EXISTS (SELECT 1 FROM ai_conversation_evaluation e WHERE e.conversation_id=c.conversation_id) ")
                    .append("OR EXISTS (SELECT 1 FROM ai_conversation_evaluation e WHERE e.conversation_id=c.conversation_id AND NOT ")
                    .append(QUALITY_SCORED_SQL).append(")) ");
        } else {
            sql.append("AND NOT EXISTS (SELECT 1 FROM ai_conversation_evaluation e WHERE e.conversation_id=c.conversation_id) ");
        }
        List<Object> args = new ArrayList<>();
        args.add(minimum);
        if (sampleRate > 0.0 && sampleRate < 1.0) {
            sql.append("AND random() < ? ");
            args.add(sampleRate);
        }
        AdminDataScope.appendAnd(sql, args, "c");
        sql.append("ORDER BY c.update_time DESC, c.id DESC LIMIT ?");
        args.add(limit);
        return jdbc.queryForList(sql.toString(), String.class, args.toArray());
    }

    /** 返回当前生效（默认+配置覆盖）的质量评分规则，供管理端“评分规则”编辑面板使用。 */
    public List<QualityRuleEngine.RuleView> effectiveRules() {
        return rules.effectiveRules();
    }

    /**
     * 保存质量评分规则配置。入参为各规则的覆盖项（enabled/deductions/suggestion/pattern），
     * 仅保留与默认不同的差异写入 system_config.quality.rules.json，立即生效。
     * /api/quality/** 已由 AdminAuthInterceptor 要求 quality.manage 权限。
     */
    @Transactional
    public Map<String, Object> updateRules(Map<String, QualityRuleEngine.RuleConfig> incoming, String actor) {
        if (incoming == null) incoming = Map.of();
        Map<String, QualityRuleEngine.RuleConfig> delta = new LinkedHashMap<>();
        for (Map.Entry<String, QualityRuleEngine.RuleConfig> entry : incoming.entrySet()) {
            String code = entry.getKey();
            if (!QualityRuleEngine.knownRuleCodes().contains(code)) {
                throw new IllegalArgumentException("未知的质量规则：" + code);
            }
            QualityRuleEngine.RuleConfig value = entry.getValue();
            if (value != null && value.pattern() != null && !value.pattern().isBlank()) {
                try { Pattern.compile(value.pattern()); }
                catch (Exception e) { throw new IllegalArgumentException("规则 " + code + " 的正则无效：" + value.pattern()); }
            }
            if (value != null && value.deductions() != null) {
                for (Map.Entry<String, Integer> d : value.deductions().entrySet()) {
                    Integer v = d.getValue();
                    if (v == null || v < 0 || v > 50) {
                        throw new IllegalArgumentException("规则 " + code + " 维度 " + d.getKey() + " 的扣分值应在 0-50 之间");
                    }
                }
            }
            QualityRuleEngine.RuleConfig override = QualityRuleEngine.computeOverride(code, value);
            if (override != null) delta.put(code, override);
        }
        String json;
        try { json = JSON.writeValueAsString(delta); }
        catch (Exception e) { throw new IllegalArgumentException("规则配置序列化失败：" + e.getMessage()); }
        config.set("quality.rules.json", json);
        audit.log("UPDATE_QUALITY_RULES", "更新质量评分规则 " + delta.size() + " 条覆盖", actor);
        return Map.of("ok", true, "overrides", delta.size(), "rules", rules.effectiveRules());
    }

    public Map<String, Object> judgeModels() {
        List<Map<String, Object>> models = new ArrayList<>();
        models.add(Map.of("provider", "rules", "label", "仅规则风险扫描（非质量分）", "modelName", ""));
        for (var model : judge.models()) {
            models.add(Map.of("provider", model.provider(), "label", model.label(),
                    "displayName", model.displayName(), "modelName", model.modelName()));
        }
        return Map.of("aiJudgeEnabled", config.getBoolean("quality.aiJudgeEnabled", false), "models", models);
    }

    public Map<String, Object> overview() {
        int low = Math.max(0, Math.min(config.getInt("quality.lowScoreThreshold", 70), 100));
        AdminDataScope.Condition scope = AdminDataScope.condition("c");
        String scopeWhere = scope.restricted() ? " WHERE " + scope.sql() : "";
        List<Object> totalArgs = new ArrayList<>();
        totalArgs.add(low);
        totalArgs.addAll(scope.args());
        Map<String, Object> totals = jdbc.queryForMap(
                "SELECT count(*) AS \"evaluationCount\", " +
                        "count(*) FILTER (WHERE " + QUALITY_SCORED_SQL + ") AS \"scoredCount\", " +
                        "count(*) FILTER (WHERE NOT " + QUALITY_SCORED_SQL + ") AS \"pendingSemanticCount\", " +
                        "COALESCE(round(avg(e.total_score) FILTER (WHERE " + QUALITY_SCORED_SQL + "),1),0) AS \"averageScore\", " +
                        "count(*) FILTER (WHERE " + QUALITY_SCORED_SQL + " AND e.total_score < ?) AS \"lowScoreCount\", " +
                        "count(*) FILTER (WHERE " + QUALITY_SCORED_SQL + " AND e.status='scored') AS \"pendingReviewCount\" " +
                        "FROM ai_conversation_evaluation e JOIN conversation c ON c.conversation_id=e.conversation_id" +
                        scopeWhere, totalArgs.toArray());
        Map<String, Object> dimensions = jdbc.queryForMap(
                "SELECT COALESCE(round(avg(e.intent_score) FILTER (WHERE " + QUALITY_SCORED_SQL + "),1),0) AS intent, " +
                        "COALESCE(round(avg(e.accuracy_score) FILTER (WHERE " + QUALITY_SCORED_SQL + "),1),0) AS accuracy, " +
                        "COALESCE(round(avg(e.context_score) FILTER (WHERE " + QUALITY_SCORED_SQL + "),1),0) AS context, " +
                        "COALESCE(round(avg(e.safety_score) FILTER (WHERE " + QUALITY_SCORED_SQL + "),1),0) AS safety, " +
                        "COALESCE(round(avg(e.naturalness_score) FILTER (WHERE " + QUALITY_SCORED_SQL + "),1),0) AS naturalness, " +
                        "COALESCE(round(avg(e.resolution_score) FILTER (WHERE " + QUALITY_SCORED_SQL + "),1),0) AS resolution, " +
                        "COALESCE(round(avg(e.handoff_score) FILTER (WHERE " + QUALITY_SCORED_SQL + "),1),0) AS handoff FROM ai_conversation_evaluation e " +
                        "JOIN conversation c ON c.conversation_id=e.conversation_id" + scopeWhere,
                scope.args().toArray());
        List<Map<String, Object>> trend = jdbc.queryForList(
                "SELECT d::date AS day, COALESCE(round(avg(e.total_score),1),0) AS score, count(e.id) AS count " +
                        "FROM generate_series(current_date - interval '13 day', current_date, interval '1 day') d " +
                        "LEFT JOIN ai_conversation_evaluation e ON e.evaluated_at::date=d::date AND " + QUALITY_SCORED_SQL +
                        (scope.restricted() ? " AND EXISTS (SELECT 1 FROM conversation c WHERE " +
                                "c.conversation_id=e.conversation_id AND " + scope.sql() + ")" : "") +
                        " GROUP BY d ORDER BY d", scope.args().toArray());
        Map<String, Integer> issueCounts = new LinkedHashMap<>();
        List<String> jsonRows = jdbc.queryForList(
                "SELECT e.issues_json FROM ai_conversation_evaluation e " +
                        "JOIN conversation c ON c.conversation_id=e.conversation_id" + scopeWhere +
                        " ORDER BY e.evaluated_at DESC LIMIT 500", String.class, scope.args().toArray());
        for (String json : jsonRows) {
            for (Map<String, Object> issue : parseList(json)) {
                String category = String.valueOf(issue.getOrDefault("category", "其他"));
                issueCounts.merge(category, 1, Integer::sum);
            }
        }
        List<Map<String, Object>> commonIssues = issueCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).limit(8)
                .map(entry -> Map.<String, Object>of("category", entry.getKey(), "count", entry.getValue())).toList();
        Map<String, Object> result = new LinkedHashMap<>(totals);
        result.put("lowScoreThreshold", low);
        result.put("dimensions", dimensions);
        result.put("trend", trend);
        result.put("commonIssues", commonIssues);
        return result;
    }

    @SuppressWarnings("SqlSourceToSinkFlow") // where 仅含固定条件、占位符和受控的数据范围片段
    public Map<String, Object> query(Integer days, Integer minScore, Integer maxScore, String status,
                                     String channel, String issueType, Integer page, Integer pageSize) {
        int p = page == null || page < 1 ? 1 : page;
        int size = pageSize == null || pageSize < 1 ? 20 : Math.min(pageSize, 100);
        StringBuilder where = new StringBuilder(" WHERE 1=1 ");
        List<Object> args = new ArrayList<>();
        if (days != null && days > 0) { where.append("AND e.evaluated_at >= now() - (? * interval '1 day') "); args.add(days); }
        if (minScore != null || maxScore != null) where.append("AND ").append(QUALITY_SCORED_SQL).append(' ');
        if (minScore != null) { where.append("AND e.total_score >= ? "); args.add(Math.max(0, minScore)); }
        if (maxScore != null) { where.append("AND e.total_score <= ? "); args.add(Math.min(100, maxScore)); }
        String statusCondition = evaluationStatusCondition(status);
        if (!statusCondition.isBlank()) where.append("AND ").append(statusCondition).append(' ');
        if (channel != null && !channel.isBlank()) { where.append("AND c.channel = ? "); args.add(channel.trim()); }
        if (issueType != null && !issueType.isBlank()) { where.append("AND e.issues_json ILIKE ? "); args.add("%" + issueType.trim() + "%"); }
        AdminDataScope.appendAnd(where, args, "c");
        Integer total = jdbc.queryForObject("SELECT count(*) FROM ai_conversation_evaluation e " +
                "JOIN conversation c ON c.conversation_id=e.conversation_id" + where, Integer.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args); pageArgs.add(size); pageArgs.add((p - 1) * size);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT e.id, e.conversation_id AS \"conversationId\", c.user_id AS \"userId\", " +
                        "COALESCE(c.channel,'web') AS channel, e.total_score AS \"totalScore\", e.rule_score AS \"riskScore\", " +
                        QUALITY_SCORED_SQL + " AS \"qualityScored\", e.grade, e.status, e.evaluation_version AS \"evaluationVersion\", " +
                        "e.review_result AS \"reviewResult\", e.judge_enabled AS \"judgeEnabled\", " +
                        "e.judge_provider AS \"judgeProvider\", e.judge_model AS \"judgeModel\", " +
                        "e.judge_error_code AS \"judgeErrorCode\", e.judge_error_message AS \"judgeErrorMessage\", " +
                        "e.judge_error_provider AS \"judgeErrorProvider\", e.judge_error_model AS \"judgeErrorModel\", " +
                        "e.judge_error_at AS \"judgeErrorAt\", e.evaluated_at AS \"evaluatedAt\", " +
                        "e.reviewed_at AS \"reviewedAt\", " +
                        "e.issues_json AS \"issuesJson\", " +
                        "(SELECT content FROM message m WHERE m.conversation_id=c.conversation_id AND m.role='user' ORDER BY m.create_time,m.id LIMIT 1) AS \"firstQuestion\", " +
                        "(SELECT count(*) FROM message m WHERE m.conversation_id=c.conversation_id) AS \"messageCount\" " +
                        "FROM ai_conversation_evaluation e JOIN conversation c ON c.conversation_id=e.conversation_id" + where +
                        evaluationActivityOrderSql() + " LIMIT ? OFFSET ?", pageArgs.toArray());
        rows.forEach(row -> row.put("issues", parseList((String) row.remove("issuesJson"))));
        return Map.of("total", total == null ? 0 : total, "page", p, "pageSize", size, "rows", rows);
    }

    /**
     * 页面上的状态是业务阶段，不直接暴露历史表里的 scored/reviewed 存储细节。
     * 风险扫描记录虽然数据库 status=scored，但尚未形成质量分，不能被“待人工复核”筛选命中。
     */
    static String evaluationStatusCondition(String status) {
        String value = status == null ? "" : status.trim().toLowerCase(java.util.Locale.ROOT);
        return switch (value) {
            case "" -> "";
            case "pending" -> "NOT " + QUALITY_SCORED_SQL;
            case "scored" -> QUALITY_SCORED_SQL + " AND e.status='scored'";
            case "reviewed" -> "e.status='reviewed'";
            case "failed" -> "e.judge_error_code IS NOT NULL";
            default -> throw new IllegalArgumentException("评测状态筛选值不正确");
        };
    }

    /** 最近一次语义评分、人工复核或失败诊断发生后，记录都保持在列表顶部。 */
    static String evaluationActivityOrderSql() {
        return " ORDER BY GREATEST(e.evaluated_at," +
                "COALESCE(e.reviewed_at,e.evaluated_at)," +
                "COALESCE(e.judge_error_at,e.evaluated_at)) DESC,e.id DESC";
    }

    @SuppressWarnings("SqlSourceToSinkFlow") // 只追加 AdminDataScope 生成的受控条件
    public Map<String, Object> detailByConversation(String conversationId) {
        StringBuilder sql = new StringBuilder("SELECT e.id FROM ai_conversation_evaluation e " +
                "JOIN conversation c ON c.conversation_id=e.conversation_id WHERE e.conversation_id=?");
        List<Object> args = new ArrayList<>();
        args.add(conversationId);
        AdminDataScope.appendAnd(sql, args, "c");
        List<Long> ids = jdbc.queryForList(sql.toString(), Long.class, args.toArray());
        if (ids.isEmpty()) throw new IllegalArgumentException("该会话尚未评分");
        return detail(ids.get(0));
    }

    @SuppressWarnings("SqlSourceToSinkFlow") // 只追加 AdminDataScope 生成的受控条件
    public Map<String, Object> detail(long evaluationId) {
        StringBuilder sql = new StringBuilder(
                "SELECT e.id, e.conversation_id AS \"conversationId\", c.user_id AS \"userId\", COALESCE(c.channel,'web') AS channel, " +
                        "e.total_score AS \"totalScore\", " + QUALITY_SCORED_SQL + " AS \"qualityScored\", " +
                        "e.intent_score AS intent, e.accuracy_score AS accuracy, " +
                        "e.context_score AS context, e.safety_score AS safety, e.naturalness_score AS naturalness, " +
                        "e.resolution_score AS resolution, e.handoff_score AS handoff, e.grade, e.rule_score AS \"ruleScore\", " +
                        "e.judge_score AS \"judgeScore\", e.judge_enabled AS \"judgeEnabled\", " +
                        "e.judge_provider AS \"judgeProvider\", e.judge_model AS \"judgeModel\", " +
                        "e.judge_error_code AS \"judgeErrorCode\", e.judge_error_message AS \"judgeErrorMessage\", " +
                        "e.judge_error_provider AS \"judgeErrorProvider\", e.judge_error_model AS \"judgeErrorModel\", " +
                        "e.judge_error_at AS \"judgeErrorAt\", e.issues_json AS \"issuesJson\", " +
                        "e.suggestions_json AS \"suggestionsJson\", e.summary, e.evaluation_version AS \"evaluationVersion\", " +
                        "e.status, e.review_result AS \"reviewResult\", e.reviewer, e.review_note AS \"reviewNote\", " +
                        "e.evaluated_at AS \"evaluatedAt\", e.reviewed_at AS \"reviewedAt\" " +
                        "FROM ai_conversation_evaluation e JOIN conversation c ON c.conversation_id=e.conversation_id WHERE e.id=?");
        List<Object> args = new ArrayList<>();
        args.add(evaluationId);
        AdminDataScope.appendAnd(sql, args, "c");
        List<Map<String, Object>> rows = jdbc.queryForList(sql.toString(), args.toArray());
        if (rows.isEmpty()) throw new IllegalArgumentException("评分记录不存在");
        Map<String, Object> row = new LinkedHashMap<>(rows.get(0));
        row.put("issues", parseList((String) row.remove("issuesJson")));
        row.put("suggestions", parseStrings((String) row.remove("suggestionsJson")));
        row.put("messages", loadMessageMaps(String.valueOf(row.get("conversationId"))));
        row.put("attempts", evaluationAttempts(String.valueOf(row.get("conversationId"))));
        return row;
    }

    private List<Map<String, Object>> evaluationAttempts(String conversationId) {
        return jdbc.queryForList(
                "SELECT id,batch_task_id AS \"batchTaskId\",status,judge_provider AS \"judgeProvider\"," +
                        "judge_model AS \"judgeModel\",judge_temperature AS \"judgeTemperature\"," +
                        "prompt_version AS \"promptVersion\",evaluation_version AS \"evaluationVersion\"," +
                        "total_score AS \"totalScore\",rule_score AS \"ruleScore\",judge_score AS \"judgeScore\"," +
                        "grade,error_code AS \"errorCode\",error_message AS \"errorMessage\"," +
                        "requested_by AS \"requestedBy\",duration_ms AS \"durationMs\"," +
                        "create_time AS \"createTime\",start_time AS \"startTime\",finish_time AS \"finishTime\" " +
                        "FROM ai_quality_evaluation_attempt WHERE conversation_id=? " +
                        "ORDER BY create_time DESC,id DESC LIMIT 30",
                conversationId);
    }

    @Transactional
    public Map<String, Object> review(long evaluationId, String result, String note,
                                      Map<String, Integer> adjusted, String actor) {
        assertEvaluationAccess(evaluationId);
        if (!List.of("agree", "disagree", "adjusted").contains(result)) throw new IllegalArgumentException("复核结论不正确");
        if ("adjusted".equals(result)) {
            if (adjusted == null) throw new IllegalArgumentException("调整评分时必须填写七个维度分数");
            int intent = bounded(adjusted, "intent", 20), accuracy = bounded(adjusted, "accuracy", 20);
            int context = bounded(adjusted, "context", 15), safety = bounded(adjusted, "safety", 20);
            int naturalness = bounded(adjusted, "naturalness", 10), resolution = bounded(adjusted, "resolution", 10);
            int handoff = bounded(adjusted, "handoff", 5);
            int total = intent + accuracy + context + safety + naturalness + resolution + handoff;
            jdbc.update("UPDATE ai_conversation_evaluation SET total_score=?, intent_score=?, accuracy_score=?, context_score=?, " +
                            "safety_score=?, naturalness_score=?, resolution_score=?, handoff_score=?, grade=?, status='reviewed', " +
                            "review_result=?, reviewer=?, review_note=?, reviewed_at=now() WHERE id=?",
                    total, intent, accuracy, context, safety, naturalness, resolution, handoff, grade(total),
                    result, actor, truncate(note, 1000), evaluationId);
        } else {
            jdbc.update("UPDATE ai_conversation_evaluation SET status='reviewed', review_result=?, reviewer=?, review_note=?, reviewed_at=now() WHERE id=?",
                    result, actor, truncate(note, 1000), evaluationId);
        }
        audit.log("REVIEW_AI_SCORE", "评测 #" + evaluationId + "：" + result, actor);
        return detail(evaluationId);
    }

    @Transactional
    public Map<String, Object> addRegressionFromEvaluation(long evaluationId, String actor) {
        Map<String, Object> evaluation = detail(evaluationId);
        List<Map<String, Object>> messages = castMaps(evaluation.get("messages"));
        int userIndex = -1;
        for (int i = messages.size() - 1; i >= 0; i--) if ("user".equals(messages.get(i).get("role"))) { userIndex = i; break; }
        if (userIndex < 0) throw new IllegalArgumentException("会话没有客户消息，无法生成回归用例");
        String message = String.valueOf(messages.get(userIndex).get("content"));
        String expected = "AI";
        for (int i = userIndex + 1; i < messages.size(); i++) {
            if ("assistant".equals(messages.get(i).get("role"))) {
                Object type = messages.get(i).get("matchType");
                if (type != null && !String.valueOf(type).isBlank()) expected = String.valueOf(type);
                break;
            }
        }
        StringBuilder history = new StringBuilder();
        for (int i = 0; i < userIndex; i++) {
            Map<String, Object> item = messages.get(i);
            if ("agent".equals(item.get("role"))) continue;
            history.append("user".equals(item.get("role")) ? "用户：" : "客服：")
                    .append(item.get("content")).append('\n');
        }
        List<Long> existing = jdbc.queryForList("SELECT id FROM ai_regression_case WHERE source_evaluation_id=?", Long.class, evaluationId);
        long id;
        if (!existing.isEmpty()) id = existing.get(0);
        else {
            String name = "质检改进-" + truncate(message, 40);
            Long created = jdbc.queryForObject("INSERT INTO ai_regression_case(name,message,history,expected_reply_type,expected_answer_points," +
                            "source_conversation_id,source_evaluation_id,created_by) VALUES (?,?,?,?,?,?,?,?) RETURNING id",
                    Long.class, name, message, history.toString(), expected, suggestionsText(evaluation),
                    evaluation.get("conversationId"), evaluationId, actor);
            id = created == null ? 0 : created;
            audit.log("CREATE_REGRESSION_CASE", name, actor);
        }
        return regressionCase(id);
    }

    public List<Map<String, Object>> regressionCases(boolean includeDisabled) {
        return jdbc.queryForList("SELECT id,name,message,history,expected_reply_type AS \"expectedReplyType\", " +
                        "expected_answer_points AS \"expectedAnswerPoints\", forbidden_answer_points AS \"forbiddenAnswerPoints\", " +
                        "expected_authority_domain AS \"expectedAuthorityDomain\", expected_authority_source AS \"expectedAuthoritySource\", " +
                        "expected_model_invoked AS \"expectedModelInvoked\", source_conversation_id AS \"sourceConversationId\", " +
                        "source_evaluation_id AS \"sourceEvaluationId\", enabled,created_by AS \"createdBy\", " +
                        "create_time AS \"createTime\",update_time AS \"updateTime\" FROM ai_regression_case " +
                        (includeDisabled ? "" : "WHERE enabled=true ") + "ORDER BY update_time DESC,id DESC");
    }

    @Transactional
    public Map<String, Object> createRegression(Map<String, Object> input, String actor) {
        String name = required(input, "name", "用例名称");
        String message = required(input, "message", "客户消息");
        String expected = string(input.get("expectedReplyType"), "AI").toUpperCase();
        Long id = jdbc.queryForObject("INSERT INTO ai_regression_case(name,message,history,expected_reply_type,expected_answer_points," +
                        "forbidden_answer_points,expected_authority_domain,expected_authority_source,expected_model_invoked,enabled,created_by) " +
                        "VALUES (?,?,?,?,?,?,?,?,?,?,?) RETURNING id", Long.class, truncate(name, 150), message,
                string(input.get("history"), ""), expected, truncate(string(input.get("expectedAnswerPoints"), ""), 4000),
                truncate(string(input.get("forbiddenAnswerPoints"), ""), 4000),
                truncate(string(input.get("expectedAuthorityDomain"), ""), 80),
                truncate(string(input.get("expectedAuthoritySource"), ""), 200), nullableBoolean(input.get("expectedModelInvoked")),
                input.get("enabled") == null || Boolean.parseBoolean(String.valueOf(input.get("enabled"))), actor);
        audit.log("CREATE_REGRESSION_CASE", name, actor);
        return regressionCase(id == null ? 0 : id);
    }

    @Transactional
    public Map<String, Object> updateRegression(long id, Map<String, Object> input, String actor) {
        String name = required(input, "name", "用例名称"), message = required(input, "message", "客户消息");
        int updated = jdbc.update("UPDATE ai_regression_case SET name=?,message=?,history=?,expected_reply_type=?,expected_answer_points=?," +
                        "forbidden_answer_points=?,expected_authority_domain=?,expected_authority_source=?,expected_model_invoked=?,enabled=?,update_time=now() WHERE id=?",
                truncate(name, 150), message, string(input.get("history"), ""),
                string(input.get("expectedReplyType"), "AI").toUpperCase(), truncate(string(input.get("expectedAnswerPoints"), ""), 4000),
                truncate(string(input.get("forbiddenAnswerPoints"), ""), 4000),
                truncate(string(input.get("expectedAuthorityDomain"), ""), 80),
                truncate(string(input.get("expectedAuthoritySource"), ""), 200), nullableBoolean(input.get("expectedModelInvoked")),
                input.get("enabled") == null || Boolean.parseBoolean(String.valueOf(input.get("enabled"))), id);
        if (updated == 0) throw new IllegalArgumentException("回归用例不存在");
        audit.log("UPDATE_REGRESSION_CASE", name, actor);
        return regressionCase(id);
    }

    @Transactional
    public void deleteRegression(long id, String actor) {
        if (jdbc.update("DELETE FROM ai_regression_case WHERE id=?", id) == 0) throw new IllegalArgumentException("回归用例不存在");
        audit.log("DELETE_REGRESSION_CASE", "用例 #" + id, actor);
    }

    private long save(String conversationId, QualityRuleEngine.Score rule, QualityRuleEngine.Score ai,
                      QualityRuleEngine.Score combined, String judgeProvider, String judgeModel) {
        String issues = json(combined.issues()), suggestions = json(combined.suggestions());
        String summary = evaluationSummary(ai, combined);
        Long id = jdbc.queryForObject("INSERT INTO ai_conversation_evaluation(conversation_id,total_score,intent_score,accuracy_score," +
                        "context_score,safety_score,naturalness_score,resolution_score,handoff_score,grade,rule_score,judge_score," +
                        "judge_enabled,judge_provider,judge_model,issues_json,suggestions_json,summary,evaluation_version,status,evaluated_at,review_result,reviewer,review_note,reviewed_at) " +
                        "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'scored',now(),NULL,NULL,NULL,NULL) " +
                        "ON CONFLICT (conversation_id) DO UPDATE SET total_score=EXCLUDED.total_score,intent_score=EXCLUDED.intent_score," +
                        "accuracy_score=EXCLUDED.accuracy_score,context_score=EXCLUDED.context_score,safety_score=EXCLUDED.safety_score," +
                        "naturalness_score=EXCLUDED.naturalness_score,resolution_score=EXCLUDED.resolution_score,handoff_score=EXCLUDED.handoff_score," +
                        "grade=EXCLUDED.grade,rule_score=EXCLUDED.rule_score,judge_score=EXCLUDED.judge_score,judge_enabled=EXCLUDED.judge_enabled," +
                        "judge_provider=EXCLUDED.judge_provider,judge_model=EXCLUDED.judge_model," +
                        "issues_json=EXCLUDED.issues_json,suggestions_json=EXCLUDED.suggestions_json,summary=EXCLUDED.summary," +
                        "evaluation_version=EXCLUDED.evaluation_version,status='scored',evaluated_at=now(),review_result=NULL,reviewer=NULL,review_note=NULL,reviewed_at=NULL," +
                        "judge_error_code=NULL,judge_error_message=NULL,judge_error_provider=NULL,judge_error_model=NULL,judge_error_at=NULL RETURNING id",
                Long.class, conversationId, combined.total(), combined.intent(), combined.accuracy(), combined.context(), combined.safety(),
                combined.naturalness(), combined.resolution(), combined.handoff(), grade(combined.total()), rule.total(), ai == null ? null : ai.total(),
                ai != null, judgeProvider, judgeModel, issues, suggestions, summary, ai == null ? "risk-scan-v3" : "semantic-v3");
        return id == null ? 0 : id;
    }

    private String evaluationSummary(QualityRuleEngine.Score ai, QualityRuleEngine.Score combined) {
        if (ai == null) {
            return combined.issues().isEmpty()
                    ? "规则风险扫描未发现明确风险；尚未进行语义质量评分"
                    : "规则风险扫描发现 " + combined.issues().size() + " 项风险；尚未进行语义质量评分";
        }
        return combined.issues().isEmpty() ? "语义复核未发现明确问题"
                : "语义复核发现 " + combined.issues().size() + " 项可复核问题";
    }

    /**
     * 失败诊断单独保存。若已有有效语义分或人工确认结果，只更新“最近失败”字段，绝不覆盖原评分。
     */
    private FailureSaveResult saveJudgeFailure(String conversationId, QualityRuleEngine.Score rule,
                                               String provider, String model, JudgeFailure failure) {
        List<Map<String, Object>> existing = jdbc.queryForList(
                "SELECT id,judge_enabled AS \"judgeEnabled\",evaluation_version AS \"evaluationVersion\"," +
                        "review_result AS \"reviewResult\" FROM ai_conversation_evaluation WHERE conversation_id=?",
                conversationId);
        if (!existing.isEmpty()) {
            Map<String, Object> row = existing.get(0);
            boolean semantic = Boolean.TRUE.equals(row.get("judgeEnabled"))
                    && "semantic-v3".equals(String.valueOf(row.get("evaluationVersion")));
            String review = row.get("reviewResult") == null ? "" : String.valueOf(row.get("reviewResult"));
            boolean preserved = semantic || "agree".equals(review) || "adjusted".equals(review);
            if (preserved) {
                long id = ((Number) row.get("id")).longValue();
                updateJudgeFailure(id, provider, model, failure);
                return new FailureSaveResult(id, true);
            }
        }
        long id = save(conversationId, rule, null, rule, provider, model);
        updateJudgeFailure(id, provider, model, failure);
        return new FailureSaveResult(id, false);
    }

    private void updateJudgeFailure(long evaluationId, String provider, String model, JudgeFailure failure) {
        jdbc.update("UPDATE ai_conversation_evaluation SET judge_error_code=?,judge_error_message=?," +
                        "judge_error_provider=?,judge_error_model=?,judge_error_at=now() WHERE id=?",
                failure.code(), truncate(failure.userMessage(), 1000), provider, truncate(model, 160), evaluationId);
    }

    private long createAttempt(String conversationId, Long batchTaskId, String provider, String model,
                               Double temperature, String promptVersion, String actor) {
        if (batchTaskId != null) {
            List<Long> pending = jdbc.queryForList(
                    "SELECT id FROM ai_quality_evaluation_attempt WHERE batch_task_id=? AND conversation_id=? " +
                            "AND status='pending' ORDER BY id LIMIT 1",
                    Long.class, batchTaskId, conversationId);
            if (!pending.isEmpty()) {
                long id = pending.get(0);
                jdbc.update("UPDATE ai_quality_evaluation_attempt SET status='running',judge_provider=?," +
                                "judge_model=?,judge_temperature=?,prompt_version=?,start_time=now() WHERE id=?",
                        provider, truncate(model, 160), temperature, truncate(promptVersion, 100), id);
                return id;
            }
        }
        Long id = jdbc.queryForObject(
                "INSERT INTO ai_quality_evaluation_attempt(conversation_id,batch_task_id,status,judge_provider," +
                        "judge_model,judge_temperature,prompt_version,requested_by,start_time) " +
                        "VALUES (?,?,'running',?,?,?,?,?,now()) RETURNING id",
                Long.class, conversationId, batchTaskId, provider, truncate(model, 160), temperature,
                truncate(promptVersion, 100), truncate(actor, 50));
        return id == null ? 0L : id;
    }

    private void succeedAttempt(long attemptId, long evaluationId, QualityRuleEngine.Score rule,
                                QualityRuleEngine.Score ai, QualityRuleEngine.Score combined,
                                String promptVersion, String provider, String model, long startedNanos) {
        if (attemptId <= 0) return;
        String evaluationVersion = ai == null ? "risk-scan-v3" : "semantic-v3";
        jdbc.update("UPDATE ai_quality_evaluation_attempt SET evaluation_id=?,status='succeeded'," +
                        "judge_provider=?,judge_model=?,prompt_version=?,evaluation_version=?,total_score=?," +
                        "intent_score=?,accuracy_score=?,context_score=?,safety_score=?,naturalness_score=?," +
                        "resolution_score=?,handoff_score=?,grade=?,rule_score=?,judge_score=?,issues_json=?," +
                        "suggestions_json=?,summary=?,error_code=NULL,error_message=NULL,duration_ms=?,finish_time=now() WHERE id=?",
                evaluationId, provider, truncate(model, 160), truncate(promptVersion, 100), evaluationVersion,
                combined.total(), combined.intent(), combined.accuracy(), combined.context(), combined.safety(),
                combined.naturalness(), combined.resolution(), combined.handoff(), grade(combined.total()),
                rule.total(), ai == null ? null : ai.total(), json(combined.issues()), json(combined.suggestions()),
                truncate(evaluationSummary(ai, combined), 1000), elapsedMillis(startedNanos), attemptId);
    }

    private void failAttempt(long attemptId, Long evaluationId, String errorCode, String errorMessage,
                             long startedNanos) {
        if (attemptId <= 0) return;
        jdbc.update("UPDATE ai_quality_evaluation_attempt SET evaluation_id=?,status='failed',error_code=?," +
                        "error_message=?,duration_ms=?,finish_time=now() WHERE id=?",
                evaluationId, truncate(errorCode, 50), truncate(errorMessage, 1000),
                elapsedMillis(startedNanos), attemptId);
    }

    private void failAttempt(long attemptId, Long evaluationId, String errorCode, String errorMessage,
                             long startedNanos, QualityRuleEngine.Score rule, String promptVersion,
                             String provider, String model) {
        if (attemptId <= 0) return;
        jdbc.update("UPDATE ai_quality_evaluation_attempt SET evaluation_id=?,status='failed',judge_provider=?," +
                        "judge_model=?,prompt_version=?,evaluation_version='semantic-v3',rule_score=?,issues_json=?," +
                        "suggestions_json=?,error_code=?,error_message=?,duration_ms=?,finish_time=now() WHERE id=?",
                evaluationId, provider, truncate(model, 160), truncate(promptVersion, 100), rule.total(),
                json(rule.issues()), json(rule.suggestions()), truncate(errorCode, 50), truncate(errorMessage, 1000),
                elapsedMillis(startedNanos), attemptId);
    }

    private long elapsedMillis(long startedNanos) {
        return Math.max(0L, java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos));
    }

    QualityRuleEngine.Score merge(QualityRuleEngine.Score rule, QualityRuleEngine.Score ai) {
        List<QualityRuleEngine.Issue> issues = new ArrayList<>(rule.issues()); issues.addAll(ai.issues());
        List<String> suggestions = new ArrayList<>(rule.suggestions());
        ai.suggestions().forEach(s -> { if (!suggestions.contains(s)) suggestions.add(s); });
        // AI 负责语义质量评分；确定性规则只作为各维度上限，命中明确风险时不能被模型高分覆盖。
        // 规则未命中时为满分上限，不再参与加权，因此不会把 AI 的真实低分向 100 分拉升。
        return new QualityRuleEngine.Score(cap(ai.intent(), rule.intent()), cap(ai.accuracy(), rule.accuracy()),
                cap(ai.context(), rule.context()), cap(ai.safety(), rule.safety()),
                cap(ai.naturalness(), rule.naturalness()), cap(ai.resolution(), rule.resolution()),
                cap(ai.handoff(), rule.handoff()), issues, suggestions);
    }

    private int cap(int ai, int ruleCeiling) { return Math.min(ai, ruleCeiling); }
    private String grade(int score) { return score >= 90 ? "A" : score >= 80 ? "B" : score >= 70 ? "C" : score >= 60 ? "D" : "E"; }

    private List<QualityRuleEngine.Message> loadMessages(String conversationId) {
        return jdbc.query("SELECT id,role,content,match_type FROM message WHERE conversation_id=? ORDER BY create_time,id",
                (rs, n) -> new QualityRuleEngine.Message(rs.getLong("id"), rs.getString("role"), rs.getString("content"), rs.getString("match_type")), conversationId);
    }

    private void assertConversationAccess(String conversationId) {
        assertScopedAccess("SELECT count(*) FROM conversation c WHERE c.conversation_id=?",
                conversationId, "会话不存在或无权访问");
    }

    private void assertEvaluationAccess(long evaluationId) {
        assertScopedAccess("SELECT count(*) FROM ai_conversation_evaluation e JOIN conversation c " +
                        "ON c.conversation_id=e.conversation_id WHERE e.id=?",
                evaluationId, "评分记录不存在或无权访问");
    }

    /** baseSql 为本类内的固定查询，动态部分仅来自受控的数据范围策略。 */
    private void assertScopedAccess(String baseSql, Object id, String failureMessage) {
        AdminDataScope.Condition scope = AdminDataScope.condition("c");
        if (!scope.restricted()) return;
        StringBuilder sql = new StringBuilder(baseSql);
        List<Object> args = new ArrayList<>();
        args.add(id);
        sql.append(" AND ").append(scope.sql());
        args.addAll(scope.args());
        Integer count = jdbc.queryForObject(sql.toString(), Integer.class, args.toArray());
        if (count == null || count == 0) throw new IllegalArgumentException(failureMessage);
    }

    private List<Map<String, Object>> loadMessageMaps(String conversationId) {
        return jdbc.queryForList("SELECT id,role,content,match_type AS \"matchType\"," +
                "decision_trace AS \"decisionTrace\",create_time AS \"createTime\" " +
                "FROM message WHERE conversation_id=? ORDER BY create_time,id", conversationId);
    }

    private Map<String, Object> regressionCase(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT id,name,message,history,expected_reply_type AS \"expectedReplyType\"," +
                "expected_answer_points AS \"expectedAnswerPoints\",forbidden_answer_points AS \"forbiddenAnswerPoints\"," +
                "expected_authority_domain AS \"expectedAuthorityDomain\",expected_authority_source AS \"expectedAuthoritySource\"," +
                "expected_model_invoked AS \"expectedModelInvoked\",source_conversation_id AS \"sourceConversationId\"," +
                "source_evaluation_id AS \"sourceEvaluationId\",enabled,created_by AS \"createdBy\",create_time AS \"createTime\"," +
                "update_time AS \"updateTime\" FROM ai_regression_case WHERE id=?", id);
        if (rows.isEmpty()) throw new IllegalArgumentException("回归用例不存在");
        return rows.get(0);
    }

    private List<Map<String, Object>> parseList(String json) {
        try { return json == null || json.isBlank() ? List.of() : JSON.readValue(json, MAP_LIST); }
        catch (Exception ignored) { return List.of(); }
    }

    private List<String> parseStrings(String json) {
        try { return json == null || json.isBlank() ? List.of() : JSON.readValue(json, new TypeReference<>() {}); }
        catch (Exception ignored) { return List.of(); }
    }

    private String json(Object value) { try { return JSON.writeValueAsString(value); } catch (Exception e) { return "[]"; } }
    private int bounded(Map<String, Integer> values, String key, int max) {
        Integer value = values.get(key); if (value == null || value < 0 || value > max) throw new IllegalArgumentException(key + " 分数应在 0-" + max + " 之间"); return value;
    }
    private String suggestionsText(Map<String, Object> eval) { return castStrings(eval.get("suggestions")).stream().limit(5).reduce((a,b)->a+"\n"+b).orElse(""); }
    @SuppressWarnings("unchecked") private List<Map<String, Object>> castMaps(Object value) { return value instanceof List<?> list ? (List<Map<String, Object>>) list : List.of(); }
    @SuppressWarnings("unchecked") private List<String> castStrings(Object value) { return value instanceof List<?> list ? (List<String>) list : List.of(); }
    private String required(Map<String, Object> input, String key, String label) { String v=string(input.get(key),"").trim(); if(v.isBlank()) throw new IllegalArgumentException(label+"不能为空"); return v; }
    private String string(Object value, String fallback) { return value == null ? fallback : String.valueOf(value); }
    private Boolean nullableBoolean(Object value) {
        if (value == null || String.valueOf(value).isBlank()) return null;
        return Boolean.parseBoolean(String.valueOf(value));
    }
    private String truncate(String value, int max) { if(value==null) return ""; return value.length()<=max?value:value.substring(0,max); }
    private String safeMessage(Exception e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) return "评分失败";
        if (message.contains("SQL") || message.contains("jdbc") || message.contains("relation") || message.contains("column")) {
            return "评分暂时不可用，请稍后重试";
        }
        return truncate(message, 200);
    }

    String batchFailureMessage(Exception e) { return safeMessage(e); }

    private JudgeFailure classifyJudgeFailure(Exception error) {
        if (error instanceof QualityJudgeService.JudgeResponseException responseError) {
            return new JudgeFailure("invalid_response", responseError.getMessage());
        }
        String message = throwableMessages(error).toLowerCase(java.util.Locale.ROOT);
        if (containsAny(message, "429", "too many requests", "rate limit", "ratelimit", "请求过于频繁", "请求频率", "限流")) {
            return new JudgeFailure("rate_limited", "模型接口请求过于频繁，已完成退避重试但仍被限流，请稍后再试");
        }
        if (containsAny(message, "timeout", "timed out", "read timed out", "connect timed out", "超时")) {
            return new JudgeFailure("timeout", "模型接口在超时时间内没有完成响应，请稍后重试或检查模型服务状态");
        }
        if (containsAny(message, "401", "403", "unauthorized", "authentication", "invalid api key", "api key invalid", "鉴权", "密钥无效")) {
            return new JudgeFailure("authentication_failed", "模型接口鉴权失败，请检查该模型的 API Key 和访问权限");
        }
        if (containsAny(message, "404", "model not found", "deployment not found", "endpoint not found", "模型不存在")) {
            return new JudgeFailure("model_unavailable", "评测模型或推理接入点不可用，请检查模型 ID 和接口地址");
        }
        if (containsAny(message, "connection", "connectexception", "unknown host", "dns", "network", "连接失败")) {
            return new JudgeFailure("network_error", "无法连接评测模型接口，请检查网络和接口地址后重试");
        }
        return new JudgeFailure("model_error", "评测模型调用异常，系统已记录服务端日志，请稍后重试");
    }

    private String throwableMessages(Throwable error) {
        StringBuilder text = new StringBuilder();
        Throwable current = error;
        for (int depth = 0; current != null && depth < 8; depth++, current = current.getCause()) {
            if (current.getMessage() != null) text.append(' ').append(current.getMessage());
        }
        return text.toString();
    }

    private boolean containsAny(String value, String... needles) {
        for (String needle : needles) if (value.contains(needle)) return true;
        return false;
    }

    private String judgeRoleName(String provider) {
        return "primary".equals(provider) ? "主模型"
                : "fallback".equals(provider) ? "备用模型"
                : "eval".equals(provider) ? "评测专用模型" : "AI 模型";
    }

    private String normalizeJudgeProvider(String provider) {
        String selected = provider == null ? "rules" : provider.trim().toLowerCase(java.util.Locale.ROOT);
        if (selected.isBlank()) selected = "rules";
        if (!List.of("rules", "primary", "fallback", "eval").contains(selected)) {
            throw new IllegalArgumentException("评测方式只能选择仅规则、主模型、备用模型或评测专用模型");
        }
        return selected;
    }

    /**
     * 规则低分自动升级 AI 复核时的模型角色：沿用自动评测模型选择（quality.autoScoreProvider，默认 eval），
     * 该角色未配置可用模型时回落主模型 primary；两者都不可用返回 null，
     * 本次维持纯规则结果（该记录仍会进入夜间自动评测候选，不会被遗漏）。
     */
    private String resolveRuleUpgradeProvider() {
        String chosen = String.valueOf(config.get("quality.autoScoreProvider", "eval"))
                .trim().toLowerCase(java.util.Locale.ROOT);
        if (!List.of("primary", "fallback", "eval").contains(chosen)) chosen = "eval";
        for (String candidate : chosen.equals("primary") ? List.of("primary") : List.of(chosen, "primary")) {
            try {
                String model = judge.modelName(candidate);
                if (model != null && !model.isBlank()) return candidate;
            } catch (Exception ignored) {
                // 模型列表读取失败时尝试下一候选
            }
        }
        return null;
    }

    private record JudgeFailure(String code, String userMessage) {}
    private record FailureSaveResult(long evaluationId, boolean preservedExistingScore) {}
    public record BatchPlan(List<String> conversationIds, String judgeProvider, String judgeModel,
                            String promptVersion, Double judgeTemperature) {
        public BatchPlan(List<String> conversationIds, String judgeProvider, String judgeModel) {
            this(conversationIds, judgeProvider, judgeModel,
                    "rules".equals(judgeProvider) ? "risk-scan-v3" : "semantic-v3",
                    "rules".equals(judgeProvider) ? null : 0.0);
        }
    }

}
