package com.example.aics.chat;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HarnessAssertionsTest {
    @Test
    void 数值保留小数符号比例与边界() {
        for (String[] pair : java.util.List.of(
                new String[]{"298元", "价格2.98元"}, new String[]{"余额298元", "余额-298元"},
                new String[]{"维持1.5年", "维持15年"}, new String[]{"8元", "价格298元"},
                new String[]{"8元", "订金0.8元"}, new String[]{"98%", "比例98"},
                new String[]{"298", "2，98"})) {
            HarnessAssertions.Result result = HarnessAssertions.evaluate(
                    new HarnessAssertions.Spec("FACT", pair[0], "", "", "", null),
                    new HarnessAssertions.Actual("FACT", pair[1], "", "", false));
            assertThat(result.passed()).as("%s 不应匹配 %s", pair[0], pair[1]).isFalse();
        }
    }

    @Test
    void 等值数字兼容空格全角千分位和小数尾零() {
        HarnessAssertions.Result result = HarnessAssertions.evaluate(
                new HarnessAssertions.Spec("FACT", "298元；1234元；98%；0.5年", "", "", "", null),
                new HarnessAssertions.Actual("FACT", "２９８．００ 元；1,234元；98 %；.50 年", "", "", false));
        assertThat(result.passed()).isTrue();
    }

    @Test
    void 空回答不能通过禁止内容检查() {
        HarnessAssertions.Result result = HarnessAssertions.evaluate(
                new HarnessAssertions.Spec("AI", "", "保证", "", "", null),
                new HarnessAssertions.Actual("AI", null, "", "", false));
        assertThat(result.passed()).isFalse();
        assertThat(result.failures()).anyMatch(reason -> reason.contains("未取得有效回答"));
    }

    @Test
    void 只有分隔符的检查项不能静默通过() {
        for (String spec : java.util.List.of("；;\n", "|||", "；｜；")) {
            assertThat(HarnessAssertions.evaluate(new HarnessAssertions.Spec("AI", spec, "", "", "", null),
                    new HarnessAssertions.Actual("AI", "有效回答", "", "", false)).passed()).isFalse();
        }
    }

    @Test
    void 即使没有内容断言执行失败也不能通过() {
        assertThat(HarnessAssertions.evaluate(null, null, "模型执行失败").passed()).isFalse();
    }

    @Test
    void 全部回答要点命中才通过并支持同义表达() {
        HarnessAssertions.Result result = HarnessAssertions.evaluate(
                new HarnessAssertions.Spec("FACT", "298元；8元|订金八元", "", "project", "project_catalog", false),
                new HarnessAssertions.Actual("FACT", "活动价 298 元，支付订金八元即可锁定名额。",
                        "project", "project_catalog", false));

        assertThat(result.passed()).isTrue();
        assertThat(result.failures()).isEmpty();
        assertThat(result.checks()).hasSize(5);
    }

    @Test
    void 路由正确但缺少回答要点仍然失败() {
        HarnessAssertions.Result result = HarnessAssertions.evaluate(
                new HarnessAssertions.Spec("FACT", "298元；8元", "", "", "", null),
                new HarnessAssertions.Actual("FACT", "活动价298元。", null, null, false));

        assertThat(result.passed()).isFalse();
        assertThat(result.failures()).containsExactly("回答要点：缺少：8元");
    }

    @Test
    void 命中禁止内容时失败() {
        HarnessAssertions.Result result = HarnessAssertions.evaluate(
                new HarnessAssertions.Spec("AI", "因人而异", "保证|百分百", "", "", true),
                new HarnessAssertions.Actual("AI", "效果因人而异，但保证一次见效。", null, null, true));

        assertThat(result.passed()).isFalse();
        assertThat(result.failures()).contains("禁止内容：出现：保证|百分百");
    }

    @Test
    void 权威来源和模型调用都参与断言() {
        HarnessAssertions.Result result = HarnessAssertions.evaluate(
                new HarnessAssertions.Spec("FACT", "", "", "store", "store_catalog|project_catalog", false),
                new HarnessAssertions.Actual("FACT", "门店营业时间为10点到18点。",
                        "store", "knowledge_base", true));

        assertThat(result.passed()).isFalse();
        assertThat(result.failures()).containsExactlyInAnyOrder(
                "权威来源：knowledge_base", "模型调用：已调用");
    }
}
