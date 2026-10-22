-- 项目报价长期有效，只由项目状态和报价状态控制。
-- 图片素材、知识文档和业务事实的有效期字段不受影响。
DROP INDEX IF EXISTS idx_project_offer_active;

ALTER TABLE project_offer
    DROP CONSTRAINT IF EXISTS ck_project_offer_validity,
    DROP COLUMN IF EXISTS valid_from,
    DROP COLUMN IF EXISTS valid_until;

CREATE INDEX IF NOT EXISTS idx_project_offer_active
    ON project_offer(project_id, status);
