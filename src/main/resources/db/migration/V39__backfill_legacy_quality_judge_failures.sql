-- 旧版本只保存“语义复核失败”布尔状态，没有保留具体异常。
-- 明确标记为历史未知，避免页面把它误解成当前模型持续故障；重新评测后会写入准确分类。
UPDATE ai_conversation_evaluation
SET judge_error_code = 'legacy_unknown',
    judge_error_message = '该失败记录由旧版本产生，当时未保存具体原因；请重新评测以获得准确诊断',
    judge_error_provider = judge_provider,
    judge_error_model = judge_model,
    judge_error_at = evaluated_at
WHERE judge_provider IS NOT NULL
  AND judge_enabled = FALSE
  AND judge_error_code IS NULL;
