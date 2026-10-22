-- 记录企微回调实际使用的客服账号，后续人工/主动回复可以沿原账号发送。
ALTER TABLE conversation ADD COLUMN IF NOT EXISTS open_kfid VARCHAR(128);

CREATE INDEX IF NOT EXISTS idx_conversation_kf_identity
    ON conversation(external_userid, open_kfid, update_time DESC)
    WHERE channel = 'kf';
