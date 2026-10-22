-- 可由超级管理员维护的 OpenAI 兼容聊天模型列表。
-- 历史 DeepSeek / 智谱密钥继续保留在 system_config 或环境变量中，表内只记录引用，
-- 避免迁移时复制或解密已有密钥。
CREATE TABLE IF NOT EXISTS ai_model_provider (
    id                  BIGSERIAL PRIMARY KEY,
    provider_code       VARCHAR(64) NOT NULL UNIQUE,
    display_name        VARCHAR(100) NOT NULL,
    base_url            VARCHAR(500) NOT NULL,
    model_name          VARCHAR(160) NOT NULL,
    api_protocol        VARCHAR(32) NOT NULL DEFAULT 'openai-compatible',
    api_key_ciphertext  TEXT,
    legacy_key_ref      VARCHAR(100),
    role                VARCHAR(20) NOT NULL DEFAULT 'candidate',
    enabled             BOOLEAN NOT NULL DEFAULT TRUE,
    create_time         TIMESTAMP NOT NULL DEFAULT now(),
    update_time         TIMESTAMP NOT NULL DEFAULT now(),
    CONSTRAINT chk_ai_model_provider_role
        CHECK (role IN ('primary', 'fallback', 'candidate')),
    CONSTRAINT chk_ai_model_provider_protocol
        CHECK (api_protocol = 'openai-compatible')
);

-- 数据库层同时阻止两个启用的主模型或备用模型；业务层负责角色交换和友好提示。
CREATE UNIQUE INDEX IF NOT EXISTS uq_ai_model_provider_active_primary
    ON ai_model_provider(role) WHERE enabled AND role = 'primary';
CREATE UNIQUE INDEX IF NOT EXISTS uq_ai_model_provider_active_fallback
    ON ai_model_provider(role) WHERE enabled AND role = 'fallback';
CREATE INDEX IF NOT EXISTS idx_ai_model_provider_role_enabled
    ON ai_model_provider(role, enabled);

-- 首次升级保持原有主备行为。若旧版曾在 AI 配置中心改过地址或模型名，沿用旧值。
INSERT INTO ai_model_provider(
    provider_code, display_name, base_url, model_name, legacy_key_ref, role, enabled)
VALUES (
    'deepseek',
    'DeepSeek',
    COALESCE(NULLIF((SELECT value FROM system_config WHERE key='model.primary.baseUrl'), ''),
             'https://api.deepseek.com/v1'),
    COALESCE(NULLIF((SELECT value FROM system_config WHERE key='model.primary.name'), ''),
             'deepseek-chat'),
    'ai.deepseek_key',
    'primary',
    TRUE
)
ON CONFLICT (provider_code) DO NOTHING;

INSERT INTO ai_model_provider(
    provider_code, display_name, base_url, model_name, legacy_key_ref, role, enabled)
VALUES (
    'bigmodel',
    '智谱开放平台',
    COALESCE(NULLIF((SELECT value FROM system_config WHERE key='model.fallback.baseUrl'), ''),
             'https://open.bigmodel.cn/api/paas/v4'),
    COALESCE(NULLIF((SELECT value FROM system_config WHERE key='model.fallback.name'), ''),
             'glm-4-flash'),
    'ai.bigmodel_key',
    'fallback',
    TRUE
)
ON CONFLICT (provider_code) DO NOTHING;
