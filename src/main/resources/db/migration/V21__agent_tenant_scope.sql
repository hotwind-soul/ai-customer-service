-- 人工坐席服务范围：网页/全局会话（tenant_id IS NULL）始终可服务；
-- 企微会话必须命中坐席已分配企业，若分配了具体客服号则进一步按客服号收窄。
CREATE TABLE IF NOT EXISTS agent_tenant_scope (
    agent_id   BIGINT NOT NULL REFERENCES agent(id) ON DELETE CASCADE,
    tenant_id  BIGINT NOT NULL REFERENCES wecom_tenant(id) ON DELETE CASCADE,
    PRIMARY KEY (agent_id, tenant_id)
);

CREATE TABLE IF NOT EXISTS agent_kf_scope (
    agent_id       BIGINT NOT NULL REFERENCES agent(id) ON DELETE CASCADE,
    kf_account_id  BIGINT NOT NULL REFERENCES wecom_kf_account(id) ON DELETE CASCADE,
    PRIMARY KEY (agent_id, kf_account_id)
);

CREATE INDEX IF NOT EXISTS idx_agent_tenant_scope_tenant
    ON agent_tenant_scope(tenant_id, agent_id);
CREATE INDEX IF NOT EXISTS idx_agent_kf_scope_account
    ON agent_kf_scope(kf_account_id, agent_id);

-- 升级时保持现有坐席的服务连续性：赋予当时已有的全部企业；
-- agent_kf_scope 留空表示可服务这些企业下的全部客服号。后续可在后台主动收窄。
INSERT INTO agent_tenant_scope(agent_id, tenant_id)
SELECT a.id, t.id FROM agent a CROSS JOIN wecom_tenant t WHERE a.role = 'agent'
ON CONFLICT DO NOTHING;
