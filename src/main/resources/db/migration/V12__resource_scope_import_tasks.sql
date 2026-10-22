ALTER TABLE import_task ADD COLUMN IF NOT EXISTS tenant_id BIGINT REFERENCES wecom_tenant(id) ON DELETE CASCADE;
ALTER TABLE import_task ADD COLUMN IF NOT EXISTS kf_account_id BIGINT REFERENCES wecom_kf_account(id) ON DELETE CASCADE;
CREATE INDEX IF NOT EXISTS idx_import_task_scope ON import_task(tenant_id, kf_account_id, create_time);
