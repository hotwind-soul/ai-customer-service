-- 统一项目中心：项目、别名、报价、图片和门店只维护一份主数据。
-- 旧字符串列暂时保留为兼容层，由应用服务同步，便于无停机渐进迁移。
CREATE TABLE IF NOT EXISTS service_project (
    id             BIGSERIAL PRIMARY KEY,
    tenant_id      BIGINT REFERENCES wecom_tenant(id) ON DELETE CASCADE,
    kf_account_id  BIGINT REFERENCES wecom_kf_account(id) ON DELETE CASCADE,
    code           VARCHAR(80) NOT NULL,
    name           VARCHAR(100) NOT NULL,
    status         VARCHAR(20) NOT NULL DEFAULT 'published',
    sort_order     INT NOT NULL DEFAULT 0,
    create_time    TIMESTAMP NOT NULL DEFAULT now(),
    update_time    TIMESTAMP NOT NULL DEFAULT now(),
    CONSTRAINT ck_service_project_status CHECK (status IN ('draft', 'published', 'disabled'))
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_service_project_scope_code
    ON service_project(tenant_id, kf_account_id, code) NULLS NOT DISTINCT;
CREATE UNIQUE INDEX IF NOT EXISTS uq_service_project_scope_name
    ON service_project(tenant_id, kf_account_id, name) NULLS NOT DISTINCT;
CREATE INDEX IF NOT EXISTS idx_service_project_scope_status
    ON service_project(tenant_id, kf_account_id, status, sort_order);

CREATE TABLE IF NOT EXISTS service_project_alias (
    project_id   BIGINT NOT NULL REFERENCES service_project(id) ON DELETE CASCADE,
    alias        VARCHAR(100) NOT NULL,
    create_time  TIMESTAMP NOT NULL DEFAULT now(),
    PRIMARY KEY (project_id, alias)
);
CREATE INDEX IF NOT EXISTS idx_service_project_alias_text
    ON service_project_alias(alias);

CREATE TABLE IF NOT EXISTS project_offer (
    id              BIGSERIAL PRIMARY KEY,
    project_id      BIGINT NOT NULL REFERENCES service_project(id) ON DELETE CASCADE,
    offer_key       VARCHAR(80) NOT NULL DEFAULT 'default',
    activity_price  NUMERIC(12,2),
    original_price  NUMERIC(12,2),
    deposit         NUMERIC(12,2),
    brief_copy      TEXT,
    sale_copy       TEXT,
    status          VARCHAR(20) NOT NULL DEFAULT 'draft',
    source          VARCHAR(255),
    valid_from      TIMESTAMP,
    valid_until     TIMESTAMP,
    create_time     TIMESTAMP NOT NULL DEFAULT now(),
    update_time     TIMESTAMP NOT NULL DEFAULT now(),
    CONSTRAINT uq_project_offer_key UNIQUE(project_id, offer_key),
    CONSTRAINT ck_project_offer_status CHECK (status IN ('draft', 'published', 'disabled')),
    CONSTRAINT ck_project_offer_activity_nonnegative CHECK (activity_price IS NULL OR activity_price >= 0),
    CONSTRAINT ck_project_offer_original_nonnegative CHECK (original_price IS NULL OR original_price >= 0),
    CONSTRAINT ck_project_offer_deposit_nonnegative CHECK (deposit IS NULL OR deposit >= 0),
    CONSTRAINT ck_project_offer_deposit_limit CHECK (
        deposit IS NULL OR activity_price IS NULL OR deposit <= activity_price
    ),
    CONSTRAINT ck_project_offer_validity CHECK (
        valid_from IS NULL OR valid_until IS NULL OR valid_until > valid_from
    )
);
CREATE INDEX IF NOT EXISTS idx_project_offer_active
    ON project_offer(project_id, status, valid_from, valid_until);

CREATE TABLE IF NOT EXISTS project_asset (
    project_id  BIGINT NOT NULL REFERENCES service_project(id) ON DELETE CASCADE,
    image_id    BIGINT NOT NULL REFERENCES image_asset(id) ON DELETE CASCADE,
    asset_role  VARCHAR(20) NOT NULL DEFAULT 'effect',
    sort_order  INT NOT NULL DEFAULT 0,
    PRIMARY KEY(project_id, image_id)
);

CREATE TABLE IF NOT EXISTS store_project (
    store_id    BIGINT NOT NULL REFERENCES store(id) ON DELETE CASCADE,
    project_id  BIGINT NOT NULL REFERENCES service_project(id) ON DELETE CASCADE,
    PRIMARY KEY(store_id, project_id)
);

-- 结构化报价只需留档，不参与向量检索；允许此类切片不存 embedding。
ALTER TABLE knowledge_chunk ALTER COLUMN embedding DROP NOT NULL;
ALTER TABLE knowledge_chunk ADD COLUMN IF NOT EXISTS project_id BIGINT
    REFERENCES service_project(id) ON DELETE SET NULL;
CREATE INDEX IF NOT EXISTS idx_knowledge_chunk_project_id
    ON knowledge_chunk(project_id, status);

-- 已知项目作为空库默认值；后面的导入会继续吸收已有配置和历史资源中的项目。
INSERT INTO service_project(tenant_id, kf_account_id, code, name, status, sort_order)
VALUES
    (NULL, NULL, 'anti-aging', '抗衰', 'published', 10),
    (NULL, NULL, 'eye-bag', '眼袋', 'published', 20),
    (NULL, NULL, 'spots', '淡斑', 'published', 30),
    (NULL, NULL, 'hair', '胡子', 'published', 40)
ON CONFLICT (tenant_id, kf_account_id, name) DO NOTHING;

-- 吸收图片、知识、事实台账和门店逗号文本里的已有项目，避免升级后丢项目。
INSERT INTO service_project(tenant_id, kf_account_id, code, name, status, sort_order)
SELECT DISTINCT tenant_id, kf_account_id,
       'project-' || substr(md5(trim(project)), 1, 12), trim(project), 'published', 100
FROM image_asset
WHERE project IS NOT NULL AND trim(project) <> ''
ON CONFLICT (tenant_id, kf_account_id, name) DO NOTHING;

INSERT INTO service_project(tenant_id, kf_account_id, code, name, status, sort_order)
SELECT DISTINCT tenant_id, kf_account_id,
       'project-' || substr(md5(trim(project)), 1, 12), trim(project), 'published', 100
FROM knowledge_chunk
WHERE project IS NOT NULL AND trim(project) <> ''
ON CONFLICT (tenant_id, kf_account_id, name) DO NOTHING;

INSERT INTO service_project(tenant_id, kf_account_id, code, name, status, sort_order)
SELECT DISTINCT tenant_id, kf_account_id,
       'project-' || substr(md5(trim(project)), 1, 12), trim(project), 'published', 100
FROM business_fact
WHERE project IS NOT NULL AND trim(project) <> ''
ON CONFLICT (tenant_id, kf_account_id, name) DO NOTHING;

INSERT INTO service_project(tenant_id, kf_account_id, code, name, status, sort_order)
SELECT DISTINCT s.tenant_id, s.kf_account_id,
       'project-' || substr(md5(trim(raw.name)), 1, 12), trim(raw.name), 'published', 100
FROM store s
CROSS JOIN LATERAL regexp_split_to_table(COALESCE(s.projects, ''), '[,，、]+') raw(name)
WHERE trim(raw.name) <> ''
ON CONFLICT (tenant_id, kf_account_id, name) DO NOTHING;

-- 吸收历史 intent.projects JSON 中运营维护过的项目及别名；非法旧 JSON 不阻塞升级。
DO $$
DECLARE
    raw_config TEXT;
    item RECORD;
    alias_value TEXT;
    resolved_project_id BIGINT;
BEGIN
    SELECT value INTO raw_config FROM system_config WHERE key = 'intent.projects';
    IF raw_config IS NOT NULL AND trim(raw_config) <> '' THEN
        FOR item IN SELECT key, value FROM jsonb_each(raw_config::jsonb)
        LOOP
            INSERT INTO service_project(tenant_id, kf_account_id, code, name, status, sort_order)
            VALUES (NULL, NULL, 'project-' || substr(md5(item.key), 1, 12), item.key, 'published', 100)
            ON CONFLICT (tenant_id, kf_account_id, name) DO NOTHING;
            SELECT id INTO resolved_project_id FROM service_project
            WHERE tenant_id IS NULL AND kf_account_id IS NULL AND name = item.key;
            FOR alias_value IN SELECT jsonb_array_elements_text(item.value)
            LOOP
                INSERT INTO service_project_alias(project_id, alias)
                VALUES (resolved_project_id, trim(alias_value))
                ON CONFLICT DO NOTHING;
            END LOOP;
        END LOOP;
    END IF;
EXCEPTION WHEN OTHERS THEN
    RAISE NOTICE '跳过无法解析的历史 intent.projects：%', SQLERRM;
END $$;

-- 标准名本身始终也是一个可识别别名。
INSERT INTO service_project_alias(project_id, alias)
SELECT id, name FROM service_project
ON CONFLICT DO NOTHING;

-- 把历史字符串关联迁移为规范化关系；优先选择同作用域项目，再回退全局项目。
INSERT INTO project_asset(project_id, image_id, asset_role)
SELECT project_match.id, i.id, COALESCE(NULLIF(i.asset_type, ''), 'effect')
FROM image_asset i
JOIN LATERAL (
    SELECT p.id FROM service_project p
    WHERE p.name = i.project
      AND (p.tenant_id IS NULL OR p.tenant_id IS NOT DISTINCT FROM i.tenant_id)
      AND (p.kf_account_id IS NULL OR p.kf_account_id IS NOT DISTINCT FROM i.kf_account_id)
    ORDER BY p.tenant_id NULLS LAST, p.kf_account_id NULLS LAST
    LIMIT 1
) project_match ON TRUE
WHERE i.project IS NOT NULL AND trim(i.project) <> ''
ON CONFLICT DO NOTHING;

INSERT INTO store_project(store_id, project_id)
SELECT s.id, project_match.id
FROM store s
CROSS JOIN LATERAL regexp_split_to_table(COALESCE(s.projects, ''), '[,，、]+') raw(name)
JOIN LATERAL (
    SELECT p.id FROM service_project p
    WHERE p.name = trim(raw.name)
      AND (p.tenant_id IS NULL OR p.tenant_id IS NOT DISTINCT FROM s.tenant_id)
      AND (p.kf_account_id IS NULL OR p.kf_account_id IS NOT DISTINCT FROM s.kf_account_id)
    ORDER BY p.tenant_id NULLS LAST, p.kf_account_id NULLS LAST
    LIMIT 1
) project_match ON TRUE
WHERE trim(raw.name) <> ''
ON CONFLICT DO NOTHING;

UPDATE knowledge_chunk k
SET project_id = (
    SELECT p.id FROM service_project p
    WHERE p.name = k.project
      AND (p.tenant_id IS NULL OR p.tenant_id IS NOT DISTINCT FROM k.tenant_id)
      AND (p.kf_account_id IS NULL OR p.kf_account_id IS NOT DISTINCT FROM k.kf_account_id)
    ORDER BY p.tenant_id NULLS LAST, p.kf_account_id NULLS LAST
    LIMIT 1
)
WHERE k.project_id IS NULL AND k.project IS NOT NULL AND trim(k.project) <> '';
