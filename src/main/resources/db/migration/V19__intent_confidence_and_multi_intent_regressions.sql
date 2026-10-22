-- 将真实 B 级误解案例与高风险多意图表达加入共享回归库。
-- 动作 + 问题的复合表达必须先回答问题；不确定的动作只能澄清，不能产生副作用。
INSERT INTO ai_regression_case(
    name, message, history, expected_reply_type, expected_answer_points, enabled, created_by
)
SELECT seed.name, seed.message, seed.history, seed.expected_reply_type,
       seed.expected_answer_points, TRUE, 'system'
FROM (VALUES
    ('抗衰操作方式不重复问项目', '做抗衰是打针还是手术？', '', 'AI', '抗衰；仪器；不重复询问项目'),
    ('抗衰疼痛麻药不重复问项目', '做抗衰痛吗？要打麻药吗？', '', 'SAFETY', '疼痛感受因人而异；专业人员确认；不重复询问项目'),
    ('胡子原理不重复问项目', '胡子去除是什么原理？', '', 'AI', '胡子去除；原理；不重复询问项目'),
    ('预约加价格复合意图', '我想预约抗衰，多少钱？', '', 'FACT', '抗衰价格；尚未登记；确认预约'),
    ('议价后仍想预约', '太贵了但我还是想预约', '', 'AGENT', '不能擅自承诺额外优惠；确认预约'),
    ('投诉加退款复合意图', '我要投诉，顺便问退款', '', 'AGENT', '退款按规则和订单状态核实；转人工'),
    ('预约收费不是预约动作', '预约要钱吗？', '', 'FACT', '不会自动扣款；确认规则后主动支付'),
    ('模糊客服诉求先澄清', '客服能处理这个吗？', '', 'CLARIFY', '确认是否需要转人工；不自动转接')
) AS seed(name, message, history, expected_reply_type, expected_answer_points)
WHERE NOT EXISTS (
    SELECT 1
    FROM ai_regression_case existing
    WHERE existing.name = seed.name
      AND existing.message = seed.message
);
