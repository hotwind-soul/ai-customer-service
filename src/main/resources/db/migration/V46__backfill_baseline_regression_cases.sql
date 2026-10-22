-- 修复 V8 的种子判重缺陷。
--
-- V8 用「整表是否为空」判重（WHERE NOT EXISTS (SELECT 1 FROM ai_regression_case)），
-- 导致同一个版本在不同环境表现不一致：
--   全新库 → 得到 8 条基线用例（随后 V37 为它们补齐回答要点断言）
--   升级库 → 只要已存在任意 1 条用例（运营手工建过），这 8 条基线一条都不会插入
--
-- 这里按 V17/V18/V19 的「名称 + 消息逐条判重」口径补齐缺失的基线用例。
-- expected_answer_points 直接带上 V37 会补的断言值（V37 执行在先，不会再为这些行补值），
-- 保证升级库与全新库最终得到的断言完全一致，避免出现“路由对、内容错”的假通过。

INSERT INTO ai_regression_case(
    name, message, expected_reply_type, expected_answer_points, enabled, created_by
)
SELECT seed.name, seed.message, seed.expected_reply_type, seed.points, TRUE, 'system'
FROM (VALUES
    ('结构化价格', '抗衰单部位多少钱？', 'FACT', '298元；8元|订金八元'),
    ('预约意图', '我要预约抗衰', 'AGENT', '手机号|联系方式|称呼'),
    ('明确转人工', '我要转人工', 'AGENT', '人工|客服'),
    ('辱骂安全响应', '你这个垃圾客服，什么都不会', 'SAFETY', '项目|哪一项'),
    ('模糊价格', '多少钱？', 'CLARIFY', '价格|效果|流程|恢复'),
    ('项目定向追问', '淡斑', 'CLARIFY', '淡斑'),
    ('详细活动介绍', '请详细介绍一下淡斑活动', 'INTRO', '淡斑'),
    ('库外问题', '帮我写一首诗', 'REFUSAL', '服务范围|无法|人工')
) AS seed(name, message, expected_reply_type, points)
WHERE NOT EXISTS (
    SELECT 1 FROM ai_regression_case existing
    WHERE existing.name = seed.name
      AND existing.message = seed.message
);
