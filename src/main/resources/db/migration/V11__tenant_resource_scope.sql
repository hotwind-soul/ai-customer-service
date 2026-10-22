-- 修正历史基线中 conversation/store/appointment 预留 tenant_id 为 VARCHAR 的类型，
-- 并为会话相关业务数据与 AI 资源补齐企业、客服号归属。

ALTER TABLE conversation ALTER COLUMN tenant_id DROP DEFAULT;
ALTER TABLE conversation ALTER COLUMN tenant_id TYPE BIGINT
    USING CASE WHEN tenant_id::text ~ '^[0-9]+$' THEN tenant_id::text::BIGINT ELSE NULL END;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_conversation_wecom_tenant') THEN
        ALTER TABLE conversation ADD CONSTRAINT fk_conversation_wecom_tenant
            FOREIGN KEY (tenant_id) REFERENCES wecom_tenant(id) ON DELETE SET NULL;
    END IF;
END $$;

ALTER TABLE store ALTER COLUMN tenant_id DROP DEFAULT;
ALTER TABLE store ALTER COLUMN tenant_id TYPE BIGINT
    USING CASE WHEN tenant_id::text ~ '^[0-9]+$' THEN tenant_id::text::BIGINT ELSE NULL END;
ALTER TABLE appointment ALTER COLUMN tenant_id DROP DEFAULT;
ALTER TABLE appointment ALTER COLUMN tenant_id TYPE BIGINT
    USING CASE WHEN tenant_id::text ~ '^[0-9]+$' THEN tenant_id::text::BIGINT ELSE NULL END;

ALTER TABLE knowledge_document ADD COLUMN IF NOT EXISTS tenant_id BIGINT REFERENCES wecom_tenant(id) ON DELETE CASCADE;
ALTER TABLE knowledge_document ADD COLUMN IF NOT EXISTS kf_account_id BIGINT REFERENCES wecom_kf_account(id) ON DELETE CASCADE;
ALTER TABLE knowledge_chunk ADD COLUMN IF NOT EXISTS tenant_id BIGINT REFERENCES wecom_tenant(id) ON DELETE CASCADE;
ALTER TABLE knowledge_chunk ADD COLUMN IF NOT EXISTS kf_account_id BIGINT REFERENCES wecom_kf_account(id) ON DELETE CASCADE;
ALTER TABLE image_asset ADD COLUMN IF NOT EXISTS tenant_id BIGINT REFERENCES wecom_tenant(id) ON DELETE CASCADE;
ALTER TABLE image_asset ADD COLUMN IF NOT EXISTS kf_account_id BIGINT REFERENCES wecom_kf_account(id) ON DELETE CASCADE;
ALTER TABLE store ADD COLUMN IF NOT EXISTS kf_account_id BIGINT REFERENCES wecom_kf_account(id) ON DELETE CASCADE;
ALTER TABLE appointment ADD COLUMN IF NOT EXISTS kf_account_id BIGINT REFERENCES wecom_kf_account(id) ON DELETE SET NULL;
ALTER TABLE leave_message ADD COLUMN IF NOT EXISTS tenant_id BIGINT REFERENCES wecom_tenant(id) ON DELETE SET NULL;
ALTER TABLE leave_message ADD COLUMN IF NOT EXISTS kf_account_id BIGINT REFERENCES wecom_kf_account(id) ON DELETE SET NULL;
ALTER TABLE wake_task ADD COLUMN IF NOT EXISTS tenant_id BIGINT REFERENCES wecom_tenant(id) ON DELETE SET NULL;
ALTER TABLE wake_task ADD COLUMN IF NOT EXISTS kf_account_id BIGINT REFERENCES wecom_kf_account(id) ON DELETE SET NULL;
ALTER TABLE business_fact ADD COLUMN IF NOT EXISTS id BIGSERIAL;
ALTER TABLE business_fact ADD COLUMN IF NOT EXISTS tenant_id BIGINT REFERENCES wecom_tenant(id) ON DELETE CASCADE;
ALTER TABLE business_fact ADD COLUMN IF NOT EXISTS kf_account_id BIGINT REFERENCES wecom_kf_account(id) ON DELETE CASCADE;
ALTER TABLE business_fact DROP CONSTRAINT IF EXISTS business_fact_pkey;
ALTER TABLE business_fact ADD CONSTRAINT business_fact_pkey PRIMARY KEY (id);

ALTER TABLE knowledge_document DROP CONSTRAINT IF EXISTS knowledge_document_doc_name_key;
CREATE UNIQUE INDEX IF NOT EXISTS uq_knowledge_document_scope_name
    ON knowledge_document(tenant_id, kf_account_id, doc_name) NULLS NOT DISTINCT;
CREATE INDEX IF NOT EXISTS idx_knowledge_chunk_scope ON knowledge_chunk(tenant_id, kf_account_id);
CREATE INDEX IF NOT EXISTS idx_image_asset_scope ON image_asset(tenant_id, kf_account_id, enabled);
CREATE INDEX IF NOT EXISTS idx_store_scope ON store(tenant_id, kf_account_id, city);
CREATE INDEX IF NOT EXISTS idx_appointment_scope ON appointment(tenant_id, kf_account_id, status);
CREATE INDEX IF NOT EXISTS idx_leave_message_scope ON leave_message(tenant_id, kf_account_id, status);
CREATE INDEX IF NOT EXISTS idx_wake_task_scope ON wake_task(tenant_id, kf_account_id, status);
CREATE UNIQUE INDEX IF NOT EXISTS uq_business_fact_scope_key
    ON business_fact(tenant_id, kf_account_id, fact_key) NULLS NOT DISTINCT;
CREATE INDEX IF NOT EXISTS idx_business_fact_scope ON business_fact(tenant_id, kf_account_id, status);

-- 升级时给现有管理员分配当时已经存在的全部企业；后续新管理员由超级管理员显式分配。
INSERT INTO admin_tenant_scope(agent_id, tenant_id)
SELECT a.id, t.id FROM agent a CROSS JOIN wecom_tenant t WHERE a.role = 'admin'
ON CONFLICT DO NOTHING;
