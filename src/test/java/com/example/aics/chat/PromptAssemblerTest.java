package com.example.aics.chat;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PromptAssemblerTest {

    @Test
    void 按信任层和顺序组装并跳过空模块() {
        ChatPrompt prompt = new PromptAssembler("customer-service-test-v1")
                .add("style.customer", "v1", PromptModule.Layer.SYSTEM, 900, "亲和简洁")
                .add("identity.agent", "v2", PromptModule.Layer.SYSTEM, 100, "你是客服")
                .add("memory.empty", "v1", PromptModule.Layer.CONTEXT, 100, "  ")
                .add("knowledge.retrieved", "v3", PromptModule.Layer.CONTEXT, 500, "参考资料")
                .add("request.current", "v1", PromptModule.Layer.USER, 100, "客户问题")
                .assemble();

        assertThat(prompt.system()).isEqualTo("你是客服\n\n亲和简洁");
        assertThat(prompt.context()).isEqualTo("参考资料");
        assertThat(prompt.user()).isEqualTo("客户问题");
        assertThat(prompt.version()).isEqualTo("customer-service-test-v1");
        assertThat(prompt.modules()).extracting(PromptModule.Descriptor::id)
                .containsExactly("identity.agent", "style.customer", "knowledge.retrieved", "request.current");
        assertThat(prompt.fingerprint()).hasSize(16);
        assertThat(prompt.metadata()).containsEntry("systemChars", 10)
                .containsEntry("contextChars", 4)
                .containsEntry("userChars", 4);
    }

    @Test
    void 拒绝重复模块标识避免配置被静默覆盖() {
        PromptAssembler assembler = new PromptAssembler("test-v1")
                .add("identity.agent", "v1", PromptModule.Layer.SYSTEM, 100, "规则一");

        assertThatThrownBy(() -> assembler.add(
                "identity.agent", "v2", PromptModule.Layer.SYSTEM, 200, "规则二"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("模块 id 重复");
    }

    @Test
    void 各模块按独立预算裁剪并记录原始长度() {
        String history = "旧".repeat(300) + "最新对话";
        ChatPrompt prompt = new PromptAssembler("budget-v1", Map.of(
                "identity.agent", 200,
                "memory.recent-history", 200))
                .add("identity.agent", "v1", PromptModule.Layer.SYSTEM, 100, "人设".repeat(200))
                .add("memory.recent-history", "v1", PromptModule.Layer.CONTEXT, 400, history)
                .assemble();

        assertThat(prompt.system().length()).isLessThanOrEqualTo(200);
        assertThat(prompt.context()).endsWith("最新对话");
        assertThat(prompt.modules()).allSatisfy(module -> {
            assertThat(module.truncated()).isTrue();
            assertThat(module.originalCharCount()).isGreaterThan(module.charCount());
            assertThat(module.charBudget()).isEqualTo(200);
        });
    }
}
