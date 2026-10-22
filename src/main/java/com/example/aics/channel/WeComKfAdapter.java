package com.example.aics.channel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.util.concurrent.RejectedExecutionException;

/**
 * 企业微信「微信客服 Kf」回调入口（channel 包骨架，2026-08-07）。
 *
 * 企微管理后台配置「接收消息服务器 URL」必须指向
 * https://<域名>/kf/callback/{tenantCode}：
 * - GET  同路径：配置回调时企微发加密 echostr，验签并解密后返回明文
 * - POST 同路径：客户消息/会话状态/发送失败等事件回调（XML 密文）
 *
 * 5 秒规则：POST handler 只做 验签→解密→事件分发（丢线程池异步处理）→ 立即回 "success"；
 * AI 处理在异步线程完成后再 kf/send_msg 发送，超过 5 秒企微会重试（按 msgid 幂等去重）。
 * tenantCode 先确定企业，再使用该企业独立的回调密钥和 access_token。
 */
@RestController
public class WeComKfAdapter {

    private static final Logger log = LoggerFactory.getLogger(WeComKfAdapter.class);
    private final WeComCryptoService crypto;
    private final WeComKfService kfService;
    private final WeComRuntimeMonitor monitor;
    private final WeComTenantService tenants;

    public WeComKfAdapter(WeComCryptoService crypto, WeComKfService kfService,
                         WeComRuntimeMonitor monitor, WeComTenantService tenants) {
        this.crypto = crypto;
        this.kfService = kfService;
        this.monitor = monitor;
        this.tenants = tenants;
    }

    /** 路径先确定企业，才能用正确的 Token/AESKey 验签和解密。 */
    @GetMapping(path = "/kf/callback/{tenantCode}", produces = MediaType.TEXT_PLAIN_VALUE)
    public String verifyTenantUrl(@PathVariable String tenantCode,
                                  @RequestParam(required = false) String msg_signature,
                                  @RequestParam(required = false) String timestamp,
                                  @RequestParam(required = false) String nonce,
                                  @RequestParam(required = false) String echostr) {
        WeComTenantService.TenantCredentials tenant = tenants.requireEnabledTenant(tenantCode);
        if (!crypto.isConfigured(tenant) || echostr == null) return "";
        try {
            if (!crypto.verifySignature(tenant, msg_signature, timestamp, nonce, echostr)) return "";
            return crypto.decrypt(tenant, echostr);
        } catch (Exception e) {
            log.warn("[Kf回调] 企业 {} URL 验证失败: {}", tenant.code(), e.getMessage());
            return "";
        }
    }

    @PostMapping(path = "/kf/callback/{tenantCode}", produces = MediaType.TEXT_PLAIN_VALUE)
    public String handleTenantEvent(@PathVariable String tenantCode,
                                    @RequestBody(required = false) String body,
                                    @RequestParam(required = false) String msg_signature,
                                    @RequestParam(required = false) String timestamp,
                                    @RequestParam(required = false) String nonce) {
        WeComTenantService.TenantCredentials tenant;
        try {
            tenant = tenants.requireEnabledTenant(tenantCode);
        } catch (Exception e) {
            monitor.callbackReceived();
            monitor.callbackRejected("企业不存在或已停用");
            return "success";
        }
        monitor.callbackReceived(tenant.code());
        if (!crypto.isConfigured(tenant) || body == null || body.isBlank()) {
            monitor.callbackRejected(tenant.code(),
                    !crypto.isConfigured(tenant) ? "企业回调密钥不完整" : "回调请求体为空");
            return "success";
        }
        try {
            String encrypt = extractEncrypt(body);
            if (encrypt == null
                    || !crypto.verifySignature(tenant, msg_signature, timestamp, nonce, encrypt)) {
                monitor.callbackRejected(tenant.code(), "企业回调密文验签失败");
                return "success";
            }
            String plainXml = crypto.decrypt(tenant, encrypt);
            kfService.handleEventAsync(tenant, plainXml);
            monitor.callbackAccepted(tenant.code());
        } catch (RejectedExecutionException e) {
            monitor.callbackRejected(tenant.code(), "回调处理队列已满");
            return "busy";
        } catch (Exception e) {
            log.error("[Kf回调] 企业 {} 处理失败: {}", tenant.code(), e.getMessage());
            monitor.callbackRejected(tenant.code(), e.getMessage());
        }
        return "success";
    }

    /** 从企微回调 XML 里提取 <Encrypt> 内容（不引 XML 依赖，简单截取） */
    private String extractEncrypt(String xml) {
        int start = xml.indexOf("<Encrypt>");
        int end = xml.indexOf("</Encrypt>");
        if (start < 0 || end < 0) return null;
        String value = xml.substring(start + "<Encrypt>".length(), end).trim();
        if (value.startsWith("<![CDATA[") && value.endsWith("]]>") && value.length() >= 12) {
            value = value.substring(9, value.length() - 3).trim();
        }
        return value;
    }
}
