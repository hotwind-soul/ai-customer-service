-- 截图回归：预约流程中“会不会动钱”是扣费追问，不是拒绝留手机号。
INSERT INTO ai_regression_case(
    name, message, history, expected_reply_type, expected_answer_points, enabled, created_by
)
SELECT '预约是否自动扣款', '会不会动钱',
       '用户：预约\n客服：方便留一下您的称呼和手机号吗？',
       'FACT', '不会自动扣款；确认金额和规则后主动支付', TRUE, 'system'
WHERE NOT EXISTS (
    SELECT 1 FROM ai_regression_case
    WHERE name = '预约是否自动扣款' AND message = '会不会动钱'
);
