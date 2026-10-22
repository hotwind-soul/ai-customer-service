-- 结构化报价与促销图片治理：动态金额保留在项目中心，危险承诺和过期海报退出客户链路。

-- 一、将历史已发布销售话术改为仅由结构化金额生成的中性口径。
UPDATE project_offer o
SET brief_copy = p.name || '当前活动价' || trim_scale(o.activity_price)::text || '元'
        || CASE WHEN o.original_price IS NOT NULL AND o.original_price > o.activity_price
                THEN '（原价' || trim_scale(o.original_price)::text || '元）' ELSE '' END
        || CASE WHEN o.deposit IS NOT NULL AND o.deposit > 0
                THEN '，' || trim_scale(o.deposit)::text || '元订金可抵扣，到店再付'
                     || trim_scale(o.activity_price - o.deposit)::text || '元' ELSE '' END || '。',
    sale_copy = p.name || '当前活动价' || trim_scale(o.activity_price)::text || '元'
        || CASE WHEN o.original_price IS NOT NULL AND o.original_price > o.activity_price
                THEN '（原价' || trim_scale(o.original_price)::text || '元）' ELSE '' END
        || CASE WHEN o.deposit IS NOT NULL AND o.deposit > 0
                THEN '，' || trim_scale(o.deposit)::text || '元订金可抵扣，到店再付'
                     || trim_scale(o.activity_price - o.deposit)::text || '元' ELSE '' END
        || '。具体服务范围、适用性和支持门店分别以项目中心、专业评估和门店资料为准。',
    update_time = now()
FROM service_project p
WHERE p.id=o.project_id AND o.status='published'
  AND COALESCE(o.brief_copy,'') || E'\n' || COALESCE(o.sale_copy,'')
      ~ '(最先进|从根源解决|保证有效|包好|百分百|永不反弹|永久维持|绝对安全|零风险|完全不痛|无需停药|不用停药|无条件退款|锁定名额|锁定优惠|赠送接车|免费接车|限时活动|限量名额|最后名额|仅剩|恢复原价|一律不能享受活动价)';

-- 二、当前 7 张促销图均经人工核对，含名额制造、无需排队、永久优惠、无条件退款、
-- 客户姓名/手机号展示或项目错配。保留原文件供审计，但停用并解除项目关联。
WITH unsafe_image AS (
    SELECT id FROM image_asset
    WHERE asset_type='promo' AND name IN (
        '抗衰优惠活动名额余量展示1', '抗衰优惠活动名额余量展示2',
        '抗衰优惠活动图2-定金8元 活动价290', '抗衰优惠活动图3-定金8元 活动价290',
        '抗衰优惠活动图5-定金8元 活动价290',
        '淡斑优惠活动1-定金8元 活动价198', '眼袋优惠活动1-定金9元 活动价468'
    )
), removed AS (
    DELETE FROM project_asset pa USING unsafe_image u
    WHERE pa.image_id=u.id RETURNING pa.image_id
)
UPDATE image_asset i
SET enabled=false, project=NULL
WHERE i.id IN (SELECT id FROM unsafe_image);
