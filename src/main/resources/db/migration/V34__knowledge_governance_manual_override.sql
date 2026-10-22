ALTER TABLE knowledge_chunk
    ADD COLUMN IF NOT EXISTS governance_override BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN knowledge_chunk.governance_override IS
    '管理员明确接受治理风险并人工启用；内容再次编辑时自动撤销';
