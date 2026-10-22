-- 知识库与项目中心一致性治理：项目服务范围结构化，历史报价留档去除危险话术，
-- 停用文档与切片状态对齐，并清理不会参与检索的旧向量。

ALTER TABLE service_project
    ADD COLUMN IF NOT EXISTS service_scope TEXT;

-- price.xlsx 仅保留结构化数字留档。客户话术仍以 project_offer 为唯一权威来源；
-- 即使兼容读取被触发，也不会再读到限量、接车、恢复原价或退款承诺。
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
SET content = '### ' || s.name || E'\n'
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
WHERE lower(k.doc_name)=lower('price.xlsx') AND k.project_id=s.project_id;

-- 文档已停用或标记为内部资料时，切片状态也同步停用，避免后台出现互相矛盾的状态。
UPDATE knowledge_chunk k
SET status='disabled', safe_direct=false, update_time=now()
FROM knowledge_document d
WHERE d.id=k.document_id AND (d.status='disabled' OR d.doc_type='internal')
  AND k.status<>'disabled';

-- 停用知识不保留可检索向量；重新发布时由应用重新生成当前模型向量。
UPDATE knowledge_chunk
SET embedding=NULL, embedding_strategy='disabled-no-vector-v1', update_time=now()
WHERE status<>'published' AND embedding IS NOT NULL;

-- 现有唯一缺少区县的门店按地址中的县级市补齐，便于后台按“市 / 区县”筛选。
UPDATE store
SET district='西昌市'
WHERE city='西昌' AND (district IS NULL OR btrim(district)='')
  AND name='四川西昌店' AND address LIKE '西昌市%';
