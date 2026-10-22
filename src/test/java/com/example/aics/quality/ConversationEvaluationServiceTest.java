package com.example.aics.quality;

import com.example.aics.audit.AuditService;
import com.example.aics.config.ConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ConversationEvaluationServiceTest {
    private JdbcTemplate jdbc;
    private ConfigService config;
    private QualityJudgeService judge;
    private ConversationEvaluationService service;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        config = mock(ConfigService.class);
        judge = mock(QualityJudgeService.class);
        service = new ConversationEvaluationService(jdbc, config, judge, mock(AuditService.class));
        when(config.getInt("quality.minimumMessages", 2)).thenReturn(2);
        when(jdbc.query(anyString(),
                org.mockito.ArgumentMatchers.<RowMapper<QualityRuleEngine.Message>>any(), anyString())).thenReturn(List.of(
                new QualityRuleEngine.Message(1, "user", "淡斑", null),
                new QualityRuleEngine.Message(2, "assistant", "您想了解哪个项目？", "CLARIFY")));
        when(jdbc.queryForObject(contains("INSERT INTO ai_conversation_evaluation"), eq(Long.class), any(Object[].class)))
                .thenReturn(9L);
        when(jdbc.queryForObject(contains("INSERT INTO ai_quality_evaluation_attempt"), eq(Long.class), any(Object[].class)))
                .thenReturn(44L);
        when(jdbc.queryForList(contains("FROM ai_conversation_evaluation e JOIN conversation"), eq(9L)))
                .thenReturn(List.of(new java.util.LinkedHashMap<>(Map.of(
                        "id", 9L, "conversationId", "conv-1", "totalScore", 81,
                        "issuesJson", "[]", "suggestionsJson", "[]"))));
        when(jdbc.queryForList(contains("FROM message WHERE conversation_id"), eq("conv-1")))
                .thenReturn(List.of());
    }

    @Test
    void ruleScoringDoesNotCallAiJudge() throws Exception {
        Map<String, Object> result = service.score("conv-1", "rules", "reviewer");

        assertThat(result.get("id")).isEqualTo(9L);
        verifyNoInteractions(judge);
    }

    @Test
    void batchScoringPromotesPendingAttemptInsteadOfCreatingDuplicateHistory() {
        when(jdbc.queryForList(contains("status='pending'"), eq(Long.class), eq(7L), eq("conv-1")))
                .thenReturn(List.of(55L));

        service.score("conv-1", "rules", "reviewer", 7L);

        verify(jdbc).update(contains("SET status='running'"), eq("rules"), anyString(),
                isNull(), eq("risk-scan-v3"), eq(55L));
        verify(jdbc, never()).queryForObject(contains("INSERT INTO ai_quality_evaluation_attempt"),
                eq(Long.class), any(Object[].class));
    }

    @Test
    void aiJudgeMustBeExplicitlyEnabledInConfiguration() {
        when(config.getBoolean("quality.aiJudgeEnabled", false)).thenReturn(false);

        assertThatThrownBy(() -> service.score("conv-1", "primary", "reviewer"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未启用");
    }

    @Test
    void aiFailureKeepsRuleScoreAndReturnsWarning() throws Exception {
        when(config.getBoolean("quality.aiJudgeEnabled", false)).thenReturn(true);
        when(judge.modelName("primary")).thenReturn("deepseek-chat");
        when(judge.judge(anyList(), eq("primary"))).thenThrow(new RuntimeException("quota"));

        Map<String, Object> result = service.score("conv-1", "primary", "reviewer");

        assertThat(result.get("warning")).asString().contains("规则风险扫描").contains("尚未生成质量分");
        assertThat(result.get("judgeErrorCode")).isEqualTo("model_error");
        verify(judge).judge(anyList(), eq("primary"));
        verify(jdbc).update(contains("judge_error_code"), eq("model_error"), anyString(),
                eq("primary"), anyString(), eq(9L));
        verify(jdbc).update(contains("ai_quality_evaluation_attempt SET evaluation_id"), any(Object[].class));
    }

    @Test
    void failedRescorePreservesExistingSemanticScore() throws Exception {
        when(config.getBoolean("quality.aiJudgeEnabled", false)).thenReturn(true);
        when(judge.modelName("eval")).thenReturn("ep-test");
        when(judge.judge(anyList(), eq("eval"))).thenThrow(new RuntimeException("429 rate limit"));
        when(jdbc.queryForList(contains("SELECT id,judge_enabled"), eq("conv-1")))
                .thenReturn(List.of(Map.of(
                        "id", 9L, "judgeEnabled", true,
                        "evaluationVersion", "semantic-v3", "reviewResult", "")));

        Map<String, Object> result = service.score("conv-1", "eval", "reviewer");

        assertThat(result.get("warning")).asString().contains("接口请求过于频繁").contains("已保留");
        assertThat(result.get("judgeErrorPreservedScore")).isEqualTo(true);
        verify(jdbc, never()).queryForObject(contains("INSERT INTO ai_conversation_evaluation"),
                eq(Long.class), any(Object[].class));
        verify(jdbc).update(contains("judge_error_code"), eq("rate_limited"), anyString(),
                eq("eval"), eq("ep-test"), eq(9L));
    }

    @Test
    void automaticBatchOnlySelectsUnevaluatedConversations() {
        when(config.getInt("quality.batchLimit", 20)).thenReturn(20);
        when(jdbc.queryForList(contains("SELECT c.conversation_id"), eq(String.class), eq(2), eq(20)))
                .thenReturn(List.of());

        Map<String, Object> result = service.batchScore(List.of(), "rules", "reviewer");

        assertThat(result.get("scored")).isEqualTo(0);
        verify(jdbc).queryForList(argThat(sql -> sql.contains("NOT EXISTS")
                        && sql.contains("ai_conversation_evaluation")),
                eq(String.class), eq(2), eq(20));
    }

    @Test
    void aiBatchAlsoSelectsExistingRuleOnlyBaselines() {
        when(config.getInt("quality.batchLimit", 20)).thenReturn(20);
        when(jdbc.queryForList(contains("SELECT c.conversation_id"), eq(String.class), eq(2), eq(20)))
                .thenReturn(List.of());

        Map<String, Object> result = service.batchScore(List.of(), "eval", "reviewer");

        assertThat(result.get("scored")).isEqualTo(0);
        verify(jdbc).queryForList(argThat(sql -> sql.contains("judge_enabled")
                        && sql.contains("review_result") && sql.contains("OR EXISTS")),
                eq(String.class), eq(2), eq(20));
    }

    @Test
    void semanticScoreIsAiLedAndRulesOnlyCapKnownRisks() {
        QualityRuleEngine.Score noRuleRisk = new QualityRuleEngine.Score(
                20, 20, 15, 20, 10, 10, 5, List.of(), List.of());
        QualityRuleEngine.Score ai = new QualityRuleEngine.Score(
                11, 12, 8, 14, 6, 5, 3, List.of(), List.of("补全漏答"));

        QualityRuleEngine.Score merged = service.merge(noRuleRisk, ai);

        assertThat(merged.total()).isEqualTo(ai.total());
        QualityRuleEngine.Score explicitRuleRisk = new QualityRuleEngine.Score(
                20, 7, 15, 20, 10, 4, 5, List.of(), List.of());
        QualityRuleEngine.Score capped = service.merge(explicitRuleRisk,
                new QualityRuleEngine.Score(18, 18, 13, 19, 9, 9, 5, List.of(), List.of()));
        assertThat(capped.accuracy()).isEqualTo(7);
        assertThat(capped.resolution()).isEqualTo(4);
    }

    @Test
    void rejectsUnknownJudgeProviderBeforeCallingModel() {
        assertThatThrownBy(() -> service.score("conv-1", "automatic", "reviewer"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("规则、主模型、备用模型或评测专用模型");
        verifyNoInteractions(judge);
    }

    @Test
    void passesExplicitFallbackSelectionToJudge() throws Exception {
        when(config.getBoolean("quality.aiJudgeEnabled", false)).thenReturn(true);
        when(judge.modelName("fallback")).thenReturn("glm-4-flash");
        QualityRuleEngine.Score aiScore = new QualityRuleEngine.Score(
                18, 18, 13, 19, 9, 8, 5, List.of(), List.of());
        when(judge.judge(anyList(), eq("fallback")))
                .thenReturn(new QualityJudgeService.JudgeResult(aiScore, "fallback", "glm-4-flash"));

        service.score("conv-1", "fallback", "reviewer");

        verify(judge).judge(anyList(), eq("fallback"));
        verify(judge, never()).judge(anyList(), eq("primary"));
        verify(jdbc).queryForObject(contains("INSERT INTO ai_conversation_evaluation"),
                eq(Long.class), any(Object[].class));
        verify(jdbc).update(contains("status='succeeded'"), any(Object[].class));
    }

    @Test
    void evaluationFiltersUseBusinessStageInsteadOfRawDatabaseStatus() {
        assertThat(ConversationEvaluationService.evaluationStatusCondition("pending"))
                .contains("NOT").contains("evaluation_version='semantic-v3'");
        assertThat(ConversationEvaluationService.evaluationStatusCondition("scored"))
                .contains("evaluation_version='semantic-v3'").contains("e.status='scored'");
        assertThat(ConversationEvaluationService.evaluationStatusCondition("reviewed"))
                .isEqualTo("e.status='reviewed'");
        assertThat(ConversationEvaluationService.evaluationStatusCondition("failed"))
                .isEqualTo("e.judge_error_code IS NOT NULL");
        assertThatThrownBy(() -> ConversationEvaluationService.evaluationStatusCondition("unknown"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void evaluationListOrdersByMostRecentScoringOrReviewActivity() {
        assertThat(ConversationEvaluationService.evaluationActivityOrderSql())
                .contains("e.evaluated_at", "e.reviewed_at", "e.judge_error_at")
                .contains("DESC,e.id DESC");
    }
}
