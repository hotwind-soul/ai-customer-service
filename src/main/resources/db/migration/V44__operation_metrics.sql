-- 历史状态不能还原转接事件：从本次迁移开始记录，不回填旧会话。
CREATE TABLE report_metric_tracking (
    metric VARCHAR(40) PRIMARY KEY,
    started_at TIMESTAMP NOT NULL DEFAULT clock_timestamp()
);
INSERT INTO report_metric_tracking(metric) VALUES ('handoff');

CREATE TABLE conversation_handoff_event (
    id BIGSERIAL PRIMARY KEY,
    conversation_id VARCHAR(64) NOT NULL REFERENCES conversation(conversation_id) ON DELETE CASCADE,
    entry_status VARCHAR(20) NOT NULL,
    reason VARCHAR(40) NOT NULL DEFAULT 'UNKNOWN',
    create_time TIMESTAMP NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX idx_handoff_event_time ON conversation_handoff_event(create_time, conversation_id);
CREATE INDEX idx_handoff_event_conversation ON conversation_handoff_event(conversation_id, id DESC);

-- 覆盖网页自动分配、排队、人工操作和企微状态同步；排队转接入不重复计数。
CREATE FUNCTION record_conversation_handoff() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.status IN ('need_human', 'human') THEN
        IF TG_OP = 'INSERT' THEN
            INSERT INTO conversation_handoff_event(conversation_id, entry_status)
                VALUES (NEW.conversation_id, NEW.status);
        ELSIF OLD.status NOT IN ('need_human', 'human') THEN
            INSERT INTO conversation_handoff_event(conversation_id, entry_status)
                VALUES (NEW.conversation_id, NEW.status);
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_conversation_handoff
    AFTER INSERT OR UPDATE OF status ON conversation
    FOR EACH ROW EXECUTE FUNCTION record_conversation_handoff();

-- 只写首次成功发送的有效答复；欢迎语、排队提示、发送失败不作为成功样本。
CREATE TABLE ai_response_timing (
    sample_key VARCHAR(256) PRIMARY KEY,
    conversation_id VARCHAR(64) NOT NULL REFERENCES conversation(conversation_id) ON DELETE CASCADE,
    received_at TIMESTAMP NOT NULL,
    first_response_ms BIGINT NOT NULL CHECK (first_response_ms >= 0)
);
CREATE INDEX idx_response_timing_received ON ai_response_timing(received_at, conversation_id);
CREATE INDEX idx_message_user_period ON message(create_time, conversation_id) WHERE role='user';
CREATE INDEX idx_appointment_report_customer ON appointment(user_id, tenant_id, kf_account_id, create_time);
