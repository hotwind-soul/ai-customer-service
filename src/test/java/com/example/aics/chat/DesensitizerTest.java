package com.example.aics.chat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 脱敏组件回归测试（第二批 #7）。
 * 守护"出网必脱敏"原则：手机号/身份证/银行卡/微信/QQ/地址送出内网前必须打码；
 * 价格等业务数字不能误伤。
 * 注意：mask 保留上下文（"我的电话138****5678，方便联系"），断言用 contains/not contains。
 */
class DesensitizerTest {

    @Test
    void 手机号_11位_中间四位打码() {
        String masked = Desensitizer.mask("我的电话13812345678，方便联系");
        assertTrue(masked.contains("138****5678"));
        assertFalse(masked.contains("13812345678"));
    }

    @Test
    void 身份证_18位_保留前6后4() {
        String masked = Desensitizer.mask("身份证110101199001011234");
        assertTrue(masked.contains("110101********1234"));
        assertFalse(masked.contains("110101199001011234"));
    }

    @Test
    void 银行卡_16位_保留前4后4() {
        // 16 位卡号不会被身份证正则误匹配（19 位才会），走银行卡兜底规则
        String masked = Desensitizer.mask("卡号6222021234567890");
        assertTrue(masked.contains("6222********7890"));
        assertFalse(masked.contains("6222021234567890"));
    }

    @Test
    void 微信号_wx前缀() {
        String masked = Desensitizer.mask("加我wx_abc12345");
        assertTrue(masked.contains("wx_****"));
        assertFalse(masked.contains("wx_abc12345"));
    }

    @Test
    void 微信号_引导词格式() {
        String masked = Desensitizer.mask("微信号：shengyuan888");
        assertTrue(masked.contains("微信号：****"));
        assertFalse(masked.contains("shengyuan888"));
    }

    @Test
    void QQ号_保留前2后2() {
        String masked = Desensitizer.mask("QQ 12345678");
        assertTrue(masked.contains("12****78"));
        assertFalse(masked.contains("12345678"));
    }

    @Test
    void 地址_门牌号打码() {
        String masked = Desensitizer.mask("昆明市官渡区星耀路99号");
        assertTrue(masked.contains("昆明市官渡区星耀路****"));
    }

    @Test
    void 价格不误伤_688元保持() {
        String input = "全脸688元，抗衰298元";
        assertEquals(input, Desensitizer.mask(input), "价格数字不能被打码");
    }

    @Test
    void 纯文本无敏感_原样返回() {
        String input = "你好，请问抗衰怎么做？";
        assertEquals(input, Desensitizer.mask(input));
    }

    @Test
    void null和空串_安全返回() {
        assertNull(Desensitizer.mask(null));
        assertEquals("", Desensitizer.mask(""));
    }

    @Test
    void 多敏感共存_全部打码() {
        String masked = Desensitizer.mask("电话13812345678，微信：abc888，QQ 12345678");
        assertFalse(masked.contains("13812345678"));
        assertFalse(masked.contains("abc888"));
        assertFalse(masked.contains("12345678"));
    }
}
