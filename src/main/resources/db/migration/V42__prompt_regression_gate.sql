-- Prompt 发布回归门禁：测试结果与草稿内容及启用用例集同时绑定。
-- 草稿或回归用例发生任何变化后，旧的通过记录自动失效。

CREATE TABLE IF NOT EXISTS ai_prompt_regression_gate_run (
    id                  BIGSERIAL PRIMARY KEY,
    requested_by        VARCHAR(100) NOT NULL,
    base_release_id     BIGINT NOT NULL REFERENCES ai_prompt_release(id),
    draft_hash          VARCHAR(64) NOT NULL,
    case_set_hash       VARCHAR(64) NOT NULL,
    status              VARCHAR(20) NOT NULL DEFAULT 'queued',
    requested_count     INT NOT NULL DEFAULT 0,
    processed_count     INT NOT NULL DEFAULT 0,
    passed_count        INT NOT NULL DEFAULT 0,
    failed_count        INT NOT NULL DEFAULT 0,
    current_case_id     BIGINT,
    failures_json       JSONB NOT NULL DEFAULT '[]'::jsonb,
    error_message       VARCHAR(1000),
    create_time         TIMESTAMP NOT NULL DEFAULT now(),
    start_time          TIMESTAMP,
    finish_time         TIMESTAMP,
    CONSTRAINT ck_prompt_gate_status
        CHECK (status IN ('queued', 'running', 'passed', 'failed')),
    CONSTRAINT ck_prompt_gate_counts
        CHECK (requested_count >= 0 AND processed_count >= 0
            AND passed_count >= 0 AND failed_count >= 0)
);

CREATE INDEX IF NOT EXISTS idx_prompt_gate_owner_time
    ON ai_prompt_regression_gate_run(requested_by, id DESC);

CREATE INDEX IF NOT EXISTS idx_prompt_gate_publish_proof
    ON ai_prompt_regression_gate_run(
        requested_by, base_release_id, draft_hash, case_set_hash, status, finish_time DESC);
