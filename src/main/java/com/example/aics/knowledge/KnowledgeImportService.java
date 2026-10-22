package com.example.aics.knowledge;

import com.example.aics.channel.TenantExecutionContext;
import com.example.aics.config.ScopedDataAccess;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import jakarta.annotation.PreDestroy;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 异步知识导入（第二批 #6，ADR-005 落地）：
 * 上传接口只收文件入队，解析/切片/Embedding 在后台线程池执行，前端轮询状态。
 *
 * 为什么不引入 RabbitMQ：单机队列足够，积压了再上 MQ（ADR-005 已留口）。
 * 线程池策略：core 2 / max 4，有界队列 100；上传先落临时文件，避免每个 50MB 任务长期占堆内存。
 *
 * 流程：
 *   submit() → 插 import_task(processing) → 线程池执行 doImport() → 更新 done/failed
 *   status() → 前端轮询查询
 */
@Service
public class KnowledgeImportService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeImportService.class);

    /** 导入专用线程池（常驻单例，不关闭）：core=2 max=4，有界队列 100，满则调用方自跑 */
    private final ExecutorService importExecutor = new ThreadPoolExecutor(
            2, 4, 60L, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(100),
            new ThreadPoolExecutor.AbortPolicy());

    private final KnowledgeService knowledgeService;
    private final JdbcTemplate jdbc;

    public KnowledgeImportService(KnowledgeService knowledgeService, JdbcTemplate jdbc) {
        this.knowledgeService = knowledgeService;
        this.jdbc = jdbc;
    }

    /**
     * 提交异步导入任务，立即返回 taskId。
     * 关键：请求内先把 MultipartFile 落到应用临时目录；后台线程再读取并在 finally 删除。
     */
    public long submit(MultipartFile file) {
        TenantExecutionContext.Scope resourceScope = TenantExecutionContext.current();
        Path staged;
        try {
            staged = Files.createTempFile("aics-import-", ".upload");
            file.transferTo(staged);
        } catch (Exception e) {
            throw new IllegalArgumentException("读取上传文件失败：" + e.getMessage());
        }
        String fileName = file.getOriginalFilename() == null ? "未命名文档" : file.getOriginalFilename();

        Long taskId = jdbc.queryForObject(
                "INSERT INTO import_task(file_name,tenant_id,kf_account_id) VALUES (?,?,?) RETURNING id",
                Long.class, fileName,
                resourceScope == null ? null : resourceScope.tenantId(),
                resourceScope == null ? null : resourceScope.kfAccountId());
        long id = taskId == null ? 0 : taskId;
        final String name = fileName;
        try {
            importExecutor.execute(() -> doImport(id, name, staged, resourceScope));
        } catch (java.util.concurrent.RejectedExecutionException e) {
            try { Files.deleteIfExists(staged); } catch (Exception ignored) { }
            jdbc.update("UPDATE import_task SET status = 'failed', error = ?, finish_time = now() WHERE id = ?",
                    "导入队列已满，请稍后重试", id);
            throw new IllegalArgumentException("导入队列已满，请稍后重试");
        }
        return id;
    }

    /** 后台执行：复用 KnowledgeService.importDocument（解析→切片→向量化→入库） */
    private void doImport(long taskId, String fileName, Path staged,
                          TenantExecutionContext.Scope resourceScope) {
        try (TenantExecutionContext.Lease ignored = useScope(resourceScope)) {
            byte[] bytes = Files.readAllBytes(staged);
            MultipartFile wrapped = new BytesMultipartFile(fileName, bytes);
            int chunks = knowledgeService.importDocument(wrapped);
            jdbc.update(
                    "UPDATE import_task SET status = 'done', chunk_count = ?, finish_time = now() WHERE id = ?",
                    chunks, taskId);
            log.info("知识库异步导入完成: {} (taskId={}, chunks={})", fileName, taskId, chunks);
        } catch (Exception e) {
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            jdbc.update(
                    "UPDATE import_task SET status = 'failed', error = ?, finish_time = now() WHERE id = ?",
                    msg.length() > 900 ? msg.substring(0, 900) : msg, taskId);
            log.warn("知识库异步导入失败: {} (taskId={}): {}", fileName, taskId, msg);
        } finally {
            try { Files.deleteIfExists(staged); } catch (Exception ignored) { }
        }
    }

    @PreDestroy
    public void shutdown() {
        importExecutor.shutdown();
        try {
            if (!importExecutor.awaitTermination(10, TimeUnit.SECONDS)) importExecutor.shutdownNow();
        } catch (InterruptedException e) {
            importExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /** 查询任务状态（前端轮询用） */
    public Map<String, Object> status(long taskId) {
        StringBuilder sql = new StringBuilder(
                "SELECT id, file_name, status, chunk_count, error, create_time, finish_time " +
                "FROM import_task t WHERE id = ?");
        List<Object> args = new ArrayList<>();
        args.add(taskId);
        ScopedDataAccess.appendAnd(sql, args, "t");
        List<Map<String, Object>> rows = jdbc.queryForList(sql.toString(), args.toArray());
        if (rows.isEmpty()) {
            return Map.of("found", false);
        }
        return rows.get(0);
    }

    private TenantExecutionContext.Lease useScope(TenantExecutionContext.Scope scope) {
        return scope == null ? () -> { } : TenantExecutionContext.use(scope.tenantId(), scope.kfAccountId());
    }
}
