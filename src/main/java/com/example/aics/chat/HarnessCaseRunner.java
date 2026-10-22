package com.example.aics.chat;

import com.example.aics.ai.ModelGateway;

/** 网页、CLI 调试接口和发布门禁共用的答案执行与断言策略。 */
public final class HarnessCaseRunner {
    private HarnessCaseRunner() {}

    public record Outcome(String answer, boolean modelRequested, boolean modelInvoked,
                          String error, HarnessAssertions.Result assertion) {}

    public static Outcome run(ChatEngine.Prepared prepared, HarnessAssertions.Spec spec,
                              boolean requestModel, ChatEngine engine, ModelGateway gateway) {
        boolean requested = requestModel || (spec != null && spec.requiresAnswer());
        boolean invoked = false;
        String error = null;
        String answer = prepared.agentAnswer() != null ? prepared.agentAnswer() : prepared.directAnswer();
        if (answer == null && prepared.modelPrompt() != null && requested) {
            invoked = true;
            try {
                answer = gateway.chat(prepared.modelPrompt());
                if (answer == null || answer.isBlank()) error = "模型未返回有效回答";
            } catch (Exception e) {
                error = "模型调用失败，请检查模型服务配置或稍后重试。";
            }
        }
        if (answer != null && error == null) {
            try {
                answer = engine.finalizeAnswer(prepared, answer);
                if (answer == null || answer.isBlank()) error = "回复校验后没有有效回答";
            } catch (Exception e) {
                error = "回复校验失败，请检查服务日志。";
            }
        }
        String domain = prepared.trace() == null ? null : prepared.trace().authorityDomain();
        String source = prepared.trace() == null ? null : prepared.trace().authoritySource();
        HarnessAssertions.Result assertion = HarnessAssertions.evaluate(spec,
                new HarnessAssertions.Actual(prepared.replyType(), answer, domain, source, invoked), error);
        return new Outcome(answer, requested, invoked, error, assertion);
    }
}
