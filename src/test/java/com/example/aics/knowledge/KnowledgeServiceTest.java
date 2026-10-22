package com.example.aics.knowledge;

import com.example.aics.ai.ModelGateway;
import com.example.aics.config.ConfigService;
import com.example.aics.project.ProjectCatalogService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyList;

@ExtendWith(MockitoExtension.class)
class KnowledgeServiceTest {

    @Mock private DocumentTextExtractor extractor;
    @Mock private TextChunker chunker;
    @Mock private VectorStore vectorStore;
    @Mock private ModelGateway modelGateway;
    @Mock private ConfigService config;
    @Mock private EmbeddingIndexCoordinator indexCoordinator;
    @Mock private ProjectCatalogService projectCatalog;
    @Mock private MultipartFile file;

    @Test
    void 导入价格表只写项目中心且不调用向量模型() throws Exception {
        DocumentTextExtractor.PriceRow row = new DocumentTextExtractor.PriceRow(
                "淡斑", "- project_key：淡斑\n- activity_price：228", "淡斑 价格");
        when(file.getOriginalFilename()).thenReturn("price.xlsx");
        when(indexCoordinator.knowledgeMutation()).thenReturn(() -> { });
        when(extractor.tryExtractPriceRows(file)).thenReturn(List.of(row));
        when(projectCatalog.importPriceRows("price.xlsx", List.of(row))).thenReturn(1);
        KnowledgeService service = new KnowledgeService(extractor, chunker, vectorStore,
                modelGateway, config, indexCoordinator, projectCatalog);

        int imported = service.importDocument(file);

        assertThat(imported).isEqualTo(1);
        verify(projectCatalog).importPriceRows("price.xlsx", List.of(row));
        verify(modelGateway, never()).embedAll(anyList());
        verify(vectorStore, never()).replaceDocument(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), anyList());
    }

    @Test
    void 文档同时出现父子项目名称时优先归入更具体的子项目() {
        float[] vector = new float[]{0.1f, 0.2f};
        when(indexCoordinator.knowledgeMutation()).thenReturn(() -> { });
        when(projectCatalog.projectRules()).thenReturn(Map.of(
                "抗衰", List.of("抗衰"),
                "抗衰全脸", List.of("抗衰全脸", "全脸抗衰")));
        when(modelGateway.embed("抗衰全脸的护理说明")).thenReturn(vector);
        KnowledgeService service = new KnowledgeService(extractor, chunker, vectorStore,
                modelGateway, config, indexCoordinator, projectCatalog);

        service.addChunk("护理说明.md", "抗衰全脸的护理说明");

        verify(vectorStore).addChunk(eq("护理说明.md"), eq("抗衰全脸的护理说明"),
                eq("抗衰全脸的护理说明"), eq(vector), eq("抗衰全脸"), isNull(), eq("reference"));
    }

    @Test
    void 停用切片重新发布时自动生成当前向量并撤销安全直回() {
        float[] vector = new float[]{0.1f, 0.2f};
        when(indexCoordinator.knowledgeMutation()).thenReturn(() -> { });
        when(vectorStore.listById(8L)).thenReturn(Map.of(
                "id", 8L, "content", "项目护理说明", "status", "disabled",
                "knowledge_type", "reference", "has_embedding", false));
        when(modelGateway.embed("项目护理说明")).thenReturn(vector);
        KnowledgeService service = new KnowledgeService(extractor, chunker, vectorStore,
                modelGateway, config, indexCoordinator, projectCatalog);

        service.updateChunkMetadata(8L, "抗衰", "护理", "reference", "medium", true, "published");

        verify(vectorStore).updateChunk(8L, "项目护理说明", "项目护理说明", vector, "content-v1");
        verify(vectorStore).updateChunkMetadata(8L, "抗衰", "护理", "reference", "medium", false, "published");
    }

    @Test
    void 导入价格FAQ时不调用向量模型并自动停用留档() throws Exception {
        when(file.getOriginalFilename()).thenReturn("新FAQ.xlsx");
        when(indexCoordinator.knowledgeMutation()).thenReturn(() -> { });
        when(extractor.tryExtractPriceRows(file)).thenReturn(null);
        when(extractor.tryExtractFaqRows(file)).thenReturn(List.<String[]>of(
                new String[]{"抗衰多少钱？", "活动价298元，订金8元。", "抗衰"}));
        when(projectCatalog.projectRules()).thenReturn(Map.of("抗衰", List.of("抗衰")));
        KnowledgeService service = new KnowledgeService(extractor, chunker, vectorStore,
                modelGateway, config, indexCoordinator, projectCatalog);

        int imported = service.importDocument(file);

        assertThat(imported).isEqualTo(1);
        verify(modelGateway, never()).embedAll(anyList());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<VectorStore.ChunkInput>> chunks = ArgumentCaptor.forClass(List.class);
        verify(vectorStore).replaceDocument(eq("新FAQ.xlsx"), eq("faq"), chunks.capture());
        VectorStore.ChunkInput governed = chunks.getValue().get(0);
        assertThat(governed.status()).isEqualTo("disabled");
        assertThat(governed.vector()).isNull();
        assertThat(governed.embeddingStrategy()).isEqualTo(DynamicPriceGovernance.STRATEGY);
        assertThat(governed.governanceReason()).contains("项目中心");
    }

    @Test
    void 在线编辑为价格FAQ时自动停用且不向模型发送内容() {
        when(indexCoordinator.knowledgeMutation()).thenReturn(() -> { });
        when(vectorStore.listById(9L)).thenReturn(Map.of(
                "id", 9L, "knowledge_type", "faq", "status", "published",
                "has_embedding", true));
        KnowledgeService service = new KnowledgeService(extractor, chunker, vectorStore,
                modelGateway, config, indexCoordinator, projectCatalog);
        String content = "问：淡斑多少钱？\n答：活动价198元。\n分类：淡斑";

        KnowledgeService.UpdateResult result = service.updateChunk(9L, content);

        assertThat(result.governed()).isTrue();
        verify(modelGateway, never()).embed(org.mockito.ArgumentMatchers.anyString());
        verify(vectorStore).governDynamicPriceChunk(eq(9L), eq(content),
                org.mockito.ArgumentMatchers.contains("标准问题：淡斑多少钱？"),
                eq(DynamicPriceGovernance.REASON));
    }

    @Test
    void 管理员确认风险后可以人工启用动态价格切片() {
        float[] vector = new float[]{0.1f, 0.2f};
        String content = "问：淡斑多少钱？\n答：活动价198元。\n分类：淡斑";
        when(indexCoordinator.knowledgeMutation()).thenReturn(() -> { });
        when(vectorStore.listById(9L)).thenReturn(Map.of(
                "id", 9L, "content", content, "knowledge_type", "faq", "status", "disabled",
                "has_embedding", false, "governance_reason", DynamicPriceGovernance.REASON,
                "governance_override", false));
        when(modelGateway.embed(anyString())).thenReturn(vector);
        KnowledgeService service = new KnowledgeService(extractor, chunker, vectorStore,
                modelGateway, config, indexCoordinator, projectCatalog);

        KnowledgeService.StatusResult result = service.setChunkStatus(9L, "published", true);

        assertThat(result.status()).isEqualTo("published");
        assertThat(result.governanceOverride()).isTrue();
        assertThat(result.hasEmbedding()).isTrue();
        verify(vectorStore).publishGovernedChunk(eq(9L), eq(content),
                org.mockito.ArgumentMatchers.contains("标准问题：淡斑多少钱？"), eq(vector),
                eq("faq-question-v1"), eq(DynamicPriceGovernance.REASON));
    }

    @Test
    void 未确认风险时不能人工启用动态价格切片() {
        String content = "问：淡斑多少钱？\n答：活动价198元。\n分类：淡斑";
        when(indexCoordinator.knowledgeMutation()).thenReturn(() -> { });
        when(vectorStore.listById(9L)).thenReturn(Map.of(
                "id", 9L, "content", content, "knowledge_type", "faq", "status", "disabled",
                "has_embedding", false, "governance_reason", DynamicPriceGovernance.REASON,
                "governance_override", false));
        KnowledgeService service = new KnowledgeService(extractor, chunker, vectorStore,
                modelGateway, config, indexCoordinator, projectCatalog);

        assertThatThrownBy(() -> service.setChunkStatus(9L, "published", false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("确认");
        verify(modelGateway, never()).embed(anyString());
        verify(vectorStore, never()).publishGovernedChunk(
                org.mockito.ArgumentMatchers.anyLong(), anyString(), anyString(),
                org.mockito.ArgumentMatchers.any(float[].class), anyString(), anyString());
    }

    @Test
    void 人工启用的动态价格切片仍可再次停用并清除向量() {
        String content = "问：淡斑多少钱？\n答：活动价198元。\n分类：淡斑";
        when(indexCoordinator.knowledgeMutation()).thenReturn(() -> { });
        when(vectorStore.listById(9L)).thenReturn(Map.of(
                "id", 9L, "content", content, "knowledge_type", "faq", "status", "published",
                "has_embedding", true, "governance_reason", DynamicPriceGovernance.REASON,
                "governance_override", true));
        KnowledgeService service = new KnowledgeService(extractor, chunker, vectorStore,
                modelGateway, config, indexCoordinator, projectCatalog);

        KnowledgeService.StatusResult result = service.setChunkStatus(9L, "disabled", false);

        assertThat(result.status()).isEqualTo("disabled");
        assertThat(result.governanceOverride()).isFalse();
        assertThat(result.hasEmbedding()).isFalse();
        verify(vectorStore).governDynamicPriceChunk(eq(9L), eq(content),
                org.mockito.ArgumentMatchers.contains("标准问题：淡斑多少钱？"),
                eq(DynamicPriceGovernance.REASON));
    }
}
