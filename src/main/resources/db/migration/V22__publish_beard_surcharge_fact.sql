-- 络腮胡属于胡子项目的同价范围。该补充说明作为可审核、可停用的业务事实发布，
-- 与 price.xlsx 的当前价格组合回答，避免把运营规则硬编码进价格计算逻辑。
INSERT INTO business_fact(
    tenant_id, kf_account_id, fact_key, project, fact_type,
    display_text, status, source, reviewer, update_time
)
VALUES (
    NULL, NULL, 'price.hair.beardSurcharge', '胡子', 'price_detail',
    '络腮胡按同一活动价，不另外加钱。', 'published',
    '已确认的胡子项目计价规则', 'system-migration', now()
)
ON CONFLICT (tenant_id, kf_account_id, fact_key) DO UPDATE SET
    project = EXCLUDED.project,
    fact_type = EXCLUDED.fact_type,
    display_text = EXCLUDED.display_text,
    status = EXCLUDED.status,
    source = EXCLUDED.source,
    reviewer = EXCLUDED.reviewer,
    valid_from = NULL,
    valid_until = NULL,
    update_time = now();
