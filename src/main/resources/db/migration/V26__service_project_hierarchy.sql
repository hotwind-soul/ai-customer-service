-- 一级父子项目：子项目独立维护报价、话术、图片和门店，父项目作为首页聚合入口。
ALTER TABLE service_project
    ADD COLUMN IF NOT EXISTS parent_id BIGINT
        REFERENCES service_project(id) ON DELETE RESTRICT;

ALTER TABLE service_project
    DROP CONSTRAINT IF EXISTS ck_service_project_not_self_parent;
ALTER TABLE service_project
    ADD CONSTRAINT ck_service_project_not_self_parent
        CHECK (parent_id IS NULL OR parent_id <> id);

CREATE INDEX IF NOT EXISTS idx_service_project_parent
    ON service_project(parent_id, status, sort_order);
