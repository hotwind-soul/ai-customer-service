package com.example.aics.chat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FactAuthorityPolicyTest {

    private final FactAuthorityPolicy policy = new FactAuthorityPolicy();

    @Test
    void 价格只允许项目中心_不回退知识库() {
        var understanding = new MessageUnderstanding.Result(
                "抗衰", "价格", true, false, "抗衰多少钱", "完整", null);

        var decision = policy.decide("抗衰多少钱", understanding);

        assertEquals(FactAuthorityPolicy.Domain.PRICE, decision.domain());
        assertEquals(FactAuthorityPolicy.Source.PROJECT_CENTER, decision.source());
        assertFalse(decision.knowledgeFallbackAllowed());
        assertTrue(decision.unavailableAnswer().contains("不引用旧知识库价格"));
    }

    /** NLU 漏识别 aspect 时，文本关键词兜底同样拦截价格问题（2026-09-04 评审 P1-1）。 */
    @Test
    void 价格关键词兜底_NLU漏识别时仍拦截() {
        var understanding = new MessageUnderstanding.Result(
                "抗衰", null, true, false, "抗衰多少钱一次", "完整", null);

        var decision = policy.decide("抗衰多少钱一次", understanding);

        assertEquals(FactAuthorityPolicy.Domain.PRICE, decision.domain());
        assertEquals(FactAuthorityPolicy.Source.PROJECT_CENTER, decision.source());
        assertFalse(decision.knowledgeFallbackAllowed());
    }

    /** 命中价格意图但未识别出项目：同样禁止回退知识库，并引导用户先给项目名。 */
    @Test
    void 价格意图但无项目_仍不回退知识库() {
        var understanding = new MessageUnderstanding.Result(
                null, null, true, false, "做个护理大概什么价位", "完整", null);

        var decision = policy.decide("做个护理大概什么价位", understanding);

        assertEquals(FactAuthorityPolicy.Domain.PRICE, decision.domain());
        assertFalse(decision.knowledgeFallbackAllowed());
        assertTrue(decision.unavailableAnswer().contains("项目名称"));
    }

    @Test
    void 服务范围由项目中心管理() {
        var understanding = new MessageUnderstanding.Result(
                "抗衰全脸", null, true, false, "包含几个部位", "完整", null);

        var decision = policy.decide("抗衰全脸包含几个部位", understanding);

        assertEquals(FactAuthorityPolicy.Domain.PROJECT_SCOPE, decision.domain());
        assertEquals(FactAuthorityPolicy.Source.PROJECT_CENTER, decision.source());
        assertFalse(decision.knowledgeFallbackAllowed());
    }

    @Test
    void 护理与流程等稳定说明允许进入知识库() {
        var understanding = new MessageUnderstanding.Result(
                "淡斑", "流程", true, false, "淡斑是怎么操作的", "完整", null);

        var decision = policy.decide("淡斑是怎么操作的", understanding);

        assertEquals(FactAuthorityPolicy.Domain.GENERAL_KNOWLEDGE, decision.domain());
        assertEquals(FactAuthorityPolicy.Source.KNOWLEDGE_BASE, decision.source());
        assertTrue(decision.knowledgeFallbackAllowed());
    }
}
