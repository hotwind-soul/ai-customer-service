package com.example.aics.knowledge;

import com.example.aics.ai.ModelGateway;
import com.example.aics.config.AdminDataScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class KnowledgeReindexServiceTest {

    @Mock private ModelGateway modelGateway;
    @Mock private KnowledgeService knowledgeService;
    @Mock private VectorStore vectorStore;
    @Mock private JdbcTemplate jdbc;
    @Mock private TransactionTemplate transactions;
    @Mock private EmbeddingIndexCoordinator indexCoordinator;
    private KnowledgeReindexService service;

    @BeforeEach
    void setUp() {
        service = new KnowledgeReindexService(modelGateway, knowledgeService, vectorStore,
                jdbc, transactions, indexCoordinator);
        lenient().when(indexCoordinator.exclusiveSwitch()).thenReturn(() -> { });
        lenient().doAnswer(invocation -> {
            Consumer<TransactionStatus> callback = invocation.getArgument(0);
            callback.accept(mock(TransactionStatus.class));
            return null;
        }).when(transactions).executeWithoutResult(any());
    }

    @Test
    void 全部生成成功后原子替换旧向量并完成任务() {
        float[] vector = new float[VectorStore.EMBEDDING_DIMENSION];
        List<VectorStore.ReindexInput> prepared = List.of(
                new VectorStore.ReindexInput(1L, "知识内容", "知识内容", vector, "content-v1"));
        when(knowledgeService.prepareReindex(any(), any())).thenAnswer(invocation -> {
            BiConsumer<Integer, Integer> progress = invocation.getArgument(1);
            progress.accept(0, 1);
            progress.accept(1, 1);
            return prepared;
        });
        when(knowledgeService.reindexSnapshotStillCurrent(prepared)).thenReturn(true);

        service.runReindex(7L, new AdminDataScope.AccessScope(false, List.of(), List.of()));

        var order = inOrder(vectorStore, knowledgeService, jdbc);
        order.verify(vectorStore).lockKnowledgeChunksForEmbeddingSwitch();
        order.verify(knowledgeService).reindexSnapshotStillCurrent(prepared);
        order.verify(vectorStore).replaceSearchVectors(prepared);
        verify(jdbc).update(contains("status='done'"), eq(7L));
        verify(jdbc, never()).update(contains("status='failed'"), any(), eq(7L));
    }

    @Test
    void 提交前知识发生变化时保留旧索引并标记失败() {
        float[] vector = new float[VectorStore.EMBEDDING_DIMENSION];
        List<VectorStore.ReindexInput> prepared = List.of(
                new VectorStore.ReindexInput(2L, "旧知识", "旧知识", vector, "content-v1"));
        when(knowledgeService.prepareReindex(any(), any())).thenReturn(prepared);
        when(knowledgeService.reindexSnapshotStillCurrent(prepared)).thenReturn(false);

        service.runReindex(8L, new AdminDataScope.AccessScope(false, List.of(), List.of()));

        verify(vectorStore, never()).replaceSearchVectors(anyList());
        verify(jdbc).update(contains("status='failed'"),
                contains("知识库发生变化"), eq(8L));
    }
}
