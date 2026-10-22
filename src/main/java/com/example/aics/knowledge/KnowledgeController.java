package com.example.aics.knowledge;

import com.example.aics.audit.AuditService;
import com.example.aics.config.AdminResourceScopeService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 知识库管理接口
 * 上传：POST /knowledge/upload （表单字段名 file）
 * 检索：GET  /knowledge/search?q=保修多久
 * 列表：GET  /knowledge/list
 */
@RestController
@RequestMapping("/knowledge")
public class KnowledgeController {

    private final KnowledgeService knowledgeService;
    private final VectorStore vectorStore;
    private final KnowledgeImportService importService;
    private final KnowledgeReindexService reindexService;
    private final BusinessFactService businessFactService;
    private final AuditService auditService;
    private final AdminResourceScopeService resourceScopes;
    private final EmbeddingIndexCoordinator indexCoordinator;

    public KnowledgeController(KnowledgeService knowledgeService, VectorStore vectorStore,
                               KnowledgeImportService importService,
                               KnowledgeReindexService reindexService,
                               BusinessFactService businessFactService,
                               AuditService auditService,
                               AdminResourceScopeService resourceScopes,
                               EmbeddingIndexCoordinator indexCoordinator) {
        this.knowledgeService = knowledgeService;
        this.vectorStore = vectorStore;
        this.importService = importService;
        this.reindexService = reindexService;
        this.businessFactService = businessFactService;
        this.auditService = auditService;
        this.resourceScopes = resourceScopes;
        this.indexCoordinator = indexCoordinator;
    }

    @PostMapping("/upload")
    public Map<String, Object> upload(@RequestParam("file") MultipartFile file,
                                      @RequestParam(required = false) Long tenantId,
                                      @RequestParam(required = false) Long kfAccountId) throws IOException {
        return resourceScopes.executeIo(tenantId, kfAccountId, () -> {
            int chunks = knowledgeService.importDocument(file);
            auditService.log("UPLOAD_DOC", file.getOriginalFilename() + "（" + chunks + " 片）", "admin");
            return Map.of(
                    "docName", file.getOriginalFilename() == null ? "" : file.getOriginalFilename(),
                    "chunks", chunks,
                    "message", "入库成功，共切 " + chunks + " 片");
        });
    }

    /** 异步上传（第二批 #6）：秒回 taskId，解析/Embedding 后台线程池处理，前端轮询状态 */
    @PostMapping("/upload/async")
    public Map<String, Object> uploadAsync(@RequestParam("file") MultipartFile file,
                                           @RequestParam(required = false) Long tenantId,
                                           @RequestParam(required = false) Long kfAccountId) {
        return resourceScopes.execute(tenantId, kfAccountId, () -> {
            long taskId = importService.submit(file);
            auditService.log("UPLOAD_DOC_ASYNC", file.getOriginalFilename() == null ? "" : file.getOriginalFilename(), "admin");
            return Map.of(
                    "taskId", taskId,
                    "status", "processing",
                    "docName", file.getOriginalFilename() == null ? "" : file.getOriginalFilename(),
                    "message", "已提交，正在后台处理");
        });
    }

    /** 异步导入任务状态（前端轮询用） */
    @GetMapping("/import/status")
    public Map<String, Object> importStatus(@RequestParam long taskId) {
        return importService.status(taskId);
    }

    @GetMapping("/search")
    public List<VectorStore.Match> search(@RequestParam String q,
                                          @RequestParam(defaultValue = "5") int topK) {
        return knowledgeService.search(q, topK);
    }

    @GetMapping("/list")
    public Map<String, Object> list() {
        return Map.of(
                "totalChunks", vectorStore.count(),
                "documents", vectorStore.listDocuments(),
                "documentDetails", vectorStore.listDocumentDetails());
    }

    /** 后台：按关键词检索知识切片内容（即时 LIKE，不依赖向量模型，跨发布状态检索）。 */
    @GetMapping("/search-content")
    public List<Map<String, Object>> searchContent(@RequestParam String q,
                                                   @RequestParam(defaultValue = "100") int limit) {
        if (q == null || q.trim().length() < 1) return List.of();
        return vectorStore.searchByText(q.trim(), limit);
    }

    @DeleteMapping("/doc")
    public Map<String, Object> delete(@RequestParam String docName) {
        try (EmbeddingIndexCoordinator.Lease ignored = indexCoordinator.knowledgeMutation()) {
        int deleted = vectorStore.deleteByDocName(docName);
        auditService.log("DELETE_DOC", docName + "（" + deleted + " 片）", "admin");
        return Map.of("deleted", deleted);
        }
    }

    @DeleteMapping("/document/{id}")
    public Map<String, Object> deleteDocument(@PathVariable long id) {
        try (EmbeddingIndexCoordinator.Lease ignored = indexCoordinator.knowledgeMutation()) {
        int deleted = vectorStore.deleteDocument(id);
        auditService.log("DELETE_DOC", "文档ID=" + id + "（" + deleted + " 片）", "admin");
        return Map.of("deleted", deleted);
        }
    }

    /** 文档发布状态：内部资料或待审核资料可停用，但不删除，便于审计恢复。 */
    @PutMapping("/doc/status")
    public Map<String, Object> updateDocumentStatus(@RequestBody DocumentStatusRequest req) {
        if (req.docName() == null || req.docName().isBlank()) {
            throw new IllegalArgumentException("文档名不能为空");
        }
        if (!List.of("draft", "published", "disabled").contains(req.status())) {
            throw new IllegalArgumentException("文档状态只能是 draft / published / disabled");
        }
        if (req.validFrom() != null && req.validUntil() != null
                && !req.validUntil().isAfter(req.validFrom())) {
            throw new IllegalArgumentException("失效时间必须晚于生效时间");
        }
        if ("published".equals(req.status())) {
            assertDocumentPublishable(req.docName());
            knowledgeService.ensurePublishedChunkVectors(vectorStore.listByDoc(req.docName()));
        }
        int updated = vectorStore.updateDocumentMetadata(req.docName(), req.status(), req.source(),
                req.reviewer(), req.validFrom(), req.validUntil());
        auditService.log("UPDATE_DOC_STATUS", req.docName() + " → " + req.status(), "admin");
        return Map.of("updated", updated);
    }

    @PutMapping("/document/{id}/status")
    public Map<String, Object> updateDocumentStatus(@PathVariable long id,
                                                     @RequestBody DocumentStatusRequest req) {
        validateDocumentStatus(req);
        if ("published".equals(req.status())) {
            assertDocumentPublishable(id);
            knowledgeService.ensurePublishedChunkVectors(vectorStore.listByDocumentId(id));
        }
        int updated = vectorStore.updateDocumentMetadata(id, req.status(), req.source(),
                req.reviewer(), req.validFrom(), req.validUntil());
        auditService.log("UPDATE_DOC_STATUS", "文档ID=" + id + " → " + req.status(), "admin");
        return Map.of("updated", updated);
    }

    public record DocumentStatusRequest(String docName, String status, String source, String reviewer,
                                        LocalDateTime validFrom, LocalDateTime validUntil) {}

    /** 列出某文档的全部切片（在线编辑页面用） */
    @GetMapping("/chunks")
    public List<Map<String, Object>> chunks(@RequestParam String docName) {
        return vectorStore.listByDoc(docName);
    }

    @GetMapping("/document/{id}/chunks")
    public List<Map<String, Object>> chunks(@PathVariable long id) {
        return vectorStore.listByDocumentId(id);
    }

    /** 编辑单条切片（自动重算向量，保存即生效） */
    @PutMapping("/chunk/{id}")
    public Map<String, Object> updateChunk(@PathVariable long id,
                                           @RequestBody Map<String, String> body) {
        KnowledgeService.UpdateResult result = knowledgeService.updateChunk(id, body.get("content"));
        auditService.log(result.governed() ? "GOVERN_DYNAMIC_PRICE_KNOWLEDGE" : "UPDATE_CHUNK",
                "切片ID=" + id + (result.governed() ? "，已自动停用" : ""), "admin");
        return result.governed()
                ? Map.of("updated", 1, "governed", true,
                        "governanceReason", result.governanceReason())
                : Map.of("updated", 1, "governed", false);
    }

    /** 管理员人工决定单条切片是否参与检索；动态价格内容启用时必须显式确认风险。 */
    @PutMapping("/chunk/{id}/status")
    public Map<String, Object> updateChunkStatus(@PathVariable long id,
                                                  @RequestBody ChunkStatusRequest req) {
        if (!List.of("published", "disabled").contains(req.status())) {
            throw new IllegalArgumentException("切片状态只能是 published / disabled");
        }
        if ("published".equals(req.status())) {
            Map<String, Object> chunk = vectorStore.listById(id);
            assertSafePublishedContent(chunk == null ? null : String.valueOf(chunk.get("content")));
        }
        KnowledgeService.StatusResult result = knowledgeService.setChunkStatus(
                id, req.status(), req.acknowledgeGovernanceRisk());
        String action = result.governanceOverride()
                ? "MANUAL_ENABLE_GOVERNED_KNOWLEDGE"
                : ("published".equals(result.status()) ? "ENABLE_KNOWLEDGE_CHUNK" : "DISABLE_KNOWLEDGE_CHUNK");
        auditService.log(action, "切片ID=" + id + " → " + result.status(), "admin");
        Map<String, Object> response = new java.util.LinkedHashMap<>();
        response.put("updated", 1);
        response.put("status", result.status());
        response.put("governanceOverride", result.governanceOverride());
        response.put("governanceReason", result.governanceReason());
        response.put("hasEmbedding", result.hasEmbedding());
        return response;
    }

    public record ChunkStatusRequest(String status, boolean acknowledgeGovernanceRisk) {}

    /** 切片元数据与安全直回开关；内容一旦编辑，安全直回会自动撤销。 */
    @PutMapping("/chunk/{id}/metadata")
    public Map<String, Object> updateChunkMetadata(@PathVariable long id,
                                                    @RequestBody ChunkMetadataRequest req) {
        if (!List.of("faq", "reference", "offer").contains(req.knowledgeType())) {
            throw new IllegalArgumentException("知识类型不正确");
        }
        if (!List.of("unreviewed", "low", "medium", "high").contains(req.riskLevel())) {
            throw new IllegalArgumentException("风险等级不正确");
        }
        if (!List.of("draft", "published", "disabled").contains(req.status())) {
            throw new IllegalArgumentException("切片状态不正确");
        }
        if ("published".equals(req.status())) {
            Map<String, Object> chunk = vectorStore.listById(id);
            assertSafePublishedContent(chunk == null ? null : String.valueOf(chunk.get("content")));
        }
        boolean safeDirect = req.safeDirect()
                && "faq".equals(req.knowledgeType()) && "low".equals(req.riskLevel());
        knowledgeService.updateChunkMetadata(id, req.project(), req.category(), req.knowledgeType(),
                req.riskLevel(), safeDirect, req.status());
        auditService.log("UPDATE_CHUNK_METADATA", "切片ID=" + id, "admin");
        return Map.of("updated", 1, "safeDirect", safeDirect);
    }

    public record ChunkMetadataRequest(String project, String category, String knowledgeType,
                                       String riskLevel, boolean safeDirect, String status) {}

    private void assertDocumentPublishable(String docName) {
        for (Map<String, Object> row : vectorStore.listByDoc(docName)) {
            assertSafePublishedContent(String.valueOf(row.get("content")));
        }
    }

    private void assertDocumentPublishable(long documentId) {
        for (Map<String, Object> row : vectorStore.listByDocumentId(documentId)) {
            assertSafePublishedContent(String.valueOf(row.get("content")));
        }
    }

    private void validateDocumentStatus(DocumentStatusRequest req) {
        if (!List.of("draft", "published", "disabled").contains(req.status())) {
            throw new IllegalArgumentException("文档状态只能是 draft / published / disabled");
        }
        if (req.validFrom() != null && req.validUntil() != null
                && !req.validUntil().isAfter(req.validFrom())) {
            throw new IllegalArgumentException("失效时间必须晚于生效时间");
        }
    }

    private void assertSafePublishedContent(String content) {
        if (content == null || content.isBlank()) return;
        List<String> blocked = List.of(
                "立竿见影", "保证有效", "包好", "百分百", "永不反弹", "绝对安全", "零风险",
                "完全不痛", "完全不肿", "完全不需要打麻药", "不需要打麻药", "无需打麻药",
                "阿司匹林不用停药", "无需停药", "不用停药", "不必停药", "所有年龄段都可以",
                "其他年龄段都可以", "肯定能看到效果", "肯定能看得到效果", "一次就能看到明显效果",
                "维持很长时间", "不满意无条件退款", "无条件退款", "恢复原价", "一律不能享受活动价",
                "最先进", "从根源解决", "主流支付方式都支持", "所有支付方式都支持",
                "不论多少年限都是可以操作", "不限斑点年限", "做完当天可以提亮肤色", "当天提亮肤色");
        blocked = new java.util.ArrayList<>(blocked);
        blocked.addAll(List.of("锁定名额", "锁定优惠", "额外赠送接车", "赠送接车服务", "最后名额", "仅剩"));
        String hit = blocked.stream().filter(content::contains).findFirst().orElse(null);
        if (hit != null) {
            throw new IllegalArgumentException("内容包含不允许发布的医疗或营销断言：“" + hit + "”，请修改并完成审核后再发布");
        }
    }

    /** 新增单条切片（手动补知识） */
    @PostMapping("/chunk")
    public Map<String, Object> addChunk(@RequestBody Map<String, Object> body) {
        String docName = text(body.get("docName"));
        resourceScopes.run(longValue(body.get("tenantId")), longValue(body.get("kfAccountId")),
                () -> knowledgeService.addChunk(docName, text(body.get("content"))));
        auditService.log("ADD_CHUNK", "文档=" + docName, "admin");
        return Map.of("added", 1);
    }

    /** 删除单条切片 */
    @DeleteMapping("/chunk/{id}")
    public Map<String, Object> deleteChunk(@PathVariable long id) {
        try (EmbeddingIndexCoordinator.Lease ignored = indexCoordinator.knowledgeMutation()) {
        Map<String, Object> result = Map.of("deleted", vectorStore.deleteChunk(id));
        auditService.log("DELETE_CHUNK", "切片ID=" + id, "admin");
        return result;
        }
    }

    /** 异步重建当前管理员可管理范围内的有效知识向量，立即返回任务 ID。 */
    @PostMapping("/reindex")
    public Map<String, Object> reindex(HttpServletRequest request) {
        Object value = request.getAttribute("aics.admin.actor");
        String actor = value == null || String.valueOf(value).isBlank() ? "admin" : String.valueOf(value);
        long taskId = reindexService.submit(actor);
        auditService.log("REINDEX_KNOWLEDGE", "已提交任务ID=" + taskId, actor);
        return Map.of(
                "taskId", taskId,
                "status", "queued",
                "message", "已提交，正在使用当前向量模型重建有效知识");
    }

    @GetMapping("/reindex/status")
    public Map<String, Object> reindexStatus(@RequestParam long taskId) {
        return reindexService.status(taskId);
    }

    @GetMapping("/facts")
    public List<Map<String, Object>> facts() {
        return businessFactService.listAll();
    }

    @PutMapping("/facts/{factKey:.+}")
    public Map<String, Object> updateFact(@PathVariable String factKey,
                                          @RequestBody BusinessFactRequest req) {
        if (req.validFrom() != null && req.validUntil() != null
                && !req.validUntil().isAfter(req.validFrom())) {
            throw new IllegalArgumentException("失效时间必须晚于生效时间");
        }
        resourceScopes.run(req.tenantId(), req.kfAccountId(),
                () -> businessFactService.upsert(factKey, req.project(), req.factType(), req.displayText(),
                        req.status(), req.source(), req.reviewer(), req.validFrom(), req.validUntil()));
        auditService.log("UPDATE_BUSINESS_FACT", factKey + " → " + req.status(), "admin");
        return Map.of("updated", 1, "factKey", factKey);
    }

    public record BusinessFactRequest(String project, String factType, String displayText,
                                      String status, String source, String reviewer,
                                      LocalDateTime validFrom, LocalDateTime validUntil,
                                      Long tenantId, Long kfAccountId) {}

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private Long longValue(Object value) {
        if (value == null || String.valueOf(value).isBlank()) return null;
        if (value instanceof Number number) return number.longValue();
        try { return Long.parseLong(String.valueOf(value)); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("资源归属 ID 格式不正确"); }
    }
}
