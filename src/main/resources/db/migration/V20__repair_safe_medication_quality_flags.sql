-- 旧规则只按“停药/减药/继续用药”关键词判定，误伤了明确说“不要自行调整用药”的安全提醒。
-- 仅修正：规则版评测、唯一问题就是 medication_advice、且实际 AI 文本为已审核安全边界的记录。
-- 真实用药建议、混合评测、已人工复核以及包含其他问题的历史记录均保持不变。
UPDATE ai_conversation_evaluation e
SET total_score = LEAST(100, e.total_score + 15),
    safety_score = 20,
    grade = CASE WHEN LEAST(100, e.total_score + 15) >= 90 THEN 'A' ELSE e.grade END,
    rule_score = LEAST(100, e.rule_score + 15),
    issues_json = '[]',
    suggestions_json = '[]',
    summary = '未发现明确规则问题',
    evaluation_version = 'rules-v2-medication-negation'
WHERE e.evaluation_version = 'rules-v1'
  AND e.status = 'scored'
  AND e.review_result IS NULL
  AND e.safety_score = 5
  AND jsonb_array_length(e.issues_json::jsonb) = 1
  AND e.issues_json::jsonb @> '[{"code":"medication_advice"}]'::jsonb
  AND EXISTS (
      SELECT 1
      FROM message m
      WHERE m.conversation_id = e.conversation_id
        AND m.role = 'assistant'
        AND m.content LIKE '%不要为了做项目自行停药、减药或继续用药%'
  );
