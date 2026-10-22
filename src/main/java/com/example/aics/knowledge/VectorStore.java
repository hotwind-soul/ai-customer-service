package com.example.aics.knowledge;

import com.example.aics.channel.TenantExecutionContext;
import com.example.aics.config.ScopedDataAccess;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** PostgreSQL + pgvector 存取，并负责发布状态、项目过滤和关键词召回。 */
@Repository
public class VectorStore {

    public static final int EMBEDDING_DIMENSION = 1024;

    private static final String ACTIVE_FILTER = " k.status = 'published' AND k.embedding IS NOT NULL " +
            "AND COALESCE(d.status, 'published') = 'published' " +
            "AND COALESCE(d.doc_type, 'reference') <> 'internal' " +
            "AND (d.valid_from IS NULL OR d.valid_from <= now()) " +
            "AND (d.valid_until IS NULL OR d.valid_until > now()) ";

    private final JdbcTemplate jdbc;

    public VectorStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 检索结果；保留四参数构造器兼容现有调用和测试。 */
    public record Match(Long id, String docName, String content, double distance,
                        String project, String category, String knowledgeType,
                        String riskLevel, boolean safeDirect,
                        String retrievalMethod, double retrievalScore) {
        public Match(Long id, String docName, String content, double distance) {
            this(id, docName, content, distance, null, null, "reference",
                    "unreviewed", false, "vector", 0.0);
        }

        public Match withRank(String method, double score) {
            return new Match(id, docName, content, distance, project, category,
                    knowledgeType, riskLevel, safeDirect, method, score);
        }
    }

    /** 完成向量化后一次性原子替换文档，避免重复上传和半份文档残留。 */
    public record ChunkInput(String content, String searchText, float[] vector,
                             String project, String category, String knowledgeType,
                             String riskLevel, boolean safeDirect, String embeddingStrategy,
                             String status, String governanceReason) {
        public ChunkInput(String content, String searchText, float[] vector,
                          String project, String category, String knowledgeType,
                          String riskLevel, boolean safeDirect, String embeddingStrategy) {
            this(content, searchText, vector, project, category, knowledgeType,
                    riskLevel, safeDirect, embeddingStrategy, "published", null);
        }
    }

    /** 保存一片文档切片及其向量（兼容旧调用；新导入统一走 replaceDocument）。 */
    public void save(String docName, int chunkIndex, String content, float[] vector) {
        TenantExecutionContext.Scope scope = TenantExecutionContext.current();
        Long tenantId = scope == null ? null : scope.tenantId();
        Long kfAccountId = scope == null ? null : scope.kfAccountId();
        jdbc.update(
                "INSERT INTO knowledge_chunk(tenant_id,kf_account_id,doc_name,chunk_index,content,search_text,embedding,embedding_strategy) " +
                        "VALUES (?,?,?,?,?,?,CAST(? AS vector),'content-v1')",
                tenantId, kfAccountId, docName, chunkIndex, content, content, toVectorLiteral(vector));
    }

    @Transactional
    public void replaceDocument(String docName, String docType, List<ChunkInput> chunks) {
        String initialStatus = "internal".equals(docType) ? "disabled" : "published";
        TenantExecutionContext.Scope scope = TenantExecutionContext.current();
        Long tenantId = scope == null ? null : scope.tenantId();
        Long kfAccountId = scope == null ? null : scope.kfAccountId();
        Long documentId = jdbc.queryForObject(
                "INSERT INTO knowledge_document(tenant_id,kf_account_id,doc_name,doc_type,status) VALUES (?,?,?,?,?) " +
                        "ON CONFLICT (tenant_id,kf_account_id,doc_name) DO UPDATE SET doc_type=EXCLUDED.doc_type, " +
                        "version=knowledge_document.version+1, update_time=now() RETURNING id",
                Long.class, tenantId, kfAccountId, docName, docType, initialStatus);
        jdbc.update("DELETE FROM knowledge_chunk WHERE doc_name=? AND tenant_id IS NOT DISTINCT FROM ? " +
                        "AND kf_account_id IS NOT DISTINCT FROM ?", docName, tenantId, kfAccountId);
        for (int i = 0; i < chunks.size(); i++) {
            ChunkInput c = chunks.get(i);
            jdbc.update(
                    "INSERT INTO knowledge_chunk(document_id, tenant_id, kf_account_id, doc_name, chunk_index, content, search_text, " +
                            "project, category, knowledge_type, risk_level, safe_direct, status, " +
                            "embedding_strategy, embedding, governance_reason) " +
                            "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,CAST(? AS vector),?)",
                    documentId, tenantId, kfAccountId, docName, i, c.content(), c.searchText(), c.project(), c.category(),
                    c.knowledgeType(), c.riskLevel(), c.safeDirect(), c.status(), c.embeddingStrategy(),
                    nullableVectorLiteral(c.vector()), c.governanceReason());
        }
    }

    /** 纯向量候选；只查询已发布的客户知识，并允许按当前项目缩小范围。 */
    public List<Match> searchVector(float[] questionVector, int topK, String project) {
        StringBuilder sql = new StringBuilder(
                "SELECT k.id, k.doc_name, k.content, k.embedding <=> CAST(? AS vector) AS distance, " +
                        "k.project, k.category, k.knowledge_type, k.risk_level, k.safe_direct " +
                        "FROM knowledge_chunk k LEFT JOIN knowledge_document d ON d.id=k.document_id WHERE " +
                        ACTIVE_FILTER);
        List<Object> args = new ArrayList<>();
        args.add(toVectorLiteral(questionVector));
        ScopedDataAccess.appendAnd(sql, args, "k");
        appendProjectFilter(sql, args, project);
        sql.append(" ORDER BY distance LIMIT ?");
        args.add(Math.max(1, topK));
        return jdbc.query(sql.toString(), (rs, n) -> mapMatch(rs, "vector", 0.0), args.toArray());
    }

    /**
     * 中文关键词候选：按完整问法、项目和咨询维度词命中数排序；仍计算真实余弦距离，
     * 便于后续相关度门槛继续发挥作用。
     */
    public List<Match> searchKeyword(float[] questionVector, List<String> terms,
                                     int topK, String project) {
        if (terms == null || terms.isEmpty()) return List.of();
        String textExpr = "lower(COALESCE(k.search_text, k.content))";
        StringBuilder score = new StringBuilder();
        List<Object> scoreArgs = new ArrayList<>();
        for (int i = 0; i < terms.size(); i++) {
            if (i > 0) score.append(" + ");
            score.append("CASE WHEN ").append(textExpr).append(" LIKE ? THEN 1 ELSE 0 END");
            scoreArgs.add("%" + terms.get(i).toLowerCase() + "%");
        }

        StringBuilder sql = new StringBuilder(
                "SELECT k.id, k.doc_name, k.content, k.embedding <=> CAST(? AS vector) AS distance, " +
                        "k.project, k.category, k.knowledge_type, k.risk_level, k.safe_direct, (" + score +
                        ") AS lexical_score FROM knowledge_chunk k " +
                        "LEFT JOIN knowledge_document d ON d.id=k.document_id WHERE " + ACTIVE_FILTER + "AND (");
        List<Object> args = new ArrayList<>();
        args.add(toVectorLiteral(questionVector));
        args.addAll(scoreArgs);
        for (int i = 0; i < terms.size(); i++) {
            if (i > 0) sql.append(" OR ");
            sql.append(textExpr).append(" LIKE ?");
            args.add("%" + terms.get(i).toLowerCase() + "%");
        }
        sql.append(")");
        ScopedDataAccess.appendAnd(sql, args, "k");
        appendProjectFilter(sql, args, project);
        sql.append(" ORDER BY lexical_score DESC, distance LIMIT ?");
        args.add(Math.max(1, topK));
        return jdbc.query(sql.toString(), (rs, n) ->
                mapMatch(rs, "keyword", rs.getDouble("lexical_score")), args.toArray());
    }

    /**
     * 后台全文检索：按关键词模糊匹配切片内容，跨发布状态检索（便于核查/修正），
     * 并返回文档状态标注。即时执行、不依赖向量模型。
     */
    public List<Map<String, Object>> searchByText(String keyword, int limit) {
        String kw = "%" + keyword.toLowerCase() + "%";
        StringBuilder sql = new StringBuilder(
                "SELECT k.id, k.doc_name AS doc_name, k.chunk_index AS chunk_index, k.content AS content, " +
                        "k.project AS project, k.category AS category, k.knowledge_type AS knowledge_type, " +
                        "k.status AS chunk_status, k.governance_reason AS governance_reason, " +
                        "k.governance_override AS governance_override, " +
                        "COALESCE(d.status, 'published') AS doc_status " +
                        "FROM knowledge_chunk k LEFT JOIN knowledge_document d ON d.id = k.document_id " +
                        "WHERE lower(k.content) LIKE ?");
        List<Object> args = new ArrayList<>();
        args.add(kw);
        ScopedDataAccess.appendAnd(sql, args, "k");
        sql.append(" ORDER BY k.doc_name, k.chunk_index LIMIT ?");
        args.add(Math.max(1, Math.min(limit, 200)));
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    /** 兼容旧调用。 */
    public List<Match> search(float[] questionVector, int topK) {
        return searchVector(questionVector, topK, null);
    }

    private void appendProjectFilter(StringBuilder sql, List<Object> args, String project) {
        if (project != null && !project.isBlank()) {
            // 子项目可复用父项目知识，但不会反向把其他子项目内容带给父项目。
            // 优先使用规范化 project_id；保留 project 字符串兼容旧切片。
            sql.append(" AND (k.project IS NULL OR k.project='' OR k.project=? " +
                    "OR k.project_id IN (SELECT child.parent_id FROM service_project child " +
                    "WHERE child.name=? AND child.status='published' AND child.parent_id IS NOT NULL))");
            args.add(project);
            args.add(project);
        }
    }

    private Match mapMatch(ResultSet rs, String method, double score) throws SQLException {
        return new Match(rs.getLong("id"), rs.getString("doc_name"), rs.getString("content"),
                rs.getDouble("distance"), rs.getString("project"), rs.getString("category"),
                rs.getString("knowledge_type"), rs.getString("risk_level"),
                rs.getBoolean("safe_direct"), method, score);
    }

    public int count() {
        StringBuilder sql = new StringBuilder(
                "SELECT count(*) FROM knowledge_chunk k LEFT JOIN knowledge_document d ON d.id=k.document_id " +
                        "WHERE " + ACTIVE_FILTER);
        List<Object> args = new ArrayList<>();
        ScopedDataAccess.appendAnd(sql, args, "k");
        Integer n = jdbc.queryForObject(sql.toString(), Integer.class, args.toArray());
        return n == null ? 0 : n;
    }

    /** 兼容旧管理页面的文档标签。 */
    public List<String> listDocuments() {
        StringBuilder sql = new StringBuilder(
                "SELECT k.doc_name || '（' || count(*) || ' 片）' FROM knowledge_chunk k WHERE 1=1");
        List<Object> args = new ArrayList<>();
        ScopedDataAccess.appendAnd(sql, args, "k");
        sql.append(" GROUP BY k.doc_name ORDER BY k.doc_name");
        return jdbc.queryForList(sql.toString(), String.class, args.toArray());
    }

    public List<Map<String, Object>> listDocumentDetails() {
        StringBuilder sql = new StringBuilder(
                "SELECT d.id, d.tenant_id, d.kf_account_id, d.doc_name, d.doc_type, d.status, d.version, d.source, d.reviewer, " +
                        "d.valid_from, d.valid_until, " +
                        "count(k.id) AS chunk_count, d.update_time FROM knowledge_document d " +
                        "LEFT JOIN knowledge_chunk k ON k.document_id=d.id WHERE 1=1");
        List<Object> args = new ArrayList<>();
        ScopedDataAccess.appendAnd(sql, args, "d");
        sql.append(" GROUP BY d.id ORDER BY d.update_time DESC");
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    @Transactional
    public int deleteByDocName(String docName) {
        ScopedDataAccess.Condition chunkScope = ScopedDataAccess.condition("k");
        List<Object> chunkArgs = new ArrayList<>();
        chunkArgs.add(docName);
        chunkArgs.addAll(chunkScope.args());
        int deleted = jdbc.update("DELETE FROM knowledge_chunk k WHERE k.doc_name = ?" +
                (chunkScope.active() ? " AND " + chunkScope.sql() : ""), chunkArgs.toArray());
        ScopedDataAccess.Condition docScope = ScopedDataAccess.condition("d");
        List<Object> docArgs = new ArrayList<>();
        docArgs.add(docName);
        docArgs.addAll(docScope.args());
        jdbc.update("DELETE FROM knowledge_document d WHERE d.doc_name = ?" +
                (docScope.active() ? " AND " + docScope.sql() : ""), docArgs.toArray());
        return deleted;
    }

    @Transactional
    public int deleteDocument(long documentId) {
        ScopedDataAccess.Condition chunkScope = ScopedDataAccess.condition("k");
        List<Object> chunkArgs = new ArrayList<>();
        chunkArgs.add(documentId);
        chunkArgs.addAll(chunkScope.args());
        int deleted = jdbc.update("DELETE FROM knowledge_chunk k WHERE k.document_id = ?" +
                (chunkScope.active() ? " AND " + chunkScope.sql() : ""), chunkArgs.toArray());
        ScopedDataAccess.Condition docScope = ScopedDataAccess.condition("d");
        List<Object> docArgs = new ArrayList<>();
        docArgs.add(documentId);
        docArgs.addAll(docScope.args());
        int documents = jdbc.update("DELETE FROM knowledge_document d WHERE d.id = ?" +
                (docScope.active() ? " AND " + docScope.sql() : ""), docArgs.toArray());
        if (documents == 0) throw new IllegalArgumentException("文档不存在或无权操作");
        return deleted;
    }

    public int updateDocumentMetadata(String docName, String status, String source, String reviewer,
                                      LocalDateTime validFrom, LocalDateTime validUntil) {
        StringBuilder sql = new StringBuilder(
                "UPDATE knowledge_document d SET status=?, source=?, reviewer=?, valid_from=?, valid_until=?, " +
                        "update_time=now() WHERE doc_name=?");
        List<Object> args = new ArrayList<>();
        args.add(status); args.add(emptyToNull(source)); args.add(emptyToNull(reviewer));
        args.add(validFrom); args.add(validUntil); args.add(docName);
        ScopedDataAccess.appendAnd(sql, args, "d");
        return jdbc.update(sql.toString(), args.toArray());
    }

    public int updateDocumentMetadata(long documentId, String status, String source, String reviewer,
                                      LocalDateTime validFrom, LocalDateTime validUntil) {
        StringBuilder sql = new StringBuilder(
                "UPDATE knowledge_document d SET status=?, source=?, reviewer=?, valid_from=?, valid_until=?, " +
                        "update_time=now() WHERE id=?");
        List<Object> args = new ArrayList<>();
        args.add(status); args.add(emptyToNull(source)); args.add(emptyToNull(reviewer));
        args.add(validFrom); args.add(validUntil); args.add(documentId);
        ScopedDataAccess.appendAnd(sql, args, "d");
        int updated = jdbc.update(sql.toString(), args.toArray());
        if (updated == 0) throw new IllegalArgumentException("文档不存在或无权操作");
        return updated;
    }

    public List<Map<String, Object>> listByDoc(String docName) {
        StringBuilder sql = new StringBuilder(
                "SELECT id, doc_name, chunk_index, content, search_text, project, category, knowledge_type, " +
                        "risk_level, safe_direct, status, embedding_strategy, governance_reason, governance_override, " +
                        "embedding IS NOT NULL AS has_embedding, " +
                        "create_time, update_time " +
                        "FROM knowledge_chunk k WHERE doc_name = ?");
        List<Object> args = new ArrayList<>();
        args.add(docName);
        ScopedDataAccess.appendAnd(sql, args, "k");
        sql.append(" ORDER BY chunk_index");
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    public List<Map<String, Object>> listByDocumentId(long documentId) {
        StringBuilder sql = new StringBuilder(
                "SELECT id, document_id, tenant_id, kf_account_id, doc_name, chunk_index, content, search_text, " +
                        "project, category, knowledge_type, risk_level, safe_direct, status, embedding_strategy, " +
                        "governance_reason, governance_override, embedding IS NOT NULL AS has_embedding, create_time, update_time " +
                        "FROM knowledge_chunk k WHERE document_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(documentId);
        ScopedDataAccess.appendAnd(sql, args, "k");
        sql.append(" ORDER BY chunk_index");
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    public Map<String, Object> listById(long id) {
        StringBuilder sql = new StringBuilder(
                "SELECT id, content, search_text, doc_name, category, knowledge_type, status, " +
                        "embedding_strategy, governance_reason, governance_override, " +
                        "embedding IS NOT NULL AS has_embedding FROM knowledge_chunk k WHERE id=?");
        List<Object> args = new ArrayList<>();
        args.add(id);
        ScopedDataAccess.appendAnd(sql, args, "k");
        List<Map<String, Object>> rows = jdbc.queryForList(sql.toString(), args.toArray());
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<Map<String, Object>> listAllForReindex() {
        StringBuilder sql = new StringBuilder(
                "SELECT k.id, k.content, k.category, k.knowledge_type FROM knowledge_chunk k " +
                        "LEFT JOIN knowledge_document d ON d.id=k.document_id " +
                        "WHERE k.status='published' AND k.knowledge_type<>'offer' " +
                        "AND (k.governance_reason IS NULL OR k.governance_override=true) " +
                        "AND COALESCE(d.status,'published')='published' " +
                        "AND COALESCE(d.doc_type,'reference')<>'internal'");
        List<Object> args = new ArrayList<>();
        ScopedDataAccess.appendAnd(sql, args, "k");
        sql.append(" ORDER BY id");
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    public void updateChunk(long id, String content, String searchText, float[] vector,
                            String strategy) {
        StringBuilder sql = new StringBuilder(
                "UPDATE knowledge_chunk k SET content=?, search_text=?, embedding=CAST(? AS vector), " +
                        "embedding_strategy=?, safe_direct=false, " +
                        "status=CASE WHEN embedding_strategy=? THEN 'published' ELSE status END, " +
                        "risk_level=CASE WHEN embedding_strategy=? THEN 'unreviewed' ELSE risk_level END, " +
                        "governance_reason=NULL, governance_override=false, update_time=now() WHERE id=?");
        List<Object> args = new ArrayList<>();
        args.add(content); args.add(searchText); args.add(toVectorLiteral(vector)); args.add(strategy);
        args.add(DynamicPriceGovernance.STRATEGY); args.add(DynamicPriceGovernance.STRATEGY); args.add(id);
        ScopedDataAccess.appendAnd(sql, args, "k");
        if (jdbc.update(sql.toString(), args.toArray()) == 0) {
            throw new IllegalArgumentException("知识切片不存在或无权操作");
        }
    }

    /** 兼容旧调用。 */
    public void updateChunk(long id, String content, float[] vector) {
        updateChunk(id, content, content, vector, "content-v1");
    }

    public void governDynamicPriceChunk(long id, String content, String searchText, String reason) {
        StringBuilder sql = new StringBuilder(
                "UPDATE knowledge_chunk k SET content=?, search_text=?, embedding=NULL, " +
                        "embedding_strategy=?, risk_level='high', safe_direct=false, status='disabled', " +
                        "governance_reason=?, governance_override=false, update_time=now() WHERE id=?");
        List<Object> args = new ArrayList<>();
        args.add(content); args.add(searchText); args.add(DynamicPriceGovernance.STRATEGY);
        args.add(reason); args.add(id);
        ScopedDataAccess.appendAnd(sql, args, "k");
        if (jdbc.update(sql.toString(), args.toArray()) == 0) {
            throw new IllegalArgumentException("知识切片不存在或无权操作");
        }
    }

    public void updateChunkMetadata(long id, String project, String category,
                                    String knowledgeType, String riskLevel,
                                    boolean safeDirect, String status) {
        StringBuilder sql = new StringBuilder(
                "UPDATE knowledge_chunk k SET project=?, category=?, knowledge_type=?, risk_level=?, " +
                        "safe_direct=?, status=?, update_time=now() WHERE id=?");
        List<Object> args = new ArrayList<>();
        args.add(emptyToNull(project)); args.add(emptyToNull(category)); args.add(knowledgeType);
        args.add(riskLevel); args.add(safeDirect); args.add(status); args.add(id);
        ScopedDataAccess.appendAnd(sql, args, "k");
        if (jdbc.update(sql.toString(), args.toArray()) == 0) {
            throw new IllegalArgumentException("知识切片不存在或无权操作");
        }
    }

    /** 管理员明确接受动态内容风险后，将该切片重新纳入客户检索。 */
    public void publishGovernedChunk(long id, String content, String searchText, float[] vector,
                                     String strategy, String reason) {
        StringBuilder sql = new StringBuilder(
                "UPDATE knowledge_chunk k SET content=?, search_text=?, embedding=CAST(? AS vector), " +
                        "embedding_strategy=?, risk_level='high', safe_direct=false, status='published', " +
                        "governance_reason=?, governance_override=true, update_time=now() WHERE id=?");
        List<Object> args = new ArrayList<>();
        args.add(content); args.add(searchText); args.add(toVectorLiteral(vector)); args.add(strategy);
        args.add(reason); args.add(id);
        ScopedDataAccess.appendAnd(sql, args, "k");
        if (jdbc.update(sql.toString(), args.toArray()) == 0) {
            throw new IllegalArgumentException("知识切片不存在或无权操作");
        }
    }

    public void updateChunkStatus(long id, String status) {
        StringBuilder sql = new StringBuilder(
                "UPDATE knowledge_chunk k SET status=?, update_time=now() WHERE id=?");
        List<Object> args = new ArrayList<>();
        args.add(status); args.add(id);
        ScopedDataAccess.appendAnd(sql, args, "k");
        if (jdbc.update(sql.toString(), args.toArray()) == 0) {
            throw new IllegalArgumentException("知识切片不存在或无权操作");
        }
    }

    public void updateSearchVector(long id, String searchText, float[] vector, String strategy) {
        StringBuilder sql = new StringBuilder(
                "UPDATE knowledge_chunk k SET search_text=?, embedding=CAST(? AS vector), " +
                        "embedding_strategy=?, update_time=now() WHERE id=?");
        List<Object> args = new ArrayList<>();
        args.add(searchText); args.add(toVectorLiteral(vector)); args.add(strategy); args.add(id);
        ScopedDataAccess.appendAnd(sql, args, "k");
        jdbc.update(sql.toString(), args.toArray());
    }

    public record ReindexInput(long id, String sourceContent, String searchText,
                               float[] vector, String strategy) {}

    /** 安全切换提交阶段阻止知识切片并发写入，锁只持有到短事务结束。 */
    public void lockKnowledgeChunksForEmbeddingSwitch() {
        jdbc.execute("LOCK TABLE knowledge_chunk IN ACCESS EXCLUSIVE MODE");
    }

    /** 所有新向量准备完成后再事务写入，避免重建失败时留下半新半旧索引。 */
    @Transactional
    public void replaceSearchVectors(List<ReindexInput> rows) {
        for (ReindexInput row : rows) {
            updateSearchVector(row.id(), row.searchText(), row.vector(), row.strategy());
        }
    }

    @Transactional
    public void addChunk(String docName, String content, String searchText, float[] vector,
                         String project, String category, String knowledgeType) {
        TenantExecutionContext.Scope scope = TenantExecutionContext.current();
        Long tenantId = scope == null ? null : scope.tenantId();
        Long kfAccountId = scope == null ? null : scope.kfAccountId();
        Long documentId = jdbc.queryForObject(
                "INSERT INTO knowledge_document(tenant_id,kf_account_id,doc_name,doc_type,status) VALUES (?,?,?,?,'published') " +
                        "ON CONFLICT (tenant_id,kf_account_id,doc_name) DO UPDATE SET update_time=now() RETURNING id",
                Long.class, tenantId, kfAccountId, docName, knowledgeType);
        // 串行化同一文档下的切片写入：max(chunk_index)+1 是"先查后插"，并发补知识会算出同一下标，
        // 而 knowledge_chunk 没有 (document_id, chunk_index) 唯一约束兜底，会造成切片顺序不确定。
        jdbc.queryForObject("SELECT id FROM knowledge_document WHERE id=? FOR UPDATE", Long.class, documentId);
        Integer maxIdx = jdbc.queryForObject(
                "SELECT COALESCE(max(chunk_index), -1) FROM knowledge_chunk WHERE doc_name=? " +
                        "AND tenant_id IS NOT DISTINCT FROM ? AND kf_account_id IS NOT DISTINCT FROM ?",
                Integer.class, docName, tenantId, kfAccountId);
        jdbc.update(
                "INSERT INTO knowledge_chunk(document_id,tenant_id,kf_account_id,doc_name,chunk_index,content,search_text,embedding," +
                        "project,category,knowledge_type,risk_level,safe_direct,status,embedding_strategy) " +
                        "VALUES (?,?,?,?,?,?,?,CAST(? AS vector),?,?,?,'unreviewed',false,'published','content-v1')",
                documentId, tenantId, kfAccountId, docName, (maxIdx == null ? -1 : maxIdx) + 1, content, searchText,
                toVectorLiteral(vector), emptyToNull(project), emptyToNull(category), knowledgeType);
    }

    @Transactional
    public void addGovernedChunk(String docName, String content, String searchText,
                                 String project, String category, String knowledgeType,
                                 String reason) {
        TenantExecutionContext.Scope scope = TenantExecutionContext.current();
        Long tenantId = scope == null ? null : scope.tenantId();
        Long kfAccountId = scope == null ? null : scope.kfAccountId();
        Long documentId = jdbc.queryForObject(
                "INSERT INTO knowledge_document(tenant_id,kf_account_id,doc_name,doc_type,status) VALUES (?,?,?,?,'published') " +
                        "ON CONFLICT (tenant_id,kf_account_id,doc_name) DO UPDATE SET update_time=now() RETURNING id",
                Long.class, tenantId, kfAccountId, docName, knowledgeType);
        // 与 addChunk 同理：锁定文档行串行化 max(chunk_index)+1 的先查后插。
        jdbc.queryForObject("SELECT id FROM knowledge_document WHERE id=? FOR UPDATE", Long.class, documentId);
        Integer maxIdx = jdbc.queryForObject(
                "SELECT COALESCE(max(chunk_index), -1) FROM knowledge_chunk WHERE doc_name=? " +
                        "AND tenant_id IS NOT DISTINCT FROM ? AND kf_account_id IS NOT DISTINCT FROM ?",
                Integer.class, docName, tenantId, kfAccountId);
        jdbc.update(
                "INSERT INTO knowledge_chunk(document_id,tenant_id,kf_account_id,doc_name,chunk_index,content,search_text," +
                        "embedding,project,category,knowledge_type,risk_level,safe_direct,status,embedding_strategy," +
                        "governance_reason) VALUES (?,?,?,?,?,?,?,NULL,?,?,?,'high',false,'disabled',?,?)",
                documentId, tenantId, kfAccountId, docName, (maxIdx == null ? -1 : maxIdx) + 1,
                content, searchText, emptyToNull(project), emptyToNull(category), knowledgeType,
                DynamicPriceGovernance.STRATEGY, reason);
    }

    /** 兼容旧调用。 */
    public void addChunk(String docName, String content, float[] vector) {
        addChunk(docName, content, content, vector, null, null, "reference");
    }

    public int deleteChunk(long id) {
        StringBuilder sql = new StringBuilder("DELETE FROM knowledge_chunk k WHERE id = ?");
        List<Object> args = new ArrayList<>();
        args.add(id);
        ScopedDataAccess.appendAnd(sql, args, "k");
        return jdbc.update(sql.toString(), args.toArray());
    }

    private String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String toVectorLiteral(float[] vector) {
        if (vector == null || vector.length != EMBEDDING_DIMENSION) {
            throw new IllegalArgumentException("向量维度必须为 " + EMBEDDING_DIMENSION
                    + "，实际为 " + (vector == null ? 0 : vector.length));
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            if (!Float.isFinite(vector[i])) {
                throw new IllegalArgumentException("向量包含非有限数值，位置=" + i);
            }
            if (i > 0) sb.append(',');
            sb.append(vector[i]);
        }
        return sb.append(']').toString();
    }

    private String nullableVectorLiteral(float[] vector) {
        return vector == null ? null : toVectorLiteral(vector);
    }

    public static String toContext(List<Match> matches) {
        return matches.stream()
                .map(m -> "【来自《" + m.docName() + "》】\n" + m.content())
                .collect(Collectors.joining("\n\n---\n\n"));
    }
}
