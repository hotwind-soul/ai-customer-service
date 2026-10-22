package com.example.aics.knowledge;

import com.example.aics.ai.ModelGateway;
import com.example.aics.config.AdminDataScope;
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

/** 使用当前向量模型异步、原子地重建当前管理员可管理范围内的有效知识。 */
@Service
public class KnowledgeReindexService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeReindexService.class);
    private static final int MAX_ERROR_LENGTH = 900;

    private final ModelGateway modelGateway;
    private final KnowledgeService knowledgeService;
    private final VectorStore vectorStore;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final EmbeddingIndexCoordinator indexCoordinator;
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(
            1, 1, 30L, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(2), new ThreadPoolExecutor.AbortPolicy());

    public KnowledgeReindexService(ModelGateway modelGateway,
                                   KnowledgeService knowledgeService,
                                   VectorStore vectorStore,
                                   JdbcTemplate jdbc,
                                   TransactionTemplate transactions,
                                   EmbeddingIndexCoordinator indexCoordinator) {
        this.modelGateway = modelGateway;
        this.knowledgeService = knowledgeService;
        this.vectorStore = vectorStore;
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.indexCoordinator = indexCoordinator;
    }

    @PostConstruct
    void recoverInterruptedTasks() {
        jdbc.update("UPDATE knowledge_reindex_task SET status='failed', "
                + "error='应用重启导致任务中断，请重新提交', finish_time=now() "
                + "WHERE status IN ('queued','embedding','switching')");
    }

    public synchronized long submit(String actor) {
        Integer active = jdbc.queryForObject(
                "SELECT count(*) FROM knowledge_reindex_task "
                        + "WHERE status IN ('queued','embedding','switching')", Integer.class);
        if (active != null && active > 0) {
            throw new IllegalArgumentException("已有知识向量重建任务正在执行，请等待完成");
        }
        Long taskId = jdbc.queryForObject(
                "INSERT INTO knowledge_reindex_task(requested_by) VALUES (?) RETURNING id",
                Long.class, actor);
        long id = taskId == null ? 0L : taskId;
        AdminDataScope.AccessScope scope = AdminDataScope.current();
        try {
            executor.execute(() -> runReindex(id, scope));
        } catch (java.util.concurrent.RejectedExecutionException e) {
            fail(id, "知识向量重建队列已满，请稍后重试");
            throw new IllegalArgumentException("知识向量重建队列已满，请稍后重试");
        }
        return id;
    }

    void runReindex(long taskId, AdminDataScope.AccessScope scope) {
        try (AdminDataScope.Lease ignoredScope = AdminDataScope.use(scope);
             EmbeddingIndexCoordinator.Lease ignoredIndex = indexCoordinator.exclusiveSwitch()) {
            jdbc.update("UPDATE knowledge_reindex_task SET status='embedding', "
                    + "start_time=now(), error=NULL WHERE id=?", taskId);
            List<VectorStore.ReindexInput> prepared = knowledgeService.prepareReindex(
                    modelGateway::embedAll,
                    (processed, total) -> jdbc.update(
                            "UPDATE knowledge_reindex_task SET processed_chunks=?, total_chunks=? WHERE id=?",
                            processed, total, taskId));

            jdbc.update("UPDATE knowledge_reindex_task SET status='switching' WHERE id=?", taskId);
            transactions.executeWithoutResult(status -> {
                vectorStore.lockKnowledgeChunksForEmbeddingSwitch();
                if (!knowledgeService.reindexSnapshotStillCurrent(prepared)) {
                    throw new IllegalStateException("重建期间知识库发生变化，未替换原向量；请重新提交");
                }
                vectorStore.replaceSearchVectors(prepared);
            });
            jdbc.update("UPDATE knowledge_reindex_task SET status='done', "
                    + "processed_chunks=total_chunks, finish_time=now() WHERE id=?", taskId);
            log.info("知识向量重建完成: taskId={}, chunks={}", taskId, prepared.size());
        } catch (Exception e) {
            String message = safeError(e);
            fail(taskId, message);
            log.warn("知识向量重建失败: taskId={}, error={}", taskId, message);
        }
    }

    public Map<String, Object> status(long taskId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id,status,total_chunks,processed_chunks,error,requested_by," 
                        + "create_time,start_time,finish_time FROM knowledge_reindex_task WHERE id=?",
                taskId);
        return rows.isEmpty() ? Map.of("found", false) : rows.get(0);
    }

    private void fail(long taskId, String message) {
        jdbc.update("UPDATE knowledge_reindex_task SET status='failed', error=?, "
                + "finish_time=now() WHERE id=?", message, taskId);
    }

    private String safeError(Exception error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        String message = current.getMessage();
        if (message == null || message.isBlank()) message = current.getClass().getSimpleName();
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
