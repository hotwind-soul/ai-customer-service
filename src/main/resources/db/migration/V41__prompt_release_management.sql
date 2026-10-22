-- Prompt 发布治理：按管理员保存草稿，发布时生成不可变快照，并支持差异查看与回滚。
-- 真实运行值仍写入 system_config；发布记录只负责审计和恢复，不改变现有热配置读取方式。

CREATE TABLE IF NOT EXISTS ai_prompt_release (
    id                  BIGSERIAL PRIMARY KEY,
    action              VARCHAR(20) NOT NULL,
    source_release_id   BIGINT REFERENCES ai_prompt_release(id),
    snapshot            JSONB NOT NULL,
    changed_keys        JSONB NOT NULL DEFAULT '[]'::jsonb,
    created_by          VARCHAR(100) NOT NULL,
    create_time         TIMESTAMP NOT NULL DEFAULT now(),
    CONSTRAINT ck_ai_prompt_release_action
        CHECK (action IN ('BASELINE', 'PUBLISH', 'ROLLBACK'))
);

CREATE INDEX IF NOT EXISTS idx_ai_prompt_release_time
    ON ai_prompt_release(create_time DESC);

-- 单行状态表使“检查基础版本 → 发布 → 切换当前版本”可以在一个事务中串行完成。
CREATE TABLE IF NOT EXISTS ai_prompt_release_state (
    singleton_id        SMALLINT PRIMARY KEY DEFAULT 1,
    current_release_id  BIGINT REFERENCES ai_prompt_release(id),
    update_time         TIMESTAMP NOT NULL DEFAULT now(),
    CONSTRAINT ck_ai_prompt_release_state_singleton CHECK (singleton_id = 1)
);

INSERT INTO ai_prompt_release_state(singleton_id, current_release_id)
VALUES (1, NULL)
ON CONFLICT (singleton_id) DO NOTHING;

CREATE TABLE IF NOT EXISTS ai_prompt_draft (
    owner               VARCHAR(100) PRIMARY KEY,
    base_release_id     BIGINT NOT NULL REFERENCES ai_prompt_release(id),
    snapshot            JSONB NOT NULL,
    update_time         TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_ai_prompt_draft_update_time
    ON ai_prompt_draft(update_time DESC);
