package com.example.aics.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PromptReleaseServiceTest {
    private PromptReleaseStore store;
    private ConfigService config;
    private PromptRegressionGateRepository gateRepository;
    private PromptRegressionCaseCatalog regressionCases;
    private PromptReleaseService service;
    private HarnessEnvironmentService environment;

    @BeforeEach
    void setUp() {
        store = mock(PromptReleaseStore.class);
        config = mock(ConfigService.class);
        gateRepository = mock(PromptRegressionGateRepository.class);
        regressionCases = mock(PromptRegressionCaseCatalog.class);
        environment = mock(HarnessEnvironmentService.class);
        when(environment.fingerprint()).thenReturn("env-v1");
        service = new PromptReleaseService(store, config, gateRepository, regressionCases, environment);
        when(config.get(anyString(), anyString())).thenAnswer(invocation -> invocation.getArgument(1));
        when(regressionCases.snapshot()).thenReturn(new PromptRegressionCaseCatalog.Snapshot(
                "cases-v1", List.of(regressionCase())));
        when(gateRepository.hasPassingProof(anyString(), anyLong(), anyString(), anyString(), anyString()))
                .thenReturn(true);
    }

    @Test
    void 首次打开工作区建立基线但不改变Prompt正文() {
        when(store.lockCurrentReleaseId()).thenReturn(null);
        when(store.insertRelease(eq("BASELINE"), isNull(), anyMap(), eq(List.of()), eq("editor")))
                .thenAnswer(invocation -> release(1, "BASELINE", null,
                        invocation.getArgument(2), List.of(), "editor"));

        Map<String, Object> workspace = service.workspace("editor", false);

        @SuppressWarnings("unchecked")
        Map<String, Object> current = (Map<String, Object>) workspace.get("currentRelease");
        assertThat(current.get("version")).isEqualTo("prompt-v0001");
        assertThat(workspace.get("draft")).isNull();
        verify(store).setCurrentReleaseId(1);
        verify(config).set("prompt.releaseVersion", "prompt-v0001");
        verify(config, never()).setAll(anyMap());
    }

    @Test
    void 发布草稿原子写入变更并生成新版本() {
        Map<String, String> baseline = defaults();
        Map<String, String> target = new LinkedHashMap<>(baseline);
        target.put("prompt.tone", "更简洁");
        PromptReleaseStore.Release current = release(1, "BASELINE", null, baseline, List.of(), "admin");
        PromptReleaseStore.Draft draft = new PromptReleaseStore.Draft(
                "editor", 1, target, LocalDateTime.now());
        PromptReleaseStore.Release published = release(
                2, "PUBLISH", null, target, List.of("prompt.tone"), "editor");
        when(store.lockCurrentReleaseId()).thenReturn(1L);
        when(store.findRelease(1)).thenReturn(current);
        when(store.findDraft("editor")).thenReturn(draft);
        when(store.insertRelease(eq("PUBLISH"), isNull(), anyMap(),
                eq(List.of("prompt.tone")), eq("editor"))).thenReturn(published);

        Map<String, Object> result = service.publishDraft("editor", false);

        assertThat(result.get("result")).isEqualTo("Prompt 新版本已发布并立即生效");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> values = ArgumentCaptor.forClass(Map.class);
        verify(config).setAll(values.capture());
        assertThat(values.getValue())
                .containsEntry("prompt.tone", "更简洁")
                .containsEntry("prompt.releaseVersion", "prompt-v0002")
                .hasSize(2);
        verify(store).setCurrentReleaseId(2);
        verify(store).deleteDraft("editor");
        verify(gateRepository).hasPassingProof(eq("editor"), eq(1L), anyString(), eq("cases-v1"), eq("env-v1"));
    }

    @Test
    void 基于旧版本的草稿拒绝发布() {
        Map<String, String> baseline = defaults();
        when(store.lockCurrentReleaseId()).thenReturn(2L);
        when(store.findRelease(2)).thenReturn(release(
                2, "PUBLISH", null, baseline, List.of(), "other"));
        when(store.findDraft("editor")).thenReturn(new PromptReleaseStore.Draft(
                "editor", 1, baseline, LocalDateTime.now()));

        assertThatThrownBy(() -> service.publishDraft("editor", false))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode().value())
                        .isEqualTo(409));
        verify(config, never()).setAll(anyMap());
    }

    @Test
    void 未通过与当前草稿绑定的回归门禁时拒绝发布() {
        Map<String, String> baseline = defaults();
        Map<String, String> target = new LinkedHashMap<>(baseline);
        target.put("prompt.tone", "更简洁");
        when(store.lockCurrentReleaseId()).thenReturn(1L);
        when(store.findRelease(1)).thenReturn(release(
                1, "BASELINE", null, baseline, List.of(), "admin"));
        when(store.findDraft("editor")).thenReturn(new PromptReleaseStore.Draft(
                "editor", 1, target, LocalDateTime.now()));
        when(gateRepository.hasPassingProof(eq("editor"), eq(1L), anyString(), eq("cases-v1"), eq("env-v1")))
                .thenReturn(false);

        assertThatThrownBy(() -> service.publishDraft("editor", false))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("尚未通过发布检查");
        verify(config, never()).setAll(anyMap());
        verify(store, never()).insertRelease(eq("PUBLISH"), any(), anyMap(), anyList(), anyString());
    }

    @Test
    void 普通管理员不能通过回滚改变高级Prompt() {
        Map<String, String> currentValues = defaults();
        Map<String, String> oldValues = new LinkedHashMap<>(currentValues);
        oldValues.put("prompt.ragRules", "旧高级规则");
        when(store.lockCurrentReleaseId()).thenReturn(2L);
        when(store.findRelease(2)).thenReturn(release(
                2, "PUBLISH", null, currentValues, List.of(), "root"));
        when(store.findRelease(1)).thenReturn(release(
                1, "BASELINE", null, oldValues, List.of(), "root"));

        assertThatThrownBy(() -> service.rollback(1, "editor", false))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode().value())
                        .isEqualTo(403));
        verify(config, never()).setAll(anyMap());
    }

    private Map<String, String> defaults() {
        Map<String, String> result = new LinkedHashMap<>();
        for (AiConfigCatalog.Setting setting : AiConfigCatalog.definitions()) {
            if (AiConfigCatalog.isVersionedPromptKey(setting.key())) {
                result.put(setting.key(), setting.defaultValue());
            }
        }
        return result;
    }

    private PromptReleaseStore.Release release(long id, String action, Long source,
                                                Map<String, String> snapshot,
                                                List<String> changedKeys, String actor) {
        return new PromptReleaseStore.Release(
                id, action, source, Map.copyOf(snapshot), List.copyOf(changedKeys),
                actor, LocalDateTime.of(2026, 9, 4, 12, 0));
    }

    private PromptRegressionCaseCatalog.CaseDefinition regressionCase() {
        return new PromptRegressionCaseCatalog.CaseDefinition(
                1, "价格", "多少钱", "", "FACT", "", "", "", "", null,
                LocalDateTime.of(2026, 9, 4, 12, 0));
    }
}
