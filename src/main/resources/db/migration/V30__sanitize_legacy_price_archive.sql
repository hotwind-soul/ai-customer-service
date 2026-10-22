-- V29 发现部分历史 price.xlsx 切片尚未回填 project_id；按标准项目名补齐并完成安全清洗。
WITH safe_offer AS (
    SELECT o.project_id,
           p.name,
           o.activity_price,
           o.original_price,
           o.deposit,
           p.name || '当前活动价' || trim_scale(o.activity_price)::text || '元'
               || CASE WHEN o.original_price IS NOT NULL AND o.original_price > o.activity_price
                       THEN '（原价' || trim_scale(o.original_price)::text || '元）' ELSE '' END
               || CASE WHEN o.deposit IS NOT NULL AND o.deposit > 0
                       THEN '，' || trim_scale(o.deposit)::text || '元订金可抵扣，到店再付'
                            || trim_scale(o.activity_price - o.deposit)::text || '元' ELSE '' END || '。' AS brief_copy,
           p.name || '当前活动价' || trim_scale(o.activity_price)::text || '元'
               || CASE WHEN o.original_price IS NOT NULL AND o.original_price > o.activity_price
                       THEN '（原价' || trim_scale(o.original_price)::text || '元）' ELSE '' END
               || CASE WHEN o.deposit IS NOT NULL AND o.deposit > 0
                       THEN '，' || trim_scale(o.deposit)::text || '元订金可抵扣，到店再付'
                            || trim_scale(o.activity_price - o.deposit)::text || '元' ELSE '' END
               || '。具体服务范围、适用性和支持门店分别以项目中心、专业评估和门店资料为准。' AS sale_copy
    FROM project_offer o
    JOIN service_project p ON p.id=o.project_id
    WHERE o.offer_key='default' AND o.status='published' AND o.activity_price IS NOT NULL
)
UPDATE knowledge_chunk k
SET project_id = s.project_id,
    project = s.name,
    content = '### ' || s.name || E'\n'
        || '- project_key：' || s.name || E'\n'
        || '- activity_price：' || trim_scale(s.activity_price)::text || E'\n'
        || '- original_price：' || COALESCE(trim_scale(s.original_price)::text, '') || E'\n'
        || '- deposit：' || COALESCE(trim_scale(s.deposit)::text, '') || E'\n'
        || '- sale_copy：' || s.sale_copy || E'\n'
        || '- brief_copy：' || s.brief_copy,
    search_text = s.name || ' 活动价 ' || trim_scale(s.activity_price)::text || '元 原价 '
        || COALESCE(trim_scale(s.original_price)::text, '') || '元 订金 '
        || COALESCE(trim_scale(s.deposit)::text, '') || '元',
    embedding = NULL,
    embedding_strategy = 'structured-offer-v1',
    knowledge_type = 'offer',
    safe_direct = false,
    update_time = now()
FROM safe_offer s
WHERE lower(k.doc_name)=lower('price.xlsx')
  AND (k.project_id=s.project_id OR k.project=s.name
       OR k.content ~ ('(?m)^\\s*-\\s*project_key\\s*[：:]\\s*' || s.name || '\\s*$'));
