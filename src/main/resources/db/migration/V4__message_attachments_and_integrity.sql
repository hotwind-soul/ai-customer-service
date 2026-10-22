-- 消息附件持久化：AI 素材图和客户聊天图均绑定具体消息，历史恢复时可长期查看。
CREATE TABLE IF NOT EXISTS message_attachment (
    id              BIGSERIAL PRIMARY KEY,
    message_id      BIGINT NOT NULL REFERENCES message(id) ON DELETE CASCADE,
    attachment_type VARCHAR(20) NOT NULL DEFAULT 'image',
    name            VARCHAR(255),
    url             VARCHAR(500) NOT NULL,
    asset_id        BIGINT REFERENCES image_asset(id) ON DELETE SET NULL,
    create_time     TIMESTAMP NOT NULL DEFAULT now(),
    CONSTRAINT chk_message_attachment_url CHECK (url LIKE '/uploads/images/%')
);
CREATE INDEX IF NOT EXISTS idx_message_attachment_message
    ON message_attachment(message_id, id);

-- 清理历史孤立行后补齐会话外键，避免今后再次产生无法归属的数据。
DELETE FROM message m
WHERE NOT EXISTS (SELECT 1 FROM conversation c WHERE c.conversation_id = m.conversation_id);
DELETE FROM leave_message l
WHERE l.conversation_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM conversation c WHERE c.conversation_id = l.conversation_id);

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_message_conversation') THEN
        ALTER TABLE message ADD CONSTRAINT fk_message_conversation
            FOREIGN KEY (conversation_id) REFERENCES conversation(conversation_id) ON DELETE CASCADE;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_leave_message_conversation') THEN
        ALTER TABLE leave_message ADD CONSTRAINT fk_leave_message_conversation
            FOREIGN KEY (conversation_id) REFERENCES conversation(conversation_id) ON DELETE CASCADE;
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_conversation_user_channel_status_time
    ON conversation(user_id, channel, status, update_time DESC);
CREATE INDEX IF NOT EXISTS idx_message_conv_time
    ON message(conversation_id, create_time, id);
CREATE INDEX IF NOT EXISTS idx_leave_message_status_time
    ON leave_message(status, create_time DESC);
