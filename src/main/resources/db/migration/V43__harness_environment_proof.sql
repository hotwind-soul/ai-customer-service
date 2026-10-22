-- 旧凭证没有环境指纹，迁移后需重新执行门禁。
ALTER TABLE ai_prompt_regression_gate_run
    ADD COLUMN environment_hash VARCHAR(64) NOT NULL DEFAULT '';
