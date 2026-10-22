-- 记录最近一次 AI 语义复核失败的安全诊断信息。
-- 成功评分仍保存在原字段中；失败重试不会再覆盖上一次有效语义分或人工复核结果。
ALTER TABLE ai_conversation_evaluation
    ADD COLUMN IF NOT EXISTS judge_error_code VARCHAR(50),
    ADD COLUMN IF NOT EXISTS judge_error_message VARCHAR(1000),
    ADD COLUMN IF NOT EXISTS judge_error_provider VARCHAR(20),
    ADD COLUMN IF NOT EXISTS judge_error_model VARCHAR(160),
    ADD COLUMN IF NOT EXISTS judge_error_at TIMESTAMP;
