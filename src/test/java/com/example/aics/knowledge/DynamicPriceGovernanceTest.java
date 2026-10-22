package com.example.aics.knowledge;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DynamicPriceGovernanceTest {

    @Test
    void 价格问法即使没有写金额也应退出向量检索() {
        var decision = DynamicPriceGovernance.inspect(
                "问：抗衰多少钱？\n答：请咨询客服\n分类：抗衰", "faq");

        assertThat(decision.governed()).isTrue();
        assertThat(decision.reason()).contains("项目中心");
    }

    @Test
    void 普通文档保存具体价格时也应治理() {
        var decision = DynamicPriceGovernance.inspect(
                "抗衰当前活动价298元，订金8元。", "reference");

        assertThat(decision.governed()).isTrue();
    }

    @Test
    void 非金额数字和结构化报价留档不应误判() {
        assertThat(DynamicPriceGovernance.inspect(
                "一般需要结合情况评估，可能需要3次护理。", "reference").governed()).isFalse();
        assertThat(DynamicPriceGovernance.inspect(
                "活动价298元", "offer").governed()).isFalse();
    }
}
