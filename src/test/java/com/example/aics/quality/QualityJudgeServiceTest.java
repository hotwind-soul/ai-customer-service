package com.example.aics.quality;

import com.example.aics.ai.ModelGateway;
import com.example.aics.config.ConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QualityJudgeServiceTest {
    private ModelGateway gateway;
    private QualityJudgeService service;

    @BeforeEach
    void setUp() {
        gateway = mock(ModelGateway.class);
        ConfigService config = mock(ConfigService.class);
        when(config.get(anyString(), anyString())).thenAnswer(call -> call.getArgument(1));
        service = new QualityJudgeService(gateway, config);
    }

    @Test
    void selectedPrimaryProviderIsForwardedWithoutGatewayFallback() throws Exception {
        when(gateway.qualityJudgeChat(anyString(), eq("primary")))
                .thenReturn(new ModelGateway.QualityJudgeResponse(validScore(), "primary", "deepseek-chat"));

        QualityJudgeService.JudgeResult result = service.judge(messages(), "primary");

        assertThat(result.provider()).isEqualTo("primary");
        assertThat(result.modelName()).isEqualTo("deepseek-chat");
        verify(gateway).qualityJudgeChat(anyString(), eq("primary"));
    }

    @Test
    void selectedFallbackProviderIsForwardedWithoutGatewayFailover() throws Exception {
        when(gateway.qualityJudgeChat(anyString(), eq("fallback")))
                .thenReturn(new ModelGateway.QualityJudgeResponse(validScore(), "fallback", "glm-4-flash"));

        QualityJudgeService.JudgeResult result = service.judge(messages(), "fallback");

        assertThat(result.provider()).isEqualTo("fallback");
        assertThat(result.modelName()).isEqualTo("glm-4-flash");
        verify(gateway).qualityJudgeChat(anyString(), eq("fallback"));
    }

    @Test
    void invalidModelJsonIsCorrectedWithOneSameModelRetry() throws Exception {
        when(gateway.qualityJudgeChat(anyString(), eq("primary")))
                .thenReturn(new ModelGateway.QualityJudgeResponse("不是JSON", "primary", "deepseek-chat"))
                .thenReturn(new ModelGateway.QualityJudgeResponse(validScore(), "primary", "deepseek-chat"));

        QualityJudgeService.JudgeResult result = service.judge(messages(), "primary");

        assertThat(result.score().total()).isEqualTo(93);
        verify(gateway, times(2)).qualityJudgeChat(anyString(), eq("primary"));
    }

    @Test
    void twoInvalidResponsesReturnTypedSafeFailure() {
        when(gateway.qualityJudgeChat(anyString(), eq("primary")))
                .thenReturn(new ModelGateway.QualityJudgeResponse("不是JSON", "primary", "deepseek-chat"));

        assertThatThrownBy(() -> service.judge(messages(), "primary"))
                .isInstanceOf(QualityJudgeService.JudgeResponseException.class)
                .hasMessageContaining("连续两次")
                .satisfies(error -> assertThat(((QualityJudgeService.JudgeResponseException) error).code())
                        .isEqualTo("invalid_response"));
        verify(gateway, times(2)).qualityJudgeChat(anyString(), eq("primary"));
    }

    @Test
    void structuredIssueKeepsMessageEvidenceAndDimension() throws Exception {
        String response = "{\"intent\":12,\"accuracy\":10,\"context\":8,\"safety\":20," +
                "\"naturalness\":8,\"resolution\":5,\"handoff\":5," +
                "\"issues\":[{\"messageId\":2,\"dimension\":\"resolution\"," +
                "\"problem\":\"未回答价格\",\"evidence\":\"只做了项目介绍\"}]," +
                "\"suggestions\":[],\"summary\":\"存在漏答\"}";
        when(gateway.qualityJudgeChat(anyString(), eq("eval")))
                .thenReturn(new ModelGateway.QualityJudgeResponse(response, "eval", "judge-model"));

        QualityJudgeService.JudgeResult result = service.judge(messages(), "eval");

        assertThat(result.score().issues()).singleElement().satisfies(issue -> {
            assertThat(issue.dimension()).isEqualTo("resolution");
            assertThat(issue.description()).isEqualTo("未回答价格");
            assertThat(issue.evidence()).contains("消息#2").contains("只做了项目介绍");
        });
    }

    private List<QualityRuleEngine.Message> messages() {
        return List.of(new QualityRuleEngine.Message(1, "user", "淡斑", null),
                new QualityRuleEngine.Message(2, "assistant", "为您详细介绍淡斑项目。", "INTRO"));
    }

    private String validScore() {
        return "{\"intent\":18,\"accuracy\":18,\"context\":14,\"safety\":20," +
                "\"naturalness\":9,\"resolution\":9,\"handoff\":5," +
                "\"issues\":[],\"suggestions\":[],\"summary\":\"回答基本合理\"}";
    }
}
