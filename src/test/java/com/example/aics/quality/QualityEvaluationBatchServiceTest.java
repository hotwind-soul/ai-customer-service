package com.example.aics.quality;

import com.example.aics.audit.AuditService;
import com.example.aics.config.AdminDataScope;
import com.example.aics.config.ConfigService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QualityEvaluationBatchServiceTest {
    private ConversationEvaluationService evaluations;
    private JdbcTemplate jdbc;
    private ConfigService config;
    private QualityEvaluationBatchService service;

    @BeforeEach
    void setUp() {
        evaluations = mock(ConversationEvaluationService.class);
        jdbc = mock(JdbcTemplate.class);
        config = mock(ConfigService.class);
        service = new QualityEvaluationBatchService(evaluations, jdbc, config, mock(AuditService.class));
        when(config.getInt("quality.batchScoreIntervalMs", 1000)).thenReturn(0);
        when(evaluations.batchFailureMessage(any(Exception.class))).thenReturn("boom");
    }

    @AfterEach
    void tearDown() {
        service.shutdown();
    }

    @Test
    void recordsProgressAndPartialCompletionWithoutBlockingHttpRequest() {
        ConversationEvaluationService.BatchPlan plan = new ConversationEvaluationService.BatchPlan(
                List.of("conv-1", "conv-2"), "eval", "judge-model");
        when(evaluations.score(eq("conv-1"), eq("eval"), eq("admin"), eq(7L)))
                .thenReturn(Map.of("qualityScored", true));
        when(evaluations.score(eq("conv-2"), eq("eval"), eq("admin"), eq(7L)))
                .thenThrow(new IllegalStateException("remote error"));

        service.run(7L, plan, "admin", new AdminDataScope.AccessScope(false, List.of(), List.of()));

        verify(jdbc).update(contains("SET status='running'"), eq(7L));
        verify(jdbc).update(contains("SET status=?"), eq("partial"), eq(1), eq(1),
                anyString(), eq("conversation_failures"), eq("boom"), eq(7L));
        verify(evaluations).score("conv-1", "eval", "admin", 7L);
        verify(evaluations).score("conv-2", "eval", "admin", 7L);
    }
}
