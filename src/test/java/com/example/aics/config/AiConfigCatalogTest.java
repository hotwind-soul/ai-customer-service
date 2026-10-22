package com.example.aics.config;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiConfigCatalogTest {

    @Test
    void 所有配置键唯一且默认值都能通过自身校验() {
        Set<String> keys = new HashSet<>();
        for (AiConfigCatalog.Setting setting : AiConfigCatalog.definitions()) {
            assertThat(keys.add(setting.key())).as("配置键重复：%s", setting.key()).isTrue();
            assertThat(AiConfigCatalog.validateAndNormalize(setting.key(), setting.defaultValue()))
                    .as("默认值无效：%s", setting.key()).isNotNull();
        }
        assertThat(keys).contains("welcome.message", "intent.projects", "agent.classifierPrompt",
                "conversation.historyRounds", "model.embedding.baseUrl", "safety.abuseKeywords",
                "safety.threatAutoHandoff", "projectIntro.aiEnabled",
                "projectIntro.requireBothImages", "projectIntro.decisionPrompt",
                "quality.aiJudgeEnabled", "quality.lowScoreThreshold", "quality.judgeTimeoutSeconds",
                "quality.judgePrompt", "prompt.moduleCharBudgets");
    }

    @Test
    void 关键词会去重并统一成每行一个() {
        assertThat(AiConfigCatalog.validateAndNormalize("image.intentKeywords", "图片，照片\n图片；案例"))
                .isEqualTo("图片\n照片\n案例");
    }

    @Test
    void 内置中文敏感词库只加载客服辱骂和明确威胁子集() {
        assertThat(AiConfigCatalog.keywords(null, "safety.abuseKeywords"))
                .contains("白痴", "狗娘养的", "婊子养的", "bitch")
                .doesNotContain("咖啡因", "发票", "共产党");
        assertThat(AiConfigCatalog.keywords(null, "safety.threatKeywords"))
                .contains("带刀去你们店", "对着你来一枪", "灭你全家");
    }

    @Test
    void 非法范围地址和Json会被拒绝() {
        assertThatThrownBy(() -> AiConfigCatalog.validateAndNormalize("threshold.relevance", "3"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AiConfigCatalog.validateAndNormalize("model.embedding.baseUrl", "file:///tmp/model"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AiConfigCatalog.validateAndNormalize("intent.projects", "[]"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AiConfigCatalog.validateAndNormalize("not.exists", "x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(AiConfigCatalog.validateAndNormalize("quality.judgeTimeoutSeconds", "5")).isEqualTo("5");
        assertThat(AiConfigCatalog.validateAndNormalize("quality.judgeTimeoutSeconds", "120")).isEqualTo("120");
        assertThatThrownBy(() -> AiConfigCatalog.validateAndNormalize("quality.judgeTimeoutSeconds", "4"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AiConfigCatalog.validateAndNormalize("quality.judgeTimeoutSeconds", "121"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 项目追问模板必须保留项目占位符() {
        assertThatThrownBy(() -> AiConfigCatalog.validateAndNormalize(
                "intent.projectQuestionTemplate", "您想了解哪方面？"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("{project}");
    }

    @Test
    void Prompt配置明确标记为版本化而普通参数保持即时发布() {
        assertThat(AiConfigCatalog.versionedPromptKeys())
                .contains("agent.systemPrompt", "prompt.tone", "prompt.ragRules",
                        "prompt.moduleCharBudgets",
                        "projectIntro.decisionPrompt", "agent.classifierPrompt",
                        "profile.prompt", "quality.judgePrompt")
                .doesNotContain("threshold.relevance", "model.temperature", "welcome.message");
    }

    @Test
    void Prompt模块预算拒绝遗漏未知模块和越界数值() {
        assertThatThrownBy(() -> AiConfigCatalog.validateAndNormalize(
                "prompt.moduleCharBudgets", "{\"identity.agent\":100}"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AiConfigCatalog.validateAndNormalize(
                "prompt.moduleCharBudgets", "{\"unknown.module\":1000}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未知模块");
    }
}
