package com.example.aics.chat;

import com.example.aics.ai.ModelGateway;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class HarnessCaseRunnerTest {
    private final ChatEngine engine = mock(ChatEngine.class);
    private final ModelGateway gateway = mock(ModelGateway.class);

    @Test
    void 仅有必须调用模型断言时自动生成() {
        when(gateway.chat(any(ChatPrompt.class))).thenReturn("有效回复");
        when(engine.finalizeAnswer(any(), anyString())).thenAnswer(call -> call.getArgument(1));
        var outcome = HarnessCaseRunner.run(ai(), spec("", "", true), false, engine, gateway);
        assertThat(outcome.modelRequested()).isTrue();
        assertThat(outcome.modelInvoked()).isTrue();
        assertThat(outcome.assertion().passed()).isTrue();
        verify(gateway).chat(any(ChatPrompt.class));
    }

    @Test
    void 空白要点不能掩盖禁止内容对生成的要求() {
        when(gateway.chat(any(ChatPrompt.class))).thenReturn("有效回复");
        when(engine.finalizeAnswer(any(), anyString())).thenAnswer(call -> call.getArgument(1));
        var outcome = HarnessCaseRunner.run(ai(), spec("  ", "保证", null), false, engine, gateway);
        assertThat(outcome.assertion().passed()).isTrue();
        verify(gateway).chat(any(ChatPrompt.class));
    }

    @Test
    void 模型异常时禁止内容断言失败且记录调用尝试() {
        when(gateway.chat(any(ChatPrompt.class))).thenThrow(new IllegalStateException("test model failure"));
        var outcome = HarnessCaseRunner.run(ai(), spec("", "保证", null), false, engine, gateway);
        assertThat(outcome.modelInvoked()).isTrue();
        assertThat(outcome.error()).isNotBlank();
        assertThat(outcome.assertion().passed()).isFalse();
    }

    @Test
    void 路由预览仍可跳过生成() {
        var outcome = HarnessCaseRunner.run(ai(), spec("", "", null), false, engine, gateway);
        assertThat(outcome.modelRequested()).isFalse();
        assertThat(outcome.assertion().passed()).isTrue();
        verifyNoInteractions(gateway);
    }

    @Test
    void 直回答案不因内容断言而额外调用模型() {
        var prepared = new ChatEngine.Prepared("test", false, null, List.of(), List.of(),
                "298元", List.of(), List.of(), null, "FACT", null);
        when(engine.finalizeAnswer(any(), anyString())).thenAnswer(call -> call.getArgument(1));
        var outcome = HarnessCaseRunner.run(prepared,
                new HarnessAssertions.Spec("FACT", "298元", "", "", "", false), false, engine, gateway);
        assertThat(outcome.assertion().passed()).isTrue();
        verifyNoInteractions(gateway);
    }

    @Test
    void 回复校验异常不能成为通过结果() {
        when(gateway.chat(any(ChatPrompt.class))).thenReturn("有效回复");
        when(engine.finalizeAnswer(any(), anyString())).thenThrow(new IllegalStateException("test validation failure"));
        var outcome = HarnessCaseRunner.run(ai(), spec("", "保证", null), false, engine, gateway);
        assertThat(outcome.assertion().passed()).isFalse();
        assertThat(outcome.error()).contains("回复校验失败");
    }

    private ChatEngine.Prepared ai() {
        return new ChatEngine.Prepared("test", false, "test prompt", List.of(), List.of(),
                null, List.of(), List.of(), null, "AI", null);
    }

    private HarnessAssertions.Spec spec(String required, String forbidden, Boolean invoked) {
        return new HarnessAssertions.Spec("AI", required, forbidden, "", "", invoked);
    }
}
