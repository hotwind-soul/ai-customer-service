ALTER TABLE message
    ADD COLUMN IF NOT EXISTS decision_trace JSONB;

COMMENT ON COLUMN message.decision_trace IS
    '不含用户原文的 harness 路由追踪：路由、权威来源、召回数和处理耗时';

CREATE INDEX IF NOT EXISTS idx_message_decision_trace_route
    ON message ((decision_trace->>'route'))
    WHERE decision_trace IS NOT NULL;
