ALTER TABLE knowledge_chunk
    ADD COLUMN IF NOT EXISTS governance_reason TEXT;

-- FAQ 的价格问法属于动态事实：保留原文供后台审计，但不再发布或保存向量。
UPDATE knowledge_chunk
SET status='disabled',
    risk_level='high',
    safe_direct=false,
    embedding=NULL,
    embedding_strategy='dynamic-price-no-vector-v1',
    governance_reason='包含价格、订金或尾款等动态数据，请在项目中心维护并实时读取',
    update_time=now()
WHERE knowledge_type='faq'
  AND content ~ '^问：.*(价格|多少钱|价钱|收费|费用|活动价|优惠价|原价|订金|定金|尾款|到店再付|补款|加价|便宜|优惠|折扣|块钱)';

-- 其他文档若同时保存具体金额和价格语义，同样退出检索，避免改价后留下旧金额。
UPDATE knowledge_chunk
SET status='disabled',
    risk_level='high',
    safe_direct=false,
    embedding=NULL,
    embedding_strategy='dynamic-price-no-vector-v1',
    governance_reason='包含价格、订金或尾款等动态数据，请在项目中心维护并实时读取',
    update_time=now()
WHERE knowledge_type<>'offer'
  AND content ~ '[0-9]+([.][0-9]{1,2})?[[:space:]]*(元|块钱|块)'
  AND content ~ '(价格|多少钱|价钱|收费|费用|活动价|优惠价|原价|订金|定金|尾款|到店再付|补款|加价|便宜|优惠|折扣|块钱)';

CREATE INDEX IF NOT EXISTS idx_knowledge_chunk_governance
    ON knowledge_chunk(status, embedding_strategy)
    WHERE governance_reason IS NOT NULL;
