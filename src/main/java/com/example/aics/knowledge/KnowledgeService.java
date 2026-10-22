package com.example.aics.knowledge;

import com.example.aics.ai.ModelGateway;
import com.example.aics.config.AiConfigCatalog;
import com.example.aics.config.ConfigService;
import com.example.aics.project.ProjectCatalogService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;

/** 文档解析、原子入库、混合检索与索引重建。 */
@Service
public class KnowledgeService {

    private static final int EMBED_BATCH_SIZE = 10;

    private final ModelGateway modelGateway;
    private final DocumentTextExtractor extractor;
    private final TextChunker chunker;
    private final VectorStore vectorStore;
    private final ConfigService config;
    private final EmbeddingIndexCoordinator indexCoordinator;
    private final ProjectCatalogService projectCatalog;
    private final RerankService rerankService;

    /** Spring 入口：带精排服务。 */
    @Autowired
    public KnowledgeService(DocumentTextExtractor extractor, TextChunker chunker,
                            VectorStore vectorStore, ModelGateway modelGateway,
                            ConfigService config, EmbeddingIndexCoordinator indexCoordinator,
                            ProjectCatalogService projectCatalog, RerankService rerankService) {
        this.extractor = extractor;
        this.chunker = chunker;
        this.vectorStore = vectorStore;
        this.modelGateway = modelGateway;
        this.config = config;
        this.indexCoordinator = indexCoordinator;
        this.projectCatalog = projectCatalog;
        this.rerankService = rerankService;
    }

    /** 兼容旧调用与单测：不接精排（检索顺序不受影响）。 */
    public KnowledgeService(DocumentTextExtractor extractor, TextChunker chunker,
                            VectorStore vectorStore, ModelGateway modelGateway,
                            ConfigService config, EmbeddingIndexCoordinator indexCoordinator,
                            ProjectCatalogService projectCatalog) {
        this(extractor, chunker, vectorStore, modelGateway, config, indexCoordinator,
                projectCatalog, null);
    }

    private record Draft(String content, String searchText, String project, String category,
                         String knowledgeType, String riskLevel, boolean safeDirect,
                         String embeddingStrategy, String status, String governanceReason) {}

    public record UpdateResult(boolean governed, String governanceReason) {}

    public record StatusResult(String status, boolean governanceOverride,
                               String governanceReason, boolean hasEmbedding) {}

    /** 上传会先完成全部解析和向量化，最后在事务中替换同名文档。 */
    public int importDocument(MultipartFile file) throws IOException {
        try (EmbeddingIndexCoordinator.Lease ignored = indexCoordinator.knowledgeMutation()) {
        String docName = file.getOriginalFilename() == null ? "未命名文档" : file.getOriginalFilename();
        String docType = detectDocType(docName);
        List<Draft> drafts;

        List<DocumentTextExtractor.PriceRow> priceRows = extractor.tryExtractPriceRows(file);
        if (priceRows != null) {
            return projectCatalog.importPriceRows(docName, priceRows);
        }
        List<String[]> faqRows = extractor.tryExtractFaqRows(file);
        if (faqRows != null) {
            drafts = faqRows.stream().map(this::faqDraft).toList();
            docType = "faq";
        } else {
            String text = extractor.extract(file);
            if (text == null || text.isBlank()) {
                throw new IllegalArgumentException("文档里没有解析出文字内容（扫描件 PDF 请先转图片或使用 OCR 导入）");
            }
            String project = inferSingleProject(docName + "\n" + text);
            String currentDocType = docType;
            drafts = chunker.chunk(text).stream()
                    .map(content -> referenceDraft(content, project, currentDocType))
                    .toList();
        }

        List<VectorStore.ChunkInput> inputs = embedDrafts(drafts);
        vectorStore.replaceDocument(docName, docType, inputs);
        return inputs.size();
        }
    }

    private Draft faqDraft(String[] row) {
        String question = row[0].trim();
        String answer = row[1].trim();
        String category = row.length > 2 && row[2] != null ? row[2].trim() : "";
        String project = projects().contains(category) ? category : null;
        String content = "问：" + question + "\n答：" + answer
                + (category.isBlank() ? "" : "\n分类：" + category);
        String searchText = faqSearchText(question, category);
        DynamicPriceGovernance.Decision governance =
                DynamicPriceGovernance.inspect(content, "faq");
        // 新 FAQ 默认不允许零模型直回；必须由管理员核对风险等级后显式开启。
        return new Draft(content, searchText, project, emptyToNull(category), "faq",
                governance.governed() ? "high" : "unreviewed", false,
                governance.governed() ? DynamicPriceGovernance.STRATEGY : "faq-question-v1",
                governance.governed() ? "disabled" : "published", governance.reason());
    }

    private Draft referenceDraft(String content, String project, String knowledgeType) {
        DynamicPriceGovernance.Decision governance =
                DynamicPriceGovernance.inspect(content, knowledgeType);
        return new Draft(content, content, project, null, knowledgeType,
                governance.governed() ? "high" : "unreviewed", false,
                governance.governed() ? DynamicPriceGovernance.STRATEGY : "content-v1",
                governance.governed() ? "disabled" : "published", governance.reason());
    }

    private List<VectorStore.ChunkInput> embedDrafts(List<Draft> drafts) {
        List<VectorStore.ChunkInput> result = new ArrayList<>(drafts.size());
        List<Integer> embeddable = new ArrayList<>();
        for (int i = 0; i < drafts.size(); i++) {
            Draft d = drafts.get(i);
            result.add(new VectorStore.ChunkInput(d.content(), d.searchText(), null,
                    d.project(), d.category(), d.knowledgeType(), d.riskLevel(),
                    d.safeDirect(), d.embeddingStrategy(), d.status(), d.governanceReason()));
            if ("published".equals(d.status()) && d.governanceReason() == null) embeddable.add(i);
        }
        for (int i = 0; i < embeddable.size(); i += EMBED_BATCH_SIZE) {
            List<Integer> batch = embeddable.subList(i, Math.min(i + EMBED_BATCH_SIZE, embeddable.size()));
            List<float[]> vectors = modelGateway.embedAll(
                    batch.stream().map(index -> drafts.get(index).searchText()).toList());
            if (vectors == null || vectors.size() != batch.size()) {
                throw new IllegalArgumentException("向量模型返回数量与知识切片数量不一致");
            }
            for (int j = 0; j < batch.size(); j++) {
                int index = batch.get(j);
                Draft d = drafts.get(index);
                result.set(index, new VectorStore.ChunkInput(d.content(), d.searchText(), vectors.get(j),
                        d.project(), d.category(), d.knowledgeType(), d.riskLevel(),
                        d.safeDirect(), d.embeddingStrategy(), d.status(), d.governanceReason()));
            }
        }
        return result;
    }

    /** 向量召回 + 中文关键词召回 + RRF 融合，并按项目过滤。 */
    public List<VectorStore.Match> search(String question, int topK) {
        return search(question, topK, null, null);
    }

    /** 供 ChatEngine 等调用方把旁路/检索相关的可观测性计数写进统一指标口径。 */
    public void counter(String name, long n) {
        modelGateway.counter(name, n);
    }

    public List<VectorStore.Match> search(String question, int topK, String project, String aspect) {
        float[] vector = modelGateway.embed(question);
        int multiplier = Math.max(1, Math.min(config.getInt("retrieval.poolMultiplier", 3), 10));
        int pool = Math.max(10, topK * multiplier);
        List<VectorStore.Match> vectorHits = vectorStore.searchVector(vector, pool, project);
        List<VectorStore.Match> keywordHits = vectorStore.searchKeyword(
                vector, lexicalTerms(question, project, aspect), pool, project);

        Map<Long, Ranked> merged = new LinkedHashMap<>();
        addRanks(merged, vectorHits, "vector", 1.0);
        addRanks(merged, keywordHits, "keyword", 0.9);
        List<Ranked> ranked = merged.values().stream()
                .sorted(Comparator.comparingDouble(Ranked::score).reversed()
                        .thenComparingDouble(r -> r.match().distance()))
                .toList();

        // 可观测性：召回各层贡献。向量/关键词各自召回多少、多少是双路共同命中——
        // 用于判断某一层是否退化（例如更换向量模型后召回明显变差）。
        modelGateway.counter("retrieval.vector.candidates", vectorHits.size());
        modelGateway.counter("retrieval.keyword.candidates", keywordHits.size());
        for (Ranked r : ranked) {
            String method = r.methods().size() > 1 ? "hybrid"
                    : (r.methods().contains("vector") ? "vector" : "keyword");
            modelGateway.counter("retrieval.source." + method, 1);
        }

        // 精排（Rerank）：RRF 只看排名位次，不理解语义；精排模型对 query 与候选逐对打分。
        // 未启用/失败时 apply 原样返回，不影响检索可用性。
        List<VectorStore.Match> ordered = rerankService == null
                ? ranked.stream().map(Ranked::match).toList()
                : rerankService.apply(question, ranked.stream().map(Ranked::match).toList(), topK);
        Map<Long, Ranked> byId = new LinkedHashMap<>();
        for (Ranked r : ranked) byId.put(r.match().id(), r);
        return ordered.stream()
                .limit(Math.max(1, topK))
                .map(m -> {
                    Ranked r = byId.get(m.id());
                    if (r == null) return m.withRank(m.retrievalMethod(), m.retrievalScore());
                    return m.withRank(r.methods().size() > 1 ? "hybrid" : r.methods().iterator().next(), r.score());
                })
                .toList();
    }

    private static final class Ranked {
        private VectorStore.Match match;
        private double score;
        private final Set<String> methods = new LinkedHashSet<>();
        Ranked(VectorStore.Match match) { this.match = match; }
        VectorStore.Match match() { return match; }
        double score() { return score; }
        Set<String> methods() { return methods; }
    }

    private void addRanks(Map<Long, Ranked> merged, List<VectorStore.Match> hits,
                          String method, double weight) {
        for (int i = 0; i < hits.size(); i++) {
            VectorStore.Match hit = hits.get(i);
            Ranked ranked = merged.computeIfAbsent(hit.id(), id -> new Ranked(hit));
            if (hit.distance() < ranked.match.distance()) ranked.match = hit;
            ranked.score += weight / (60.0 + i + 1);
            ranked.methods.add(method);
        }
    }

    private List<String> lexicalTerms(String question, String project, String aspect) {
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        String normalized = normalize(question);
        if (normalized.length() >= 2 && normalized.length() <= 30) terms.add(normalized);
        if (project != null && !project.isBlank()) terms.add(project);
        if (aspect != null && !aspect.isBlank()) terms.add(aspect);
        for (String keyword : AiConfigCatalog.keywords(config, "retrieval.keywordTerms")) {
            if (normalized.contains(normalize(keyword))) terms.add(normalize(keyword));
        }
        // 没命中词典时保留最多三个中文二元词，兼顾口语和错别字召回，不让 SQL 条件无限膨胀。
        if (terms.size() <= 1 && normalized.length() >= 2) {
            for (int i = 0; i < normalized.length() - 1 && terms.size() < 4; i++) {
                terms.add(normalized.substring(i, i + 2));
            }
        }
        int maxTerms = Math.max(1, Math.min(config.getInt("retrieval.maxLexicalTerms", 8), 20));
        return terms.stream().limit(maxTerms).toList();
    }

    public boolean hasKnowledge() {
        return vectorStore.count() > 0;
    }

    /** 编辑内容后重新生成专用检索文本，并自动撤销安全直回，要求重新审核。 */
    public UpdateResult updateChunk(long id, String content) {
        try (EmbeddingIndexCoordinator.Lease ignored = indexCoordinator.knowledgeMutation()) {
        if (content == null || content.isBlank()) throw new IllegalArgumentException("知识内容不能为空");
        Map<String, Object> current = vectorStore.listById(id);
        if (current == null) throw new IllegalArgumentException("知识切片不存在或无权操作");
        ParsedFaq faq = parseFaq(content);
        String searchText = faq == null ? content : faqSearchText(faq.question(), faq.category());
        String knowledgeType = String.valueOf(current.getOrDefault("knowledge_type",
                faq == null ? "reference" : "faq"));
        DynamicPriceGovernance.Decision governance =
                DynamicPriceGovernance.inspect(content, knowledgeType);
        if (governance.governed()) {
            vectorStore.governDynamicPriceChunk(id, content, searchText, governance.reason());
            return new UpdateResult(true, governance.reason());
        }
        String strategy = faq == null ? "content-v1" : "faq-question-v1";
        float[] vector = modelGateway.embed(searchText);
        vectorStore.updateChunk(id, content, searchText, vector, strategy);
        return new UpdateResult(false, null);
        }
    }

    /**
     * 管理员决定单条切片是否参与客户检索。动态价格内容默认停用，只有明确确认风险后才允许发布。
     */
    public StatusResult setChunkStatus(long id, String status, boolean acknowledgeGovernanceRisk) {
        try (EmbeddingIndexCoordinator.Lease ignored = indexCoordinator.knowledgeMutation()) {
            if (!List.of("published", "disabled").contains(status)) {
                throw new IllegalArgumentException("切片状态只能是 published / disabled");
            }
            Map<String, Object> current = vectorStore.listById(id);
            if (current == null) throw new IllegalArgumentException("知识切片不存在或无权操作");
            String content = String.valueOf(current.get("content"));
            String knowledgeType = String.valueOf(current.getOrDefault("knowledge_type", "reference"));
            DynamicPriceGovernance.Decision governance =
                    DynamicPriceGovernance.inspect(content, knowledgeType);
            Object reasonValue = current.get("governance_reason");
            String existingReason = reasonValue instanceof String value && !value.isBlank() ? value : null;
            String reason = governance.governed() ? governance.reason() : existingReason;

            if ("disabled".equals(status)) {
                if (governance.governed() || existingReason != null) {
                    vectorStore.governDynamicPriceChunk(id, content, searchTextFor(content),
                            reason == null ? DynamicPriceGovernance.REASON : reason);
                    return new StatusResult("disabled", false, reason, false);
                }
                vectorStore.updateChunkStatus(id, "disabled");
                return new StatusResult("disabled", false, null,
                        Boolean.TRUE.equals(current.get("has_embedding")));
            }

            if (governance.governed()) {
                if (!acknowledgeGovernanceRisk) {
                    throw new IllegalArgumentException(
                            governance.reason() + "；如需启用，请先确认由知识库内容覆盖项目中心实时数据的风险");
                }
                String searchText = searchTextFor(content);
                String strategy = parseFaq(content) == null ? "content-v1" : "faq-question-v1";
                float[] vector = modelGateway.embed(searchText);
                vectorStore.publishGovernedChunk(id, content, searchText, vector, strategy,
                        governance.reason());
                return new StatusResult("published", true, governance.reason(), true);
            }

            boolean hasEmbedding = Boolean.TRUE.equals(current.get("has_embedding"));
            if (!"offer".equals(knowledgeType) && !hasEmbedding) {
                rebuildChunkVector(id, content);
                hasEmbedding = true;
            }
            vectorStore.updateChunkStatus(id, "published");
            return new StatusResult("published", false, null, hasEmbedding);
        }
    }

    public void updateChunkMetadata(long id, String project, String category,
                                    String knowledgeType, String riskLevel,
                                    boolean safeDirect, String status) {
        try (EmbeddingIndexCoordinator.Lease ignored = indexCoordinator.knowledgeMutation()) {
            Map<String, Object> current = vectorStore.listById(id);
            if (current == null) throw new IllegalArgumentException("知识切片不存在或无权操作");
            String content = String.valueOf(current.get("content"));
            DynamicPriceGovernance.Decision governance =
                    DynamicPriceGovernance.inspect(content, knowledgeType);
            if ("published".equals(status) && governance.governed()) {
                vectorStore.governDynamicPriceChunk(id, content, searchTextFor(content), governance.reason());
                throw new IllegalArgumentException(governance.reason() + "，该切片已自动保持停用");
            }
            if ("published".equals(status) && !"offer".equals(knowledgeType)
                    && (!"published".equals(String.valueOf(current.get("status")))
                    || !Boolean.TRUE.equals(current.get("has_embedding")))) {
                rebuildChunkVector(id, String.valueOf(current.get("content")));
                safeDirect = false;
            }
            vectorStore.updateChunkMetadata(id, project, category, knowledgeType, riskLevel,
                    safeDirect, status);
        }
    }

    /** 文档从停用恢复为发布前，为其中已发布且无向量的切片补齐当前模型向量。 */
    public int ensurePublishedChunkVectors(List<Map<String, Object>> chunks) {
        if (chunks == null || chunks.isEmpty()) return 0;
        int rebuilt = 0;
        try (EmbeddingIndexCoordinator.Lease ignored = indexCoordinator.knowledgeMutation()) {
            for (Map<String, Object> chunk : chunks) {
                if (!"published".equals(String.valueOf(chunk.get("status")))
                        || "offer".equals(String.valueOf(chunk.get("knowledge_type")))
                        || Boolean.TRUE.equals(chunk.get("has_embedding"))) continue;
                String content = String.valueOf(chunk.get("content"));
                DynamicPriceGovernance.Decision governance = DynamicPriceGovernance.inspect(
                        content, String.valueOf(chunk.get("knowledge_type")));
                if (governance.governed()) {
                    vectorStore.governDynamicPriceChunk(((Number) chunk.get("id")).longValue(),
                            content, searchTextFor(content), governance.reason());
                    continue;
                }
                rebuildChunkVector(((Number) chunk.get("id")).longValue(),
                        content);
                rebuilt++;
            }
        }
        return rebuilt;
    }

    private void rebuildChunkVector(long id, String content) {
        ParsedFaq faq = parseFaq(content);
        String searchText = faq == null ? content : faqSearchText(faq.question(), faq.category());
        String strategy = faq == null ? "content-v1" : "faq-question-v1";
        vectorStore.updateChunk(id, content, searchText, modelGateway.embed(searchText), strategy);
    }

    public void addChunk(String docName, String content) {
        try (EmbeddingIndexCoordinator.Lease ignored = indexCoordinator.knowledgeMutation()) {
        ParsedFaq faq = parseFaq(content);
        String category = faq == null ? null : faq.category();
        String project = category != null && projects().contains(category) ? category : inferSingleProject(content);
        String type = faq == null ? "reference" : "faq";
        String searchText = faq == null ? content : faqSearchText(faq.question(), category);
        DynamicPriceGovernance.Decision governance = DynamicPriceGovernance.inspect(content, type);
        if (governance.governed()) {
            vectorStore.addGovernedChunk(docName, content, searchText, project, category,
                    type, governance.reason());
            return;
        }
        float[] vector = modelGateway.embed(searchText);
        vectorStore.addChunk(docName, content, searchText, vector, project, category, type);
        }
    }

    private String searchTextFor(String content) {
        ParsedFaq faq = parseFaq(content);
        return faq == null ? content : faqSearchText(faq.question(), faq.category());
    }

    /** 把旧的“问题+答案”FAQ 向量重建为“项目+问题+同义表达”向量。 */
    public int reindexAll() {
        try (EmbeddingIndexCoordinator.Lease ignored = indexCoordinator.exclusiveSwitch()) {
        List<VectorStore.ReindexInput> rebuilt = prepareReindex(
                modelGateway::embedAll, (processed, total) -> { });
        vectorStore.replaceSearchVectors(rebuilt);
        return rebuilt.size();
        }
    }

    /**
     * 使用指定候选模型准备全部新向量，但不修改数据库。
     * 只有全部批次成功、维度一致后，调用方才可以进入原子提交阶段。
     */
    public List<VectorStore.ReindexInput> prepareReindex(
            Function<List<String>, List<float[]>> embedder,
            BiConsumer<Integer, Integer> progress) {
        List<Map<String, Object>> rows = vectorStore.listAllForReindex();
        List<VectorStore.ReindexInput> rebuilt = new ArrayList<>(rows.size());
        progress.accept(0, rows.size());
        for (int i = 0; i < rows.size(); i += EMBED_BATCH_SIZE) {
            List<Map<String, Object>> batch = rows.subList(i, Math.min(i + EMBED_BATCH_SIZE, rows.size()));
            List<String> texts = batch.stream().map(row -> {
                String content = String.valueOf(row.get("content"));
                ParsedFaq faq = parseFaq(content);
                return faq == null ? content : faqSearchText(faq.question(), faq.category());
            }).toList();
            List<float[]> vectors = embedder.apply(texts);
            if (vectors == null || vectors.size() != batch.size()) {
                throw new IllegalArgumentException("向量模型返回数量与知识切片数量不一致");
            }
            for (int j = 0; j < batch.size(); j++) {
                Map<String, Object> row = batch.get(j);
                boolean faq = parseFaq(String.valueOf(row.get("content"))) != null;
                float[] vector = vectors.get(j);
                if (vector == null || vector.length != VectorStore.EMBEDDING_DIMENSION) {
                    throw new IllegalArgumentException("候选向量模型输出 "
                            + (vector == null ? 0 : vector.length) + " 维，当前知识库要求 "
                            + VectorStore.EMBEDDING_DIMENSION + " 维");
                }
                String content = String.valueOf(row.get("content"));
                rebuilt.add(new VectorStore.ReindexInput(((Number) row.get("id")).longValue(),
                        content, texts.get(j), vector, faq ? "faq-question-v1" : "content-v1"));
            }
            progress.accept(Math.min(i + EMBED_BATCH_SIZE, rows.size()), rows.size());
        }
        return List.copyOf(rebuilt);
    }

    /** 必须在 knowledge_chunk 写锁内调用，防止重建期间编辑/导入的内容被旧快照覆盖。 */
    public boolean reindexSnapshotStillCurrent(List<VectorStore.ReindexInput> prepared) {
        List<Map<String, Object>> current = vectorStore.listAllForReindex();
        if (current.size() != prepared.size()) return false;
        for (int i = 0; i < current.size(); i++) {
            Map<String, Object> row = current.get(i);
            VectorStore.ReindexInput expected = prepared.get(i);
            if (((Number) row.get("id")).longValue() != expected.id()
                    || !String.valueOf(row.get("content")).equals(expected.sourceContent())) {
                return false;
            }
        }
        return true;
    }

    private record ParsedFaq(String question, String category) {}

    private ParsedFaq parseFaq(String content) {
        if (content == null || !content.stripLeading().startsWith("问：")) return null;
        String question = null;
        String category = "";
        for (String line : content.split("\\R")) {
            if (line.startsWith("问：")) question = line.substring(2).trim();
            if (line.startsWith("分类：")) category = line.substring(3).trim();
        }
        return question == null || question.isBlank() ? null : new ParsedFaq(question, category);
    }

    private String faqSearchText(String question, String category) {
        LinkedHashSet<String> aliases = new LinkedHashSet<>();
        aliases.add(question);
        addAlias(aliases, question, "多少钱", "价格是多少");
        addAlias(aliases, question, "多少钱", "怎么收费");
        addAlias(aliases, question, "痛不痛", "疼不疼");
        addAlias(aliases, question, "几次", "多少次");
        addAlias(aliases, question, "能管多久", "维持多久");
        addAlias(aliases, question, "能做吗", "适合做吗");
        StringBuilder text = new StringBuilder();
        if (category != null && !category.isBlank()) text.append("项目分类：").append(category).append('\n');
        text.append("标准问题：").append(question);
        if (aliases.size() > 1) text.append("\n同义表达：").append(String.join("；", aliases));
        return text.toString();
    }

    private void addAlias(Set<String> aliases, String source, String from, String to) {
        if (source.contains(from)) aliases.add(source.replace(from, to));
    }

    private String detectDocType(String docName) {
        if ("概念-医美营销模式.md".equals(docName)) return "internal";
        if (docName.toLowerCase(Locale.ROOT).contains("faq")) return "faq";
        if ("price.xlsx".equalsIgnoreCase(docName)) return "offer";
        return "reference";
    }

    private String inferSingleProject(String text) {
        if (text == null || text.isBlank()) return null;
        List<String> found = projects().stream().filter(text::contains)
                .sorted((left, right) -> Integer.compare(right.length(), left.length()))
                .toList();
        if (found.isEmpty()) return null;
        int longest = found.get(0).length();
        List<String> strongest = found.stream().filter(name -> name.length() == longest).toList();
        return strongest.size() == 1 ? strongest.get(0) : null;
    }


    private List<String> projects() {
        List<String> names = List.copyOf(projectCatalog.projectRules().keySet());
        return names.isEmpty()
                ? List.copyOf(AiConfigCatalog.stringListMap(config, "intent.projects").keySet())
                : names;
    }

    private String normalize(String text) {
        return text == null ? "" : text.replaceAll("[\\p{P}\\p{S}\\s]", "")
                .toLowerCase(Locale.ROOT);
    }

    private String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
