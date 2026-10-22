package com.example.aics.config;

import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 密钥/API Key 配置（管理后台"设置"页，2026-08-08）。
 *
 * 优先级：system_config 表（网页可填、热生效）→ 环境变量（初始兜底，兼容原部署方式）。
 * 历史聊天模型密钥仍可由动态模型管理服务读取；新模型统一在“模型管理”页维护。
 *
 * 注意：本类只管"读"。写走 ConfigService.set（PUT /config/{key}，已有热更新机制）。
 */
@Component
public class KeyConfigService {

    private static final Logger log = LoggerFactory.getLogger(KeyConfigService.class);
    private static final Set<String> RETIRED_SECRET_KEYS = Set.of("ai.deepseek_key", "ai.bigmodel_key");
    private static final Map<String, String[]> KNOWN_KEYS = createKnownKeys();

    private final ConfigService configService;
    private final SecretCryptoService crypto;

    public KeyConfigService(ConfigService configService, SecretCryptoService crypto) {
        this.configService = configService;
        this.crypto = crypto;
    }

    /** 读密钥：DB 优先，空则回退环境变量（可能返回 ""） */
    public String get(String key, String envVar) {
        String stored = configService.get(key, "");
        if (stored != null && !stored.isBlank()) {
            // 已部署实例可能仍有升级前的明文值。有主密钥时首次读取即原地迁移；
            // 没有主密钥时仅为兼容旧开发环境继续读取，并明确告警，不允许新增明文。
            if (!crypto.isEncrypted(stored)) {
                if (crypto.isConfigured()) {
                    configService.set(key, crypto.encrypt(stored));
                    log.info("已把历史明文配置 {} 迁移为 AES-GCM 密文", key);
                } else {
                    log.warn("配置 {} 仍以历史明文保存；配置 AICS_CONFIG_ENCRYPTION_KEY 后会自动迁移", key);
                }
                return stored;
            }
            return crypto.decrypt(stored);
        }
        String env = System.getenv(envVar);
        return env == null ? "" : env;
    }

    /** 后台保存密钥：空值表示清除 DB 覆盖，非空值必须加密后落库。 */
    public void set(String key, String value) {
        if (!isSecretKey(key)) {
            throw new IllegalArgumentException("不是受支持的密钥配置：" + key);
        }
        configService.set(key, value == null || value.isBlank() ? "" : crypto.encrypt(value.trim()));
    }

    public boolean isSecretKey(String key) {
        return KNOWN_KEYS.containsKey(key);
    }

    /** 鉴权层使用：包含仍可能残留在旧链接/旧数据库中的历史密钥名，统一按超级管理员保护。 */
    public static boolean isSensitiveConfigKey(String key) {
        return KNOWN_KEYS.containsKey(key) || RETIRED_SECRET_KEYS.contains(key);
    }

    /** 不解密即可判断 DB 是否有覆盖值，供只写式设置页显示状态。 */
    public boolean hasStoredValue(String key) {
        return !configService.get(key, "").isBlank();
    }

    /** 是否已配置（DB 或环境变量任一有值） */
    public boolean has(String key, String envVar) {
        return hasStoredValue(key)
                || (envVar != null && !envVar.isBlank()
                && System.getenv(envVar) != null && !System.getenv(envVar).isBlank());
    }

    /**
     * 仅超级管理员"查看密钥"用：解密返回 DB 值，空则回退环境变量（可能返回 ""）。
     * 调用方（Controller）必须校验超级管理员角色并写审计日志。
     */
    public String reveal(String key) {
        String[] meta = KNOWN_KEYS.get(key);
        if (meta == null) throw new IllegalArgumentException("不是受支持的密钥配置：" + key);
        return get(key, meta.length > 3 ? meta[3] : "");
    }

    /** 已知配置项清单（设置页展示用）：key → [显示名, 分组, 说明, 环境变量回退名] */
    public Map<String, String[]> knownKeys() {
        return KNOWN_KEYS;
    }

    private static Map<String, String[]> createKnownKeys() {
        Map<String, String[]> map = new LinkedHashMap<>();
        // 通义 Key 仍专供向量化和图片识别；聊天模型 API Key 已迁移到独立模型列表。
        map.put("ai.dashscope_key", new String[]{"通义千问 API Key", "向量与图片模型", "知识库向量化和图片识别；仅在视觉/向量模型未单独配置密钥时作为兜底", "DASHSCOPE_API_KEY"});
        // 视觉 / 向量模型可单独配置密钥，优先于通义 Key；留空则按 baseUrl 回退到通义 Key。
        map.put("model.vision.apiKey", new String[]{"视觉模型 API Key", "向量与图片模型", "图片理解/OCR 模型鉴权密钥；留空则回退通义 DashScope Key", ""});
        map.put("model.embedding.apiKey", new String[]{"向量模型 API Key", "向量与图片模型", "知识库嵌入模型鉴权密钥；留空则回退通义 DashScope Key", ""});
        // 门店定位
        map.put("map.tencent_key", new String[]{"腾讯地图 Key", "门店定位", "输入地址自动解析经纬度（lbs.qq.com 免费申请）", ""});
        // 企微企业凭证和 open_kfid 只能在“企微企业”页按企业保存。
        // 旧版 system_config 中的 wecom.* 单企业键不再对外暴露，也不会被运行链路读取。
        // 语音识别
        map.put("wecom.asr_secret_id", new String[]{"腾讯云 ASR SecretId", "语音识别", "语音转文字（企微语音消息），腾讯云开通语音识别", ""});
        map.put("wecom.asr_secret_key", new String[]{"腾讯云 ASR SecretKey", "语音识别", "同上", ""});
        return java.util.Collections.unmodifiableMap(map);
    }
}
