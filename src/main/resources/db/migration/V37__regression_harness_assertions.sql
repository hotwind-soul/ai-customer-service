-- Harness 回归断言扩展：回答要点之外，支持禁止内容、权威来源和模型调用约束。
-- 字段均向后兼容；NULL expected_model_invoked 表示该用例不检查模型调用。
ALTER TABLE ai_regression_case
    ADD COLUMN IF NOT EXISTS forbidden_answer_points TEXT NOT NULL DEFAULT '',
    ADD COLUMN IF NOT EXISTS expected_authority_domain VARCHAR(80) NOT NULL DEFAULT '',
    ADD COLUMN IF NOT EXISTS expected_authority_source VARCHAR(200) NOT NULL DEFAULT '',
    ADD COLUMN IF NOT EXISTS expected_model_invoked BOOLEAN;

-- 最早的种子用例只有路由断言。补上稳定的关键字断言，避免“路由对、内容错”假通过。
UPDATE ai_regression_case SET expected_answer_points = '298元；8元|订金八元'
WHERE name = '结构化价格' AND message = '抗衰单部位多少钱？' AND expected_answer_points = '';

UPDATE ai_regression_case SET expected_answer_points = '手机号|联系方式|称呼'
WHERE name = '预约意图' AND message = '我要预约抗衰' AND expected_answer_points = '';

UPDATE ai_regression_case SET expected_answer_points = '人工|客服'
WHERE name = '明确转人工' AND message = '我要转人工' AND expected_answer_points = '';

UPDATE ai_regression_case SET expected_answer_points = '具体问题|帮助处理'
WHERE name = '辱骂安全响应' AND message = '你这个垃圾客服，什么都不会' AND expected_answer_points = '';

UPDATE ai_regression_case SET expected_answer_points = '项目|哪一项'
WHERE name = '模糊价格' AND message = '多少钱？' AND expected_answer_points = '';

UPDATE ai_regression_case SET expected_answer_points = '价格|效果|流程|恢复'
WHERE name = '项目定向追问' AND message = '淡斑' AND expected_answer_points = '';

UPDATE ai_regression_case SET expected_answer_points = '淡斑'
WHERE name = '详细活动介绍' AND message = '请详细介绍一下淡斑活动' AND expected_answer_points = '';

UPDATE ai_regression_case SET expected_answer_points = '服务范围|无法|人工'
WHERE name = '库外问题' AND message = '帮我写一首诗' AND expected_answer_points = '';
