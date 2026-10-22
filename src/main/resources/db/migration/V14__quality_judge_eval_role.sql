-- 支持“评测专用(eval)”作为第三个质量复核模型来源，扩展 judge_provider 取值约束。
ALTER TABLE ai_conversation_evaluation
    DROP CONSTRAINT IF EXISTS chk_ai_eval_judge_provider;

ALTER TABLE ai_conversation_evaluation
    ADD CONSTRAINT chk_ai_eval_judge_provider
        CHECK (judge_provider IS NULL OR judge_provider IN ('primary', 'fallback', 'eval'));
