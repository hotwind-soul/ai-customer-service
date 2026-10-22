package com.example.aics.ai;

import com.example.aics.config.ConfigService;
import com.example.aics.config.AdminDataScope;
import com.example.aics.config.KeyConfigService;
import com.example.aics.config.SecretCryptoService;
import com.example.aics.knowledge.KnowledgeService;
import com.example.aics.knowledge.VectorStore;
import com.example.aics.knowledge.EmbeddingIndexCoordinator;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 向量模型安全切换：候选模型先验证并重建全部知识向量，随后在同一数据库事务中
 * 提交新向量与新模型配置。任一步失败都继续使用旧配置和旧向量。
 */
@Service
public class EmbeddingModelSwitchService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingModelSwitchService.class);
    private static final int MAX_ERROR_LENGTH = 900;

    private final ModelGateway modelGateway;
    private final KnowledgeService knowledgeService;
    private final VectorStore vectorStore;
    private final ConfigService configService;
    private final KeyConfigService keyConfigService;
    private final SecretCryptoService crypto;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final EmbeddingIndexCoordinator indexCoordinator;
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(
            1, 1, 30L, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(5), new ThreadPoolExecutor.AbortPolicy());

    public EmbeddingModelSwitchService(ModelGateway modelGateway,
                                       KnowledgeService knowledgeService,
                                       VectorStore vectorStore,
                                       ConfigService configService,
                                       KeyConfigService keyConfigService,
                                       SecretCryptoService crypto,
                                       JdbcTemplate jdbc,
                                       TransactionTemplate transactions,
                                       EmbeddingIndexCoordinator indexCoordinator) {
        this.modelGateway = modelGateway;
        this.knowledgeService = knowledgeService;
        this.vectorStore = vectorStore;
        this.configService = configService;
        this.keyConfigService = keyConfigService;
        this.crypto = crypto;
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.indexCoordinator = indexCoordinator;
    }

    @PostConstruct
    void recoverInterruptedTasks() {
        jdbc.update("UPDATE embedding_model_switch_task SET status='failed', "
                + "error='应用重启导致任务中断，请重新提交', finish_time=now() "
                + "WHERE status IN ('queued','embedding','switching')");
    }

    public synchronized long submit(String baseUrl, String modelName,
                                    String optionalApiKey, String actor) {
        Integer active = jdbc.queryForObject(
                "SELECT count(*) FROM embedding_model_switch_task "
                        + "WHERE status IN ('queued','embedding','switching')", Integer.class);
        if (active != null && active > 0) {
            throw new IllegalArgumentException("已有向量模型切换任务正在执行，请等待完成");
        }
        Long taskId = jdbc.queryForObject(
                "INSERT INTO embedding_model_switch_task(base_url,model_name,requested_by) "
                        + "VALUES (?,?,?) RETURNING id",
                Long.class, baseUrl, modelName, actor);
        long id = taskId == null ? 0L : taskId;
        String key = optionalApiKey == null ? "" : optionalApiKey.trim();
        AdminDataScope.AccessScope scope = AdminDataScope.current();
        try {
            executor.execute(() -> {
                try (AdminDataScope.Lease ignored = AdminDataScope.use(scope)) {
                    runSwitch(id, baseUrl, modelName, key);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            fail(id, "向量模型切换队列已满，请稍后重试");
            throw new IllegalArgumentException("向量模型切换队列已满，请稍后重试");
        }
        return id;
    }

    void runSwitch(long taskId, String baseUrl, String modelName, String optionalApiKey) {
        String effectiveApiKey = "";
        try (EmbeddingIndexCoordinator.Lease ignored = indexCoordinator.exclusiveSwitch()) {
            jdbc.update("UPDATE embedding_model_switch_task SET status='embedding', "
                    + "start_time=now(), error=NULL WHERE id=?", taskId);
            effectiveApiKey = resolveCandidateApiKey(baseUrl, optionalApiKey);
            if (effectiveApiKey.isBlank()) {
                throw new IllegalArgumentException("候选向量模型没有可用的 API Key");
            }

            ModelGateway.EmbeddingBatchClient candidate = modelGateway.candidateEmbeddingClient(
                    baseUrl, modelName, effectiveApiKey);
            validateProbe(candidate.embedAll(List.of("向量模型切换验证")));

            List<VectorStore.ReindexInput> prepared = knowledgeService.prepareReindex(
                    candidate::embedAll,
                    (processed, total) -> jdbc.update(
                            "UPDATE embedding_model_switch_task SET processed_chunks=?, total_chunks=? WHERE id=?",
                            processed, total, taskId));

            jdbc.update("UPDATE embedding_model_switch_task SET status='switching' WHERE id=?", taskId);
            transactions.executeWithoutResult(status -> {
                vectorStore.lockKnowledgeChunksForEmbeddingSwitch();
                if (!knowledgeService.reindexSnapshotStillCurrent(prepared)) {
                    throw new IllegalStateException("重建期间知识库发生变化，已取消切换；请重新提交");
                }
                vectorStore.replaceSearchVectors(prepared);
                persistConfig("model.embedding.baseUrl", baseUrl);
                persistConfig("model.embedding.name", modelName);
                if (!optionalApiKey.isBlank()) {
                    persistConfig("model.embedding.apiKey", crypto.encrypt(optionalApiKey));
                }
            });
            // 事务提交后再清缓存，使线上只会看到“旧配置+旧向量”或“新配置+新向量”。
            configService.reload();
            jdbc.update("UPDATE embedding_model_switch_task SET status='done', "
                    + "processed_chunks=total_chunks, finish_time=now() WHERE id=?", taskId);
            log.info("向量模型安全切换完成: taskId={}, model={}, chunks={}",
                    taskId, modelName, prepared.size());
        } catch (Exception e) {
            configService.reload();
            String message = safeError(e, optionalApiKey, effectiveApiKey);
            fail(taskId, message);
            log.warn("向量模型安全切换失败: taskId={}, model={}, error={}",
                    taskId, modelName, message);
        }
    }

    public Map<String, Object> status(long taskId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id,base_url,model_name,status,total_chunks,processed_chunks,error,"
                        + "requested_by,create_time,start_time,finish_time "
                        + "FROM embedding_model_switch_task WHERE id=?", taskId);
        return rows.isEmpty() ? Map.of("found", false) : rows.get(0);
    }

    private String resolveCandidateApiKey(String baseUrl, String optionalApiKey) {
        if (optionalApiKey != null && !optionalApiKey.isBlank()) return optionalApiKey;
        String configured = keyConfigService.get("model.embedding.apiKey", "");
        String dashscope = keyConfigService.get("ai.dashscope_key", "DASHSCOPE_API_KEY");
        return ModelGateway.fallbackApiKey(baseUrl, configured, dashscope);
    }

    private void validateProbe(List<float[]> vectors) {
        if (vectors == null || vectors.size() != 1 || vectors.get(0) == null) {
            throw new IllegalArgumentException("候选向量模型没有返回有效向量");
        }
        float[] vector = vectors.get(0);
        if (vector.length != VectorStore.EMBEDDING_DIMENSION) {
            throw new IllegalArgumentException("候选向量模型输出 " + vector.length
                    + " 维，当前知识库要求 " + VectorStore.EMBEDDING_DIMENSION + " 维");
        }
        for (float value : vector) {
            if (!Float.isFinite(value)) {
                throw new IllegalArgumentException("候选向量模型返回了非有限数值");
            }
        }
    }

    private void persistConfig(String key, String value) {
        jdbc.update("INSERT INTO system_config(key,value,update_time) VALUES (?,?,now()) "
                + "ON CONFLICT (key) DO UPDATE SET value=EXCLUDED.value, update_time=now()",
                key, value);
    }

    private void fail(long taskId, String message) {
        jdbc.update("UPDATE embedding_model_switch_task SET status='failed', error=?, "
                + "finish_time=now() WHERE id=?", message, taskId);
    }

    private String safeError(Exception error, String... secrets) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        String message = current.getMessage();
        if (message == null || message.isBlank()) message = current.getClass().getSimpleName();
        for (String secret : secrets) {
            if (secret != null && !secret.isBlank()) message = message.replace(secret, "***");
        }
        return message.length() > MAX_ERROR_LENGTH ? message.substring(0, MAX_ERROR_LENGTH) : message;
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
