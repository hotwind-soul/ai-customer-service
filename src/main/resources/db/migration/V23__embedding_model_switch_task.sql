-- 向量模型安全切换任务：候选模型先在后台完成全量重建，成功后才原子发布配置与向量。
-- 表中不保存 API Key；密钥仍只进入加密的 system_config。
CREATE TABLE IF NOT EXISTS embedding_model_switch_task (
    id                BIGSERIAL PRIMARY KEY,
    base_url          VARCHAR(500) NOT NULL,
    model_name        VARCHAR(200) NOT NULL,
    status            VARCHAR(20) NOT NULL DEFAULT 'queued',
    total_chunks      INT NOT NULL DEFAULT 0,
    processed_chunks  INT NOT NULL DEFAULT 0,
    error             VARCHAR(1000),
    requested_by      VARCHAR(100),
    create_time       TIMESTAMP NOT NULL DEFAULT now(),
    start_time        TIMESTAMP,
    finish_time       TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_embedding_model_switch_task_status
    ON embedding_model_switch_task(status, create_time);

-- 应用异常退出后，内存任务无法继续；启动后的首次提交会将这些任务标记为失败。
