-- 记录每次 AI 对话质量复核明确选择的模型，便于评分来源追溯。
ALTER TABLE ai_conversation_evaluation
    ADD COLUMN IF NOT EXISTS judge_provider VARCHAR(20),
    ADD COLUMN IF NOT EXISTS judge_model VARCHAR(100);

ALTER TABLE ai_conversation_evaluation
    DROP CONSTRAINT IF EXISTS chk_ai_eval_judge_provider;

ALTER TABLE ai_conversation_evaluation
    ADD CONSTRAINT chk_ai_eval_judge_provider
        CHECK (judge_provider IS NULL OR judge_provider IN ('primary', 'fallback'));
