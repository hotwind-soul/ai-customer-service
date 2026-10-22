-- 多企业、多微信客服账号：所有企微凭证、客户身份和会话路由必须显式归属企业。
CREATE TABLE IF NOT EXISTS wecom_tenant (
    id                     BIGSERIAL PRIMARY KEY,
    tenant_code            VARCHAR(50) UNIQUE NOT NULL,
    name                   VARCHAR(100) NOT NULL,
    corp_id                VARCHAR(128) NOT NULL,
    secret_ciphertext      TEXT NOT NULL DEFAULT '',
    callback_token_ciphertext TEXT NOT NULL DEFAULT '',
    aes_key_ciphertext     TEXT NOT NULL DEFAULT '',
    enabled                BOOLEAN NOT NULL DEFAULT TRUE,
    create_time            TIMESTAMP NOT NULL DEFAULT now(),
    update_time            TIMESTAMP NOT NULL DEFAULT now(),
    CONSTRAINT chk_wecom_tenant_code CHECK (tenant_code ~ '^[a-z0-9][a-z0-9-]{1,48}[a-z0-9]$')
);

CREATE TABLE IF NOT EXISTS wecom_kf_account (
    id               BIGSERIAL PRIMARY KEY,
    tenant_id        BIGINT NOT NULL REFERENCES wecom_tenant(id) ON DELETE CASCADE,
    open_kfid        VARCHAR(128) NOT NULL,
    name             VARCHAR(100) NOT NULL,
    business_scope   VARCHAR(500) NOT NULL DEFAULT '',
    welcome_message  TEXT NOT NULL DEFAULT '',
    enabled          BOOLEAN NOT NULL DEFAULT TRUE,
    create_time      TIMESTAMP NOT NULL DEFAULT now(),
    update_time      TIMESTAMP NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, open_kfid)
);
CREATE INDEX IF NOT EXISTS idx_wecom_kf_account_tenant
    ON wecom_kf_account(tenant_id, enabled, id);

-- 管理员仍只有 admin / super_admin 两种角色；普通管理员通过数据范围限定可见企业和客服号。
CREATE TABLE IF NOT EXISTS admin_tenant_scope (
    agent_id   BIGINT NOT NULL REFERENCES agent(id) ON DELETE CASCADE,
    tenant_id  BIGINT NOT NULL REFERENCES wecom_tenant(id) ON DELETE CASCADE,
    PRIMARY KEY (agent_id, tenant_id)
);
CREATE TABLE IF NOT EXISTS admin_kf_scope (
    agent_id       BIGINT NOT NULL REFERENCES agent(id) ON DELETE CASCADE,
    kf_account_id  BIGINT NOT NULL REFERENCES wecom_kf_account(id) ON DELETE CASCADE,
    PRIMARY KEY (agent_id, kf_account_id)
);

-- external_userid 只在一个企业主体内唯一，不能再作为全局主键。
ALTER TABLE external_user ADD COLUMN IF NOT EXISTS id BIGSERIAL;
ALTER TABLE external_user ADD COLUMN IF NOT EXISTS tenant_id BIGINT REFERENCES wecom_tenant(id) ON DELETE CASCADE;
ALTER TABLE external_user DROP CONSTRAINT IF EXISTS external_user_pkey;
ALTER TABLE external_user ADD CONSTRAINT external_user_pkey PRIMARY KEY (id);
CREATE UNIQUE INDEX IF NOT EXISTS uq_external_user_tenant_identity
    ON external_user(tenant_id, external_userid) NULLS NOT DISTINCT;
CREATE INDEX IF NOT EXISTS idx_external_user_tenant_user
    ON external_user(tenant_id, user_id);

ALTER TABLE conversation ADD COLUMN IF NOT EXISTS tenant_id BIGINT REFERENCES wecom_tenant(id) ON DELETE SET NULL;
ALTER TABLE conversation ADD COLUMN IF NOT EXISTS kf_account_id BIGINT REFERENCES wecom_kf_account(id) ON DELETE SET NULL;
CREATE INDEX IF NOT EXISTS idx_conversation_tenant_kf_customer
    ON conversation(tenant_id, kf_account_id, external_userid, update_time DESC)
    WHERE channel = 'kf';

-- 企微消息及状态事件按企业和客服号幂等；数据库约束阻止同一企业内重复客服号。
CREATE TABLE IF NOT EXISTS wecom_event_receipt (
    tenant_id      BIGINT NOT NULL REFERENCES wecom_tenant(id) ON DELETE CASCADE,
    kf_account_id  BIGINT REFERENCES wecom_kf_account(id) ON DELETE CASCADE,
    msg_id         VARCHAR(128) NOT NULL,
    create_time    TIMESTAMP NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, msg_id)
);
CREATE INDEX IF NOT EXISTS idx_wecom_event_receipt_time
    ON wecom_event_receipt(create_time);
