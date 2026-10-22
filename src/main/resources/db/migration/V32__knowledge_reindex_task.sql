CREATE TABLE IF NOT EXISTS knowledge_reindex_task (
    id BIGSERIAL PRIMARY KEY,
    status VARCHAR(20) NOT NULL DEFAULT 'queued',
    total_chunks INTEGER NOT NULL DEFAULT 0,
    processed_chunks INTEGER NOT NULL DEFAULT 0,
    error TEXT,
    requested_by VARCHAR(100),
    create_time TIMESTAMP NOT NULL DEFAULT now(),
    start_time TIMESTAMP,
    finish_time TIMESTAMP,
    CONSTRAINT ck_knowledge_reindex_task_status
        CHECK (status IN ('queued', 'embedding', 'switching', 'done', 'failed'))
);

CREATE INDEX IF NOT EXISTS idx_knowledge_reindex_task_status
    ON knowledge_reindex_task(status, create_time DESC);
