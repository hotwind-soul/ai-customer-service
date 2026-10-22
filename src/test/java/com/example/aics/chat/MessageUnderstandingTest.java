package com.example.aics.chat;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MessageUnderstandingTest {

    @Test
    void 子项目长别名优先于父项目短别名且不依赖目录顺序() {
        Map<String, List<String>> rules = new LinkedHashMap<>();
        rules.put("抗衰", List.of("抗衰"));
        rules.put("抗衰全脸", List.of("抗衰全脸", "全脸抗衰"));

        MessageUnderstanding.Result result = MessageUnderstanding.analyze("抗衰全脸多少钱", "", null, rules);

        assertEquals("抗衰全脸", result.project());
        MessageUnderstanding.Result followup = MessageUnderstanding.analyze(
                "多少钱", "用户：想了解全脸抗衰\n客服：您想了解什么？", null, rules);
        assertEquals("抗衰全脸", followup.project());
        assertTrue(followup.inheritedProject());
    }

    @Test
    void 空别名不会匹配任意客户消息() {
        MessageUnderstanding.Result result = MessageUnderstanding.analyze("多少钱", "", null,
                Map.of("抗衰", List.of("", "  ", "抗衰")));

        assertNull(result.project());
        assertFalse(result.complete());
    }

    @Test
    void 只有客服开场时不能把客服推荐当作客户选择() {
        for (String history : List.of("客服：可以咨询抗衰和淡斑", "assistant: 可以咨询抗衰",
                "客服开场语：抗衰活动介绍")) {
            MessageUnderstanding.Result result = MessageUnderstanding.analyze("多少钱", history);
            assertNull(result.project(), history);
            assertFalse(result.complete(), history);
        }
    }

    @Test
    void 无角色标签的历史保持兼容() {
        MessageUnderstanding.Result result = MessageUnderstanding.analyze("多少钱", "我想了解淡斑");
        assertEquals("淡斑", result.project());
        assertTrue(result.inheritedProject());
    }

    @Test
    void 项目中心新增别名后无需改代码即可识别() {
        MessageUnderstanding.Result result = MessageUnderstanding.analyze(
                "我想问超声炮多少钱", "", null,
                Map.of("面部年轻化", List.of("面部年轻化", "超声炮")));

        assertTrue(result.complete());
        assertEquals("面部年轻化", result.project());
        assertEquals("价格", result.aspect());
    }

    @Test
    void 两字项目不是无意义短句() {
        MessageUnderstanding.Result result = MessageUnderstanding.analyze("淡斑", "（无历史对话）");

        assertEquals("淡斑", result.project());
        assertFalse(result.complete());
        assertTrue(result.nextQuestion().contains("淡斑的哪方面"));
    }

    @Test
    void 项目加维度可以直接检索() {
        MessageUnderstanding.Result result = MessageUnderstanding.analyze("淡斑要做几次", "");

        assertTrue(result.complete());
        assertEquals("淡斑", result.project());
        assertEquals("疗程", result.aspect());
        assertEquals("淡斑要做几次", result.retrievalQuery());
    }

    @Test
    void 短追问从最近客户消息继承项目() {
        MessageUnderstanding.Result result = MessageUnderstanding.analyze(
                "效果呢", "用户：我想做抗衰\n客服：您想了解哪方面？\n");

        assertTrue(result.complete());
        assertEquals("抗衰", result.project());
        assertEquals("效果", result.aspect());
        assertTrue(result.inheritedProject());
        assertEquals("抗衰，效果呢", result.retrievalQuery());
    }

    @Test
    void 不从客服列举的项目中错误继承() {
        MessageUnderstanding.Result result = MessageUnderstanding.analyze(
                "多少钱", "用户：你好\n客服：可以咨询抗衰、淡斑、眼袋或胡子。\n");

        assertFalse(result.complete());
        assertNull(result.project());
        assertTrue(result.nextQuestion().contains("哪个项目"));
    }

    @Test
    void 未说明项目询问仪器_先澄清项目() {
        MessageUnderstanding.Result result = MessageUnderstanding.analyze("你们用什么仪器做", "");

        assertFalse(result.complete());
        assertEquals("流程", result.aspect());
        assertNull(result.project());
        assertTrue(result.nextQuestion().contains("项目"));
        assertTrue(result.nextQuestion().contains("仪器"));
    }

    @Test
    void 未说明项目询问恢复情况_先澄清项目() {
        MessageUnderstanding.Result result = MessageUnderstanding.analyze("做完能上班吗", "");

        assertFalse(result.complete());
        assertEquals("恢复", result.aspect());
        assertNull(result.project());
        assertTrue(result.nextQuestion().contains("哪个项目"));
    }

    @Test
    void 未说明项目询问操作时长_先澄清项目而不混答() {
        MessageUnderstanding.Result result = MessageUnderstanding.analyze("做一次项目要多久", "");

        assertFalse(result.complete());
        assertEquals("流程", result.aspect());
        assertNull(result.project());
        assertTrue(result.nextQuestion().contains("操作时长"));
        assertTrue(result.nextQuestion().contains("哪个项目"));
    }

    @Test
    void 未说明项目询问维持时间_先澄清项目() {
        MessageUnderstanding.Result result = MessageUnderstanding.analyze("做完能维持多久啊", "");

        assertFalse(result.complete());
        assertEquals("效果", result.aspect());
        assertNull(result.project());
        assertTrue(result.nextQuestion().contains("哪个项目"));
    }

    @Test
    void 口语询问能管多久_识别效果维度并追问项目() {
        MessageUnderstanding.Result result = MessageUnderstanding.analyze("做一次能管多久", "");

        assertFalse(result.complete());
        assertEquals("效果", result.aspect());
        assertNull(result.project());
    }

    @Test
    void 与项目无关的请求不会被项目词误判为项目简称() {
        MessageUnderstanding.Result result = MessageUnderstanding.analyze("帮我写一首淡斑的诗", "");

        assertTrue(result.complete());
        assertEquals("淡斑", result.project());
    }

    @Test
    void 订金余额问题识别为价格维度() {
        MessageUnderstanding.Result result = MessageUnderstanding.analyze("抗衰交了订金，到店还要付多少", "");

        assertTrue(result.complete());
        assertEquals("抗衰", result.project());
        assertEquals("价格", result.aspect());
    }

    @Test
    void 打针还是手术识别为流程维度() {
        MessageUnderstanding.Result result = MessageUnderstanding.analyze("抗衰是打针还是手术", "");

        assertTrue(result.complete());
        assertEquals("抗衰", result.project());
        assertEquals("流程", result.aspect());
    }
}
