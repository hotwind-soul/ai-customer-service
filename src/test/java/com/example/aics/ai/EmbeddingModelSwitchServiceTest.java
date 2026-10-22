package com.example.aics.ai;

import com.example.aics.config.ConfigService;
import com.example.aics.config.KeyConfigService;
import com.example.aics.config.SecretCryptoService;
import com.example.aics.knowledge.KnowledgeService;
import com.example.aics.knowledge.VectorStore;
import com.example.aics.knowledge.EmbeddingIndexCoordinator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.function.Consumer;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EmbeddingModelSwitchServiceTest {

    @Mock private ModelGateway modelGateway;
    @Mock private KnowledgeService knowledgeService;
    @Mock private VectorStore vectorStore;
    @Mock private ConfigService configService;
    @Mock private KeyConfigService keyConfigService;
    @Mock private SecretCryptoService crypto;
    @Mock private JdbcTemplate jdbc;
    @Mock private TransactionTemplate transactions;
    @Mock private EmbeddingIndexCoordinator indexCoordinator;
    private EmbeddingModelSwitchService service;

    @BeforeEach
    void setUp() {
        service = new EmbeddingModelSwitchService(modelGateway, knowledgeService, vectorStore,
                configService, keyConfigService, crypto, jdbc, transactions, indexCoordinator);
        lenient().when(indexCoordinator.exclusiveSwitch()).thenReturn(() -> { });
        lenient().doAnswer(invocation -> {
            Consumer<TransactionStatus> callback = invocation.getArgument(0);
            callback.accept(mock(TransactionStatus.class));
            return null;
        }).when(transactions).executeWithoutResult(any());
    }

    @Test
    void 候选模型全量重建成功后才提交配置和新向量() {
        float[] vector = new float[VectorStore.EMBEDDING_DIMENSION];
        ModelGateway.EmbeddingBatchClient candidate = texts -> List.of(vector);
        when(modelGateway.candidateEmbeddingClient(
                "https://example.com/v1", "embedding-1024", "new-key"))
                .thenReturn(candidate);
        List<VectorStore.ReindexInput> prepared = List.of(
                new VectorStore.ReindexInput(1L, "知识内容", "知识内容", vector, "content-v1"));
        when(knowledgeService.prepareReindex(any(), any())).thenReturn(prepared);
        when(knowledgeService.reindexSnapshotStillCurrent(prepared)).thenReturn(true);
        when(crypto.encrypt("new-key")).thenReturn("enc-key");

        service.runSwitch(7L, "https://example.com/v1", "embedding-1024", "new-key");

        var order = inOrder(vectorStore, knowledgeService, jdbc, configService);
        order.verify(vectorStore).lockKnowledgeChunksForEmbeddingSwitch();
        order.verify(knowledgeService).reindexSnapshotStillCurrent(prepared);
        order.verify(vectorStore).replaceSearchVectors(prepared);
        verify(jdbc).update(contains("INSERT INTO system_config"),
                eq("model.embedding.baseUrl"), eq("https://example.com/v1"));
        verify(jdbc).update(contains("INSERT INTO system_config"),
                eq("model.embedding.name"), eq("embedding-1024"));
        verify(jdbc).update(contains("INSERT INTO system_config"),
                eq("model.embedding.apiKey"), eq("enc-key"));
        verify(configService).reload();
        verify(jdbc).update(contains("status='done'"), eq(7L));
    }

    @Test
    void 候选模型维度不兼容时保留原配置和原向量() {
        when(modelGateway.candidateEmbeddingClient(
                "https://example.com/v1", "embedding-1536", "new-key"))
                .thenReturn(texts -> List.of(new float[1536]));

        service.runSwitch(8L, "https://example.com/v1", "embedding-1536", "new-key");

        verify(knowledgeService, never()).prepareReindex(any(), any());
        verify(vectorStore, never()).replaceSearchVectors(anyList());
        verify(jdbc, never()).update(contains("INSERT INTO system_config"), any(), any());
        verify(jdbc).update(contains("status='failed'"),
                contains("输出 1536 维"), eq(8L));
        verify(configService).reload();
    }

    @Test
    void 重建期间知识变化会取消原子切换() {
        float[] vector = new float[VectorStore.EMBEDDING_DIMENSION];
        when(modelGateway.candidateEmbeddingClient(anyString(), anyString(), anyString()))
                .thenReturn(texts -> List.of(vector));
        List<VectorStore.ReindexInput> prepared = List.of(
                new VectorStore.ReindexInput(1L, "旧内容", "旧内容", vector, "content-v1"));
        when(knowledgeService.prepareReindex(any(), any())).thenReturn(prepared);
        when(knowledgeService.reindexSnapshotStillCurrent(prepared)).thenReturn(false);

        service.runSwitch(9L, "https://example.com/v1", "embedding-1024", "new-key");

        verify(vectorStore).lockKnowledgeChunksForEmbeddingSwitch();
        verify(vectorStore, never()).replaceSearchVectors(anyList());
        verify(jdbc, never()).update(contains("INSERT INTO system_config"), any(), any());
        verify(jdbc).update(contains("status='failed'"),
                contains("知识库发生变化"), eq(9L));
    }
}
