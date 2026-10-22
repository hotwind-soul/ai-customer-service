-- 记录每个会话已经真正发送过的首次项目活动介绍。
-- 不再用“客户历史里是否提到项目”代替发送状态，避免仅说项目名就被当成已介绍。
CREATE TABLE IF NOT EXISTS conversation_project_intro (
    conversation_id VARCHAR(64) NOT NULL
        REFERENCES conversation(conversation_id) ON DELETE CASCADE,
    project         VARCHAR(50) NOT NULL,
    introduced_at   TIMESTAMP NOT NULL DEFAULT now(),
    PRIMARY KEY (conversation_id, project)
);

CREATE INDEX IF NOT EXISTS idx_conversation_project_intro_time
    ON conversation_project_intro(introduced_at DESC);
