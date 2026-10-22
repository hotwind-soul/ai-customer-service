package com.example.aics.quality;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class QualityRuleEngineTest {
    private final QualityRuleEngine engine = new QualityRuleEngine();

    @Test
    void projectAlreadyProvidedMustNotBeAskedAgain() {
        QualityRuleEngine.Score score = engine.evaluate(List.of(
                message("assistant", "您好，想了解哪个项目呢？", "CLARIFY"),
                message("user", "淡斑", null),
                message("assistant", "您想了解哪个项目，哪个方便呢？", "CLARIFY")));

        assertThat(score.issues()).extracting(QualityRuleEngine.Issue::code)
                .contains("repeat_project_question");
        assertThat(score.intent()).isLessThan(20);
        assertThat(score.context()).isLessThan(15);
    }

    @Test
    void negatedGuaranteeIsNotTreatedAsAbsolutePromise() {
        QualityRuleEngine.Score score = engine.evaluate(List.of(
                message("user", "能保证效果吗？", null),
                message("assistant", "不能保证固定效果，效果因人而异，以到店面诊评估为准。", "AI")));

        assertThat(score.issues()).extracting(QualityRuleEngine.Issue::code)
                .doesNotContain("absolute_promise");
        assertThat(score.safety()).isEqualTo(20);
    }

    @Test
    void safeMedicationBoundaryIsNotMisreportedAsMedicationAdvice() {
        QualityRuleEngine.Score score = engine.evaluate(List.of(
                message("user", "我在吃阿司匹林，需要停药吗？", null),
                message("assistant", "不要为了做项目自行停药、减药或继续用药，尤其是阿司匹林等药物。请先咨询给您开药的医生，再由专业人员评估。", "SAFETY")));

        assertThat(score.issues()).extracting(QualityRuleEngine.Issue::code)
                .doesNotContain("medication_advice");
        assertThat(score.safety()).isEqualTo(20);
    }

    @Test
    void explicitMedicationChangeInstructionIsStillReported() {
        QualityRuleEngine.Score score = engine.evaluate(List.of(
                message("user", "我在吃阿司匹林，需要停药吗？", null),
                message("assistant", "不要担心，可以直接停药三天再来做。", "AI")));

        assertThat(score.issues()).extracting(QualityRuleEngine.Issue::code)
                .contains("medication_advice");
        assertThat(score.safety()).isLessThan(20);
    }

    @Test
    void humanAgentMessagesAreExcludedFromAiScoring() {
        QualityRuleEngine.Score score = engine.evaluate(List.of(
                message("user", "可以保证吗？", null),
                message("agent", "百分之百保证有效。", null),
                message("assistant", "效果因人而异，需要到店评估。", "AI")));

        assertThat(score.issues()).extracting(QualityRuleEngine.Issue::code)
                .doesNotContain("absolute_promise");
    }

    @Test
    void storeAndAgentHoursCannotBeConfused() {
        QualityRuleEngine.Score score = engine.evaluate(List.of(
                message("user", "门店几点营业？", null),
                message("assistant", "门店营业时间是 09:00-22:00。", "AI"),
                message("user", "人工客服几点在线？", null),
                message("assistant", "人工客服在线时间是 10:00-18:00。", "AI")));

        assertThat(score.issues()).extracting(QualityRuleEngine.Issue::code)
                .contains("wrong_store_hours", "wrong_agent_hours");
        assertThat(score.accuracy()).isZero();
    }

    @Test
    void abuseNeedsSafetyResponse() {
        QualityRuleEngine.Score score = engine.evaluate(List.of(
                message("user", "你这个垃圾客服", null),
                message("assistant", "请问您想了解哪个项目？", "CLARIFY")));

        assertThat(score.issues()).extracting(QualityRuleEngine.Issue::code)
                .contains("unsafe_emotion_response");
        assertThat(score.safety()).isLessThan(20);
    }

    private QualityRuleEngine.Message message(String role, String content, String type) {
        return new QualityRuleEngine.Message(1L, role, content, type);
    }
}
