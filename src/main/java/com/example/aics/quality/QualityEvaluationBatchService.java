package com.example.aics.quality;

import com.example.aics.audit.AuditService;
import com.example.aics.config.AdminDataScope;
import com.example.aics.config.ConfigService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 手动批量质量评测的持久化后台任务。HTTP 请求只负责提交，
 * 页面通过状态接口轮询进度，避免长时间占用 Web 线程或因请求超时丢失结果。
 */
@Service
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
public class QualityEvaluationBatchService {
    private static final Logger log = LoggerFactory.getLogger(QualityEvaluationBatchService.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<List<Map<String, String>>> FAILURE_LIST = new TypeReference<>() {};

    private final ConversationEvaluationService evaluations;
    private final JdbcTemplate jdbc;
    private final ConfigService config;
    private final AuditService audit;
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(
            1, 1, 30L, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(5), new ThreadPoolExecutor.AbortPolicy());

    public QualityEvaluationBatchService(ConversationEvaluationService evaluations, JdbcTemplate jdbc,
                                         ConfigService config, AuditService audit) {
        this.evaluations = evaluations;
        this.jdbc = jdbc;
        this.config = config;
        this.audit = audit;
    }

    @PostConstruct
    void recoverInterruptedTasks() {
        jdbc.update("UPDATE ai_quality_batch_task SET status='failed',error_code='application_restarted'," +
                "error_message='应用重启导致任务中断，请重新提交',current_conversation_id=NULL," +
                "finish_time=now() WHERE status IN ('queued','running')");
        jdbc.update("UPDATE ai_quality_evaluation_attempt SET status='failed',error_code='application_restarted'," +
                "error_message='应用重启导致评测中断，请重新评测',finish_time=now() " +
                "WHERE status IN ('pending','running')");
    }

    public synchronized Map<String, Object> submit(List<String> requestedIds, String provider, String actor) {
        ConversationEvaluationService.BatchPlan plan = evaluations.prepareBatch(requestedIds, provider);
        Integer active = jdbc.queryForObject(
                "SELECT count(*) FROM ai_quality_batch_task WHERE status IN ('queued','running')", Integer.class);
        if (active != null && active >= 5) {
            throw new IllegalArgumentException("质量评测任务队列已满，请稍后重试");
        }
        Long taskId = jdbc.queryForObject(
                "INSERT INTO ai_quality_batch_task(status,judge_provider,judge_model,requested_count," +
                        "conversation_ids_json,requested_by) VALUES ('queued',?,?,?,?,?) RETURNING id",
                Long.class, plan.judgeProvider(), emptyToNull(plan.judgeModel()), plan.conversationIds().size(),
                json(plan.conversationIds()), actor);
        long id = taskId == null ? 0L : taskId;
        try {
            for (String conversationId : plan.conversationIds()) {
                jdbc.update("INSERT INTO ai_quality_evaluation_attempt(conversation_id,batch_task_id,status," +
                                "judge_provider,judge_model,judge_temperature,prompt_version,requested_by) " +
                                "VALUES (?,?,'pending',?,?,?,?,?)",
                        conversationId, id, plan.judgeProvider(), emptyToNull(plan.judgeModel()),
                        plan.judgeTemperature(), plan.promptVersion(), actor);
            }
        } catch (RuntimeException e) {
            jdbc.update("UPDATE ai_quality_evaluation_attempt SET status='failed',error_code='task_submit_failed'," +
                    "error_message='批量任务提交失败',finish_time=now() WHERE batch_task_id=? AND status='pending'", id);
            failTask(id, "task_submit_failed", "批量评测任务提交失败");
            throw e;
        }
        AdminDataScope.AccessScope scope = AdminDataScope.current();
        try {
            executor.execute(() -> run(id, plan, actor, scope));
        } catch (java.util.concurrent.RejectedExecutionException e) {
            failTask(id, "queue_full", "质量评测任务队列已满，请稍后重试");
            throw new IllegalArgumentException("质量评测任务队列已满，请稍后重试");
        }
        audit.log("SUBMIT_QUALITY_BATCH", "批量评测 #" + id + "：" + plan.conversationIds().size() + " 条", actor);
        return status(id, actor);
    }

    void run(long taskId, ConversationEvaluationService.BatchPlan plan, String actor,
             AdminDataScope.AccessScope scope) {
        List<Map<String, String>> failures = new ArrayList<>();
        int succeeded = 0;
        try (AdminDataScope.Lease ignored = AdminDataScope.use(scope)) {
            jdbc.update("UPDATE ai_quality_batch_task SET status='running',start_time=now()," +
                    "error_code=NULL,error_message=NULL WHERE id=?", taskId);
            long intervalMs = "rules".equals(plan.judgeProvider()) ? 0L
                    : Math.max(0, Math.min(config.getInt("quality.batchScoreIntervalMs", 1000), 10000));
            for (int index = 0; index < plan.conversationIds().size(); index++) {
                String conversationId = plan.conversationIds().get(index);
                jdbc.update("UPDATE ai_quality_batch_task SET current_conversation_id=? WHERE id=?",
                        conversationId, taskId);
                try {
                    Map<String, Object> result = evaluations.score(
                            conversationId, plan.judgeProvider(), actor, taskId);
                    boolean ok = "rules".equals(plan.judgeProvider())
                            || Boolean.TRUE.equals(result.get("qualityScored"));
                    if (ok) succeeded++;
                    else failures.add(failure(conversationId,
                            String.valueOf(result.getOrDefault("warning", "AI 语义复核未成功"))));
                } catch (RuntimeException e) {
                    String error = evaluations.batchFailureMessage(e);
                    failures.add(failure(conversationId, error));
                    jdbc.update("UPDATE ai_quality_evaluation_attempt SET status='failed'," +
                                    "error_code='evaluation_rejected',error_message=?,finish_time=now() " +
                                    "WHERE batch_task_id=? AND conversation_id=? AND status='pending'",
                            truncate(error, 1000), taskId, conversationId);
                }
                int processed = index + 1;
                jdbc.update("UPDATE ai_quality_batch_task SET processed_count=?,succeeded_count=?," +
                                "failed_count=?,failures_json=? WHERE id=?",
                        processed, succeeded, failures.size(), json(failures), taskId);
                if (intervalMs > 0 && processed < plan.conversationIds().size()) {
                    try {
                        Thread.sleep(intervalMs);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("质量评测任务被中断", e);
                    }
                }
            }
            String status = failures.isEmpty() ? "succeeded" : succeeded > 0 ? "partial" : "failed";
            String errorCode = failures.isEmpty() ? null : "conversation_failures";
            String errorMessage = failures.isEmpty() ? null : failures.get(0).get("error");
            jdbc.update("UPDATE ai_quality_batch_task SET status=?,processed_count=requested_count," +
                            "succeeded_count=?,failed_count=?,failures_json=?,current_conversation_id=NULL," +
                            "error_code=?,error_message=?,finish_time=now() WHERE id=?",
                    status, succeeded, failures.size(), json(failures), errorCode, truncate(errorMessage, 1000), taskId);
            audit.log("COMPLETE_QUALITY_BATCH", "批量评测 #" + taskId + "：成功 "
                    + succeeded + "，失败 " + failures.size(), actor);
        } catch (Exception e) {
            String message = evaluations.batchFailureMessage(e);
            failTask(taskId, "task_failed", message);
            log.warn("批量质量评测失败: taskId={}, error={}", taskId, message, e);
        }
    }

    public Map<String, Object> status(long taskId, String actor) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id,status,judge_provider AS \"judgeProvider\",judge_model AS \"judgeModel\"," +
                        "requested_count AS \"requestedCount\",processed_count AS \"processedCount\"," +
                        "succeeded_count AS \"succeededCount\",failed_count AS \"failedCount\"," +
                        "current_conversation_id AS \"currentConversationId\",failures_json AS \"failuresJson\"," +
                        "error_code AS \"errorCode\",error_message AS \"errorMessage\"," +
                        "create_time AS \"createTime\",start_time AS \"startTime\",finish_time AS \"finishTime\" " +
                        "FROM ai_quality_batch_task WHERE id=? AND requested_by=?",
                taskId, actor);
        if (rows.isEmpty()) return Map.of("found", false);
        return taskView(rows.get(0));
    }

    public Map<String, Object> latest(String actor) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id,status,judge_provider AS \"judgeProvider\",judge_model AS \"judgeModel\"," +
                        "requested_count AS \"requestedCount\",processed_count AS \"processedCount\"," +
                        "succeeded_count AS \"succeededCount\",failed_count AS \"failedCount\"," +
                        "current_conversation_id AS \"currentConversationId\",failures_json AS \"failuresJson\"," +
                        "error_code AS \"errorCode\",error_message AS \"errorMessage\"," +
                        "create_time AS \"createTime\",start_time AS \"startTime\",finish_time AS \"finishTime\" " +
                        "FROM ai_quality_batch_task WHERE requested_by=? ORDER BY id DESC LIMIT 1",
                actor);
        if (rows.isEmpty()) return Map.of("found", false);
        return taskView(rows.get(0));
    }

    private Map<String, Object> taskView(Map<String, Object> source) {
        Map<String, Object> result = new LinkedHashMap<>(source);
        result.put("found", true);
        result.put("failures", parseFailures(String.valueOf(result.remove("failuresJson"))));
        String status = String.valueOf(result.get("status"));
        result.put("active", "queued".equals(status) || "running".equals(status));
        return result;
    }

    private List<Map<String, String>> parseFailures(String value) {
        try {
            return value == null || value.isBlank() ? List.of() : JSON.readValue(value, FAILURE_LIST);
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private Map<String, String> failure(String conversationId, String error) {
        Map<String, String> item = new LinkedHashMap<>();
        item.put("conversationId", conversationId);
        item.put("error", truncate(error, 300));
        return item;
    }

    private void failTask(long taskId, String code, String message) {
        jdbc.update("UPDATE ai_quality_batch_task SET status='failed',error_code=?,error_message=?," +
                        "current_conversation_id=NULL,finish_time=now() WHERE id=?",
                code, truncate(message, 1000), taskId);
    }

    private String json(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception e) {
            return "[]";
        }
    }

    private String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private String truncate(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) executor.shutdownNow();
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
