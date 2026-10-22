-- 质量评测 V2：当前快照仍保留在 ai_conversation_evaluation，兼容现有报表；
-- 每次评测尝试和批量任务单独留痕，不再因 upsert 丢失历史。
CREATE TABLE IF NOT EXISTS ai_quality_batch_task (
    id                         BIGSERIAL PRIMARY KEY,
    status                     VARCHAR(20) NOT NULL DEFAULT 'queued',
    judge_provider             VARCHAR(20) NOT NULL,
    judge_model                VARCHAR(160),
    requested_count            INT NOT NULL DEFAULT 0,
    processed_count            INT NOT NULL DEFAULT 0,
    succeeded_count            INT NOT NULL DEFAULT 0,
    failed_count               INT NOT NULL DEFAULT 0,
    current_conversation_id    VARCHAR(64),
    conversation_ids_json      TEXT NOT NULL DEFAULT '[]',
    failures_json              TEXT NOT NULL DEFAULT '[]',
    requested_by               VARCHAR(50),
    error_code                 VARCHAR(50),
    error_message              VARCHAR(1000),
    create_time                TIMESTAMP NOT NULL DEFAULT now(),
    start_time                 TIMESTAMP,
    finish_time                TIMESTAMP,
    CONSTRAINT chk_quality_batch_status
        CHECK (status IN ('queued','running','succeeded','partial','failed')),
    CONSTRAINT chk_quality_batch_provider
        CHECK (judge_provider IN ('rules','primary','fallback','eval')),
    CONSTRAINT chk_quality_batch_counts
        CHECK (requested_count >= 0 AND processed_count >= 0
            AND succeeded_count >= 0 AND failed_count >= 0)
);

CREATE INDEX IF NOT EXISTS idx_quality_batch_status_time
    ON ai_quality_batch_task(status, create_time DESC);
CREATE INDEX IF NOT EXISTS idx_quality_batch_actor_time
    ON ai_quality_batch_task(requested_by, create_time DESC);

CREATE TABLE IF NOT EXISTS ai_quality_evaluation_attempt (
    id                         BIGSERIAL PRIMARY KEY,
    conversation_id            VARCHAR(64) NOT NULL
        REFERENCES conversation(conversation_id) ON DELETE CASCADE,
    evaluation_id              BIGINT
        REFERENCES ai_conversation_evaluation(id) ON DELETE SET NULL,
    batch_task_id              BIGINT
        REFERENCES ai_quality_batch_task(id) ON DELETE SET NULL,
    status                     VARCHAR(20) NOT NULL DEFAULT 'pending',
    judge_provider             VARCHAR(20) NOT NULL,
    judge_model                VARCHAR(160),
    judge_temperature          NUMERIC(3,2),
    prompt_version             VARCHAR(100) NOT NULL,
    evaluation_version         VARCHAR(30),
    total_score                INT,
    intent_score               INT,
    accuracy_score             INT,
    context_score              INT,
    safety_score               INT,
    naturalness_score          INT,
    resolution_score           INT,
    handoff_score              INT,
    grade                      VARCHAR(4),
    rule_score                 INT,
    judge_score                INT,
    issues_json                TEXT NOT NULL DEFAULT '[]',
    suggestions_json           TEXT NOT NULL DEFAULT '[]',
    summary                    VARCHAR(1000),
    error_code                 VARCHAR(50),
    error_message              VARCHAR(1000),
    requested_by               VARCHAR(50),
    duration_ms                BIGINT,
    create_time                TIMESTAMP NOT NULL DEFAULT now(),
    start_time                 TIMESTAMP,
    finish_time                TIMESTAMP,
    CONSTRAINT chk_quality_attempt_status
        CHECK (status IN ('pending','running','succeeded','failed')),
    CONSTRAINT chk_quality_attempt_provider
        CHECK (judge_provider IN ('rules','primary','fallback','eval')),
    CONSTRAINT chk_quality_attempt_total
        CHECK (total_score IS NULL OR total_score BETWEEN 0 AND 100),
    CONSTRAINT chk_quality_attempt_duration
        CHECK (duration_ms IS NULL OR duration_ms >= 0)
);

CREATE INDEX IF NOT EXISTS idx_quality_attempt_conversation_time
    ON ai_quality_evaluation_attempt(conversation_id, create_time DESC, id DESC);
CREATE INDEX IF NOT EXISTS idx_quality_attempt_batch
    ON ai_quality_evaluation_attempt(batch_task_id, id);
CREATE INDEX IF NOT EXISTS idx_quality_attempt_status_time
    ON ai_quality_evaluation_attempt(status, create_time DESC);

-- 现有快照回填为首个历史版本。若快照同时保留了最近一次失败，再补一条失败尝试。
INSERT INTO ai_quality_evaluation_attempt(
    conversation_id,evaluation_id,status,judge_provider,judge_model,prompt_version,evaluation_version,
    total_score,intent_score,accuracy_score,context_score,safety_score,naturalness_score,resolution_score,
    handoff_score,grade,rule_score,judge_score,issues_json,suggestions_json,summary,requested_by,
    duration_ms,create_time,start_time,finish_time)
SELECT e.conversation_id,e.id,'succeeded',COALESCE(e.judge_provider,'rules'),e.judge_model,
       e.evaluation_version,e.evaluation_version,e.total_score,e.intent_score,e.accuracy_score,e.context_score,
       e.safety_score,e.naturalness_score,e.resolution_score,e.handoff_score,e.grade,e.rule_score,e.judge_score,
       e.issues_json,e.suggestions_json,e.summary,'migration',0,e.evaluated_at,e.evaluated_at,e.evaluated_at
FROM ai_conversation_evaluation e
WHERE NOT EXISTS (
    SELECT 1 FROM ai_quality_evaluation_attempt a
    WHERE a.evaluation_id=e.id AND a.requested_by='migration' AND a.status='succeeded'
);

INSERT INTO ai_quality_evaluation_attempt(
    conversation_id,evaluation_id,status,judge_provider,judge_model,prompt_version,evaluation_version,
    error_code,error_message,requested_by,duration_ms,create_time,start_time,finish_time)
SELECT e.conversation_id,e.id,'failed',COALESCE(e.judge_error_provider,e.judge_provider,'rules'),
       COALESCE(e.judge_error_model,e.judge_model),e.evaluation_version,e.evaluation_version,
       e.judge_error_code,e.judge_error_message,'migration',0,
       COALESCE(e.judge_error_at,e.evaluated_at),COALESCE(e.judge_error_at,e.evaluated_at),
       COALESCE(e.judge_error_at,e.evaluated_at)
FROM ai_conversation_evaluation e
WHERE e.judge_error_code IS NOT NULL
  AND NOT EXISTS (
      SELECT 1 FROM ai_quality_evaluation_attempt a
      WHERE a.evaluation_id=e.id AND a.requested_by='migration' AND a.status='failed'
  );
