-- 放开 ai_model_provider.role 的 CHECK 约束，允许新增的 "eval"（评测专用）角色。
-- 背景：V13 定义的 chk_ai_model_provider_role 仅含 ('primary','fallback','candidate')。
-- 后续为接入「第三个评测模型」新增了 eval 角色（AiModelProviderService.ROLE_EVAL），
-- 但当时只放宽了 ai_quality_evaluation.judge_provider 的约束（V14），
-- 漏掉了本表 role 列的 CHECK。结果：在模型管理新增「评测专用」模型时，
-- create() 先插 candidate 再 assignRole(id,'eval') 改成 eval，触发 CHECK 失败、事务回滚、
-- 后端 500、前端显示「操作失败，服务器开小差了」。此处补齐。
ALTER TABLE ai_model_provider DROP CONSTRAINT IF EXISTS chk_ai_model_provider_role;
ALTER TABLE ai_model_provider ADD CONSTRAINT chk_ai_model_provider_role
    CHECK (role IN ('primary', 'fallback', 'candidate', 'eval'));
