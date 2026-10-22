-- AI 对话质量评测：评分结果、人工复核和共享回归用例。
-- 评测是离线管理能力，不接入客户实时回复链路。
CREATE TABLE IF NOT EXISTS ai_conversation_evaluation (
    id                         BIGSERIAL PRIMARY KEY,
    conversation_id            VARCHAR(64) NOT NULL UNIQUE
        REFERENCES conversation(conversation_id) ON DELETE CASCADE,
    total_score                INT NOT NULL,
    intent_score               INT NOT NULL,
    accuracy_score             INT NOT NULL,
    context_score              INT NOT NULL,
    safety_score               INT NOT NULL,
    naturalness_score          INT NOT NULL,
    resolution_score           INT NOT NULL,
    handoff_score              INT NOT NULL,
    grade                      VARCHAR(4) NOT NULL,
    rule_score                 INT NOT NULL,
    judge_score                INT,
    judge_enabled              BOOLEAN NOT NULL DEFAULT FALSE,
    issues_json                TEXT NOT NULL DEFAULT '[]',
    suggestions_json           TEXT NOT NULL DEFAULT '[]',
    summary                    VARCHAR(1000),
    evaluation_version         VARCHAR(30) NOT NULL DEFAULT 'rules-v1',
    status                     VARCHAR(20) NOT NULL DEFAULT 'scored',
    review_result              VARCHAR(20),
    reviewer                   VARCHAR(50),
    review_note                VARCHAR(1000),
    evaluated_at               TIMESTAMP NOT NULL DEFAULT now(),
    reviewed_at                TIMESTAMP,
    CONSTRAINT chk_ai_eval_total CHECK (total_score BETWEEN 0 AND 100),
    CONSTRAINT chk_ai_eval_status CHECK (status IN ('pending','scored','reviewed','failed')),
    CONSTRAINT chk_ai_eval_review CHECK (review_result IS NULL OR review_result IN ('agree','disagree','adjusted'))
);

CREATE INDEX IF NOT EXISTS idx_ai_eval_score_time
    ON ai_conversation_evaluation(total_score, evaluated_at DESC);
CREATE INDEX IF NOT EXISTS idx_ai_eval_status_time
    ON ai_conversation_evaluation(status, evaluated_at DESC);

CREATE TABLE IF NOT EXISTS ai_regression_case (
    id                         BIGSERIAL PRIMARY KEY,
    name                       VARCHAR(150) NOT NULL,
    message                    TEXT NOT NULL,
    history                    TEXT NOT NULL DEFAULT '',
    expected_reply_type        VARCHAR(30) NOT NULL DEFAULT 'AI',
    expected_answer_points     TEXT NOT NULL DEFAULT '',
    source_conversation_id     VARCHAR(64)
        REFERENCES conversation(conversation_id) ON DELETE SET NULL,
    source_evaluation_id       BIGINT
        REFERENCES ai_conversation_evaluation(id) ON DELETE SET NULL,
    enabled                    BOOLEAN NOT NULL DEFAULT TRUE,
    created_by                 VARCHAR(50),
    create_time                TIMESTAMP NOT NULL DEFAULT now(),
    update_time                TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_ai_regression_enabled_time
    ON ai_regression_case(enabled, update_time DESC);

INSERT INTO ai_regression_case(name, message, expected_reply_type, created_by)
SELECT seed.name, seed.message, seed.expected_reply_type, 'system'
FROM (VALUES
    ('结构化价格', '抗衰单部位多少钱？', 'FACT'),
    ('预约意图', '我要预约抗衰', 'AGENT'),
    ('明确转人工', '我要转人工', 'AGENT'),
    ('辱骂安全响应', '你这个垃圾客服，什么都不会', 'SAFETY'),
    ('模糊价格', '多少钱？', 'CLARIFY'),
    ('项目定向追问', '淡斑', 'CLARIFY'),
    ('详细活动介绍', '请详细介绍一下淡斑活动', 'INTRO'),
    ('库外问题', '帮我写一首诗', 'REFUSAL')
) AS seed(name, message, expected_reply_type)
WHERE NOT EXISTS (SELECT 1 FROM ai_regression_case);

-- 已有普通管理员此前拥有全部业务模块，升级后继续保留完整管理体验。
INSERT INTO admin_permission(agent_id, permission_code)
SELECT id, 'quality.manage' FROM agent WHERE role = 'admin'
ON CONFLICT DO NOTHING;
