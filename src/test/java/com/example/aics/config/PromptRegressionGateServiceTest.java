package com.example.aics.config;

import com.example.aics.ai.ModelGateway;
import com.example.aics.audit.AuditService;
import com.example.aics.chat.ChatEngine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PromptRegressionGateServiceTest {
    private PromptReleaseService releases;
    private PromptRegressionCaseCatalog cases;
    private PromptRegressionGateRepository repository;
    private ChatEngine chatEngine;
    private ModelGateway modelGateway;
    private AuditService audit;
    private PromptRegressionGateService service;
    private HarnessEnvironmentService environment;

    @BeforeEach
    void setUp() {
        releases = mock(PromptReleaseService.class);
        cases = mock(PromptRegressionCaseCatalog.class);
        repository = mock(PromptRegressionGateRepository.class);
        chatEngine = mock(ChatEngine.class);
        modelGateway = mock(ModelGateway.class);
        audit = mock(AuditService.class);
        environment = mock(HarnessEnvironmentService.class);
        when(environment.fingerprint()).thenReturn("env-v1");
        service = new PromptRegressionGateService(
                releases, cases, repository, chatEngine, modelGateway, audit, environment);
    }

    @AfterEach
    void tearDown() {
        service.shutdown();
    }

    @Test
    void 全部断言通过时生成可发布结果且直回用例不调用模型() {
        PromptReleaseService.GateCandidate candidate = new PromptReleaseService.GateCandidate(
                3, "draft-hash", Map.of());
        PromptRegressionCaseCatalog.CaseDefinition item = new PromptRegressionCaseCatalog.CaseDefinition(
                8, "结构化价格", "抗衰多少钱", "", "FACT", "298元", "",
                "", "", false, LocalDateTime.of(2026, 9, 4, 12, 0));
        PromptRegressionCaseCatalog.Snapshot snapshot =
                new PromptRegressionCaseCatalog.Snapshot("cases-hash", List.of(item));
        ChatEngine.Prepared prepared = new ChatEngine.Prepared(
                "debug", false, null, List.of(), List.of(), "活动价298元。",
                List.of(), List.of(), null, "FACT", null);
        when(chatEngine.prepareDebug(eq("抗衰多少钱"), anyString(), eq("prompt-gate"),
                isNull(), isNull(), any(ChatEngine.DebugOptions.class))).thenReturn(prepared);
        when(chatEngine.finalizeAnswer(any(ChatEngine.Prepared.class), anyString()))
                .thenAnswer(invocation -> invocation.getArgument(1));

        service.run(11, "editor", candidate, snapshot,
                new AdminDataScope.AccessScope(false, List.of(), List.of()), "env-v1");

        verify(repository).markRunning(11);
        verify(repository).finish(11, true, 1, List.of());
        verifyNoInteractions(modelGateway);
    }

    @Test
    void 回答断言失败时门禁结果为失败并保存用例原因() {
        PromptReleaseService.GateCandidate candidate = new PromptReleaseService.GateCandidate(
                3, "draft-hash", Map.of());
        PromptRegressionCaseCatalog.CaseDefinition item = new PromptRegressionCaseCatalog.CaseDefinition(
                9, "价格要点", "抗衰多少钱", "", "FACT", "298元；8元", "",
                "", "", false, LocalDateTime.of(2026, 9, 4, 12, 0));
        PromptRegressionCaseCatalog.Snapshot snapshot =
                new PromptRegressionCaseCatalog.Snapshot("cases-hash", List.of(item));
        ChatEngine.Prepared prepared = new ChatEngine.Prepared(
                "debug", false, null, List.of(), List.of(), "活动价298元。",
                List.of(), List.of(), null, "FACT", null);
        when(chatEngine.prepareDebug(anyString(), anyString(), anyString(),
                isNull(), isNull(), any(ChatEngine.DebugOptions.class))).thenReturn(prepared);
        when(chatEngine.finalizeAnswer(any(ChatEngine.Prepared.class), anyString()))
                .thenAnswer(invocation -> invocation.getArgument(1));

        service.run(12, "editor", candidate, snapshot,
                new AdminDataScope.AccessScope(false, List.of(), List.of()), "env-v1");

        verify(repository).finish(eq(12L), eq(false), eq(0), argThat(failures ->
                failures.size() == 1 && String.valueOf(failures.get(0).get("reasons")).contains("8元")));
    }

    @Test
    void 检查过程中环境变化时不给通过凭证() {
        when(environment.fingerprint()).thenReturn("env-v1", "env-v2");
        service.run(13, "editor", new PromptReleaseService.GateCandidate(3, "draft", Map.of()),
                new PromptRegressionCaseCatalog.Snapshot("cases", List.of()),
                new AdminDataScope.AccessScope(false, List.of(), List.of()), "env-v1");
        verify(repository).fail(eq(13L), contains("发生变化"));
        verify(repository, never()).finish(anyLong(), anyBoolean(), anyInt(), anyList());
    }

    @Test
    void 排队期间环境变化时不再执行旧任务() {
        when(environment.fingerprint()).thenReturn("env-v2");
        service.run(14, "editor", new PromptReleaseService.GateCandidate(3, "draft", Map.of()),
                new PromptRegressionCaseCatalog.Snapshot("cases", List.of()),
                new AdminDataScope.AccessScope(false, List.of(), List.of()), "env-v1");
        verify(repository).fail(eq(14L), contains("发生变化"));
        verifyNoInteractions(chatEngine, modelGateway);
    }
}
