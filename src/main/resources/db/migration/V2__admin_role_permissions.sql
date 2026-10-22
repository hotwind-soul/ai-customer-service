-- 后台账号分级：super_admin（超级管理员）/ admin（管理员）/ agent（客服坐席）。
-- 现有主账号 admin 自动升级为超级管理员；其他历史管理员保留原有非敏感后台能力。

ALTER TABLE agent
    ADD COLUMN IF NOT EXISTS enabled BOOLEAN NOT NULL DEFAULT TRUE;

UPDATE agent
SET role = 'super_admin'
WHERE username = 'admin' AND role = 'admin';

CREATE TABLE IF NOT EXISTS admin_permission (
    agent_id        BIGINT NOT NULL REFERENCES agent(id) ON DELETE CASCADE,
    permission_code VARCHAR(64) NOT NULL,
    create_time     TIMESTAMP NOT NULL DEFAULT now(),
    PRIMARY KEY (agent_id, permission_code)
);

CREATE INDEX IF NOT EXISTS idx_admin_permission_code
    ON admin_permission(permission_code);

-- 升级前若存在其他 role=admin 账号，默认保留原来可访问的全部非敏感模块。
-- 接口设置和管理员账号管理不在权限码中，始终只允许 super_admin。
INSERT INTO admin_permission(agent_id, permission_code)
SELECT a.id, p.permission_code
FROM agent a
CROSS JOIN (VALUES
    ('report.view'),
    ('ai.debug'),
    ('conversations.view'),
    ('knowledge.manage'),
    ('images.manage'),
    ('stores.manage'),
    ('audit.view'),
    ('agents.manage'),
    ('operations.manage')
) AS p(permission_code)
WHERE a.role = 'admin'
ON CONFLICT DO NOTHING;
