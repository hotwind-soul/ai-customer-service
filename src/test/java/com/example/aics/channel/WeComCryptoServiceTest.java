package com.example.aics.channel;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 企微回调加解密回归测试（channel 包骨架，2026-08-07）。
 * 验签用企业微信官方示例报文（WXBizMsgCrypt 样例值）；加解密做往返一致性验证。
 * 守护"回调必须验签、密文必须可解密"的通道安全基线。
 */
class WeComCryptoServiceTest {

    // 企微官方样例（WXBizMsgCrypt Java 示例）
    private static final String TOKEN = "QDG6eK";
    private static final String CORP_ID = "wx5823bf96d3bd56c7";
    private static final String AES_KEY = "jWmYm7qr5nMoAUwZRjGtBxmz3KA1tkAj3ykkR6q2B2C";

    private WeComCryptoService svc() {
        return new WeComCryptoService(CORP_ID, TOKEN, AES_KEY);
    }

    @Test
    void 配置完整时_已配置() {
        assertTrue(svc().isConfigured());
    }

    @Test
    void 密钥缺失时_未配置_不抛异常() {
        WeComCryptoService empty = new WeComCryptoService("", "", "");
        assertFalse(empty.isConfigured());
        assertFalse(empty.verifySignature("x", "1", "2", "3"));
        assertThrows(IllegalStateException.class, () -> empty.decrypt("abc"));
    }

    @Test
    void 验签_企微官方样例_通过() {
        WeComCryptoService s = svc();
        // 官方样例值（msg_signature / timestamp / nonce / echostr，echostr 已做 URL 解码）
        String sig = "5c45ff5e21c57e6ad56bac8758b79b1d9ac89fd3";
        String ts = "1409659589";
        String nonce = "263014780";
        String echostr = "P9nAzCzyDtyTWESHep1vC5X9xho/qYX3Zpb4yKa9SKld1DsH3Iyt3tP3zNdtp+4RPcs8TgAE7OaBO+FZXvnaqQ==";
        assertTrue(s.verifySignature(sig, ts, nonce, echostr));
    }

    @Test
    void 验签_内容被篡改_不通过() {
        WeComCryptoService s = svc();
        String sig = "5c45ff5e21c57e6ad56bac8758b79b1d9ac89fd3";
        String echostr = "P9nAzCzyDtyTWESHep1vC5X9xho/qYX3Zpb4yKa9SKld1DsH3Iyt3tP3zNdtp+4RPcs8TgAE7OaBO+FZXvnaqQ==";
        assertFalse(s.verifySignature(sig, "1409659589", "263014780", echostr + "x"));
    }

    @Test
    void 加解密_往返一致() {
        WeComCryptoService s = svc();
        String msg = "<xml><ToUserName>ww123</ToUserName><MsgType>event</MsgType></xml>";
        String encrypted = s.encrypt(msg);
        assertFalse(encrypted.isBlank());
        assertEquals(msg, s.decrypt(encrypted));
    }

    @Test
    void 解密_伪造密文_抛异常() {
        WeComCryptoService s = svc();
        assertThrows(IllegalStateException.class, () -> s.decrypt("not-a-valid-base64-密文"));
    }

    @Test
    void 生产构造器不读取旧版全局配置_必须显式传入企业() {
        WeComCryptoService service = new WeComCryptoService();
        assertFalse(service.isConfigured());
        WeComTenantService.TenantCredentials tenant = new WeComTenantService.TenantCredentials(
                1, "tenant-a", "A 企业", CORP_ID, "secret", TOKEN, AES_KEY, true);
        assertTrue(service.isConfigured(tenant));
        String encrypted = service.encrypt(tenant, "企业配置生效");
        assertEquals("企业配置生效", service.decrypt(tenant, encrypted));
    }
}
