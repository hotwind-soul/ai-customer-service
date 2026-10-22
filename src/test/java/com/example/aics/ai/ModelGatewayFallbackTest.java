package com.example.aics.ai;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ModelGatewayFallbackTest {

    @Test
    void 中国免费模型使用独立智谱Key() {
        assertThat(ModelGateway.fallbackApiKey(
                "https://open.bigmodel.cn/api/paas/v4", "bigmodel-key", "dashscope-key"))
                .isEqualTo("bigmodel-key");
    }

    @Test
    void 历史百炼备用地址继续兼容通义Key() {
        assertThat(ModelGateway.fallbackApiKey(
                "https://dashscope.aliyuncs.com/compatible-mode/v1", "", "dashscope-key"))
                .isEqualTo("dashscope-key");
        assertThat(ModelGateway.fallbackApiKey(
                "https://open.bigmodel.cn/api/paas/v4", "", "dashscope-key"))
                .isEmpty();
    }

    @Test
    void 空泛非回答会被识别为模型软失败() {
        assertThat(ModelGateway.isNonAnswerAnswer("抱歉，我刚才没收到您的问题，请再说一遍"))
                .isTrue();
        assertThat(ModelGateway.isNonAnswerAnswer("您好，请问有什么可以帮您？"))
                .isTrue();
        assertThat(ModelGateway.isNonAnswerAnswer("  ")).isTrue();
    }

    @Test
    void 有实质内容的短回答不会被误判为软失败() {
        assertThat(ModelGateway.isNonAnswerAnswer("淡斑活动价为198元，具体以门店当期政策为准。"))
                .isFalse();
        assertThat(ModelGateway.isNonAnswerAnswer("活动价688元"))
                .isFalse();
    }

    @Test
    void 仅在模型明确拒绝JsonSchema时降级JsonObject() {
        assertThat(ModelGateway.structuredOutputUnsupported(new RuntimeException(
                "400 Bad Request: response_format json_schema is not supported"))).isTrue();
        assertThat(ModelGateway.structuredOutputUnsupported(new RuntimeException(
                "read timeout"))).isFalse();
        assertThat(ModelGateway.structuredOutputUnsupported(new RuntimeException(
                "401 Unauthorized"))).isFalse();
    }

    @Test
    void 综合测试必须校验完整质量评分Json() {
        String valid = "{\"intent\":18,\"accuracy\":18,\"context\":14,\"safety\":20," +
                "\"naturalness\":9,\"resolution\":9,\"handoff\":5," +
                "\"issues\":[],\"suggestions\":[],\"summary\":\"测试通过\"}";

        assertThat(ModelGateway.qualityProbeValidationError(valid)).isNull();
        assertThat(ModelGateway.qualityProbeValidationError("连接成功"))
                .isEqualTo("质量评测没有返回 JSON 对象");
        assertThat(ModelGateway.qualityProbeValidationError(
                "{\"intent\":18,\"issues\":[],\"suggestions\":[],\"summary\":\"不完整\"}"))
                .isEqualTo("质量评测缺少整数评分字段：accuracy");
    }

    @Test
    void 综合测试按失败阶段给出可执行诊断() {
        assertThat(ModelGateway.providerTestHint("quality", "read timeout"))
                .contains("模型可以连接", "评测超时", "模型负载");
        assertThat(ModelGateway.providerTestHint("basic", "401 Unauthorized"))
                .contains("API 密钥无效");
        assertThat(ModelGateway.providerTestHint("basic", "java.net.ConnectException: Permission denied"))
                .contains("出网权限", "防火墙");
    }
}
