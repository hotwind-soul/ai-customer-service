-- Flyway V1：项目当前完整基线。全部 DDL 保持幂等，以兼容从旧版 schema.sql 升级的数据库。
CREATE EXTENSION IF NOT EXISTS vector;

-- Flyway 必须能够初始化一个完全空的 PostgreSQL；旧版曾只在 db-init/init.sql 创建本表。
CREATE TABLE IF NOT EXISTS knowledge_chunk (
    id                 BIGSERIAL PRIMARY KEY,
    document_id        BIGINT,
    doc_name           VARCHAR(255) NOT NULL,
    chunk_index        INT NOT NULL,
    content            TEXT NOT NULL,
    search_text        TEXT,
    project            VARCHAR(50),
    category           VARCHAR(100),
    knowledge_type     VARCHAR(30) DEFAULT 'reference',
    risk_level         VARCHAR(20) DEFAULT 'unreviewed',
    safe_direct        BOOLEAN DEFAULT false,
    status             VARCHAR(20) DEFAULT 'published',
    embedding_strategy VARCHAR(50) DEFAULT 'content-v1',
    embedding          vector(1024) NOT NULL,
    create_time        TIMESTAMP DEFAULT now(),
    update_time        TIMESTAMP DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_chunk_doc_name ON knowledge_chunk(doc_name);
CREATE INDEX IF NOT EXISTS idx_chunk_embedding
    ON knowledge_chunk USING hnsw (embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 64);

-- 第 4 周新增：会话与消息表
-- 多租户预留：tenant_id 现在不用，但先留字段，避免后期加列代价大

CREATE TABLE IF NOT EXISTS conversation (
    id              BIGSERIAL PRIMARY KEY,
    conversation_id VARCHAR(64) UNIQUE NOT NULL,   -- 对外暴露的会话标识（UUID）
    user_id         VARCHAR(64) NOT NULL,          -- 用户标识（登录体系接入前先传匿名ID）
    tenant_id       VARCHAR(64) DEFAULT 'default', -- 多租户预留
    status          VARCHAR(20) DEFAULT 'active',  -- active / closed
    create_time     TIMESTAMP DEFAULT now(),
    update_time     TIMESTAMP DEFAULT now()
);

CREATE TABLE IF NOT EXISTS message (
    id              BIGSERIAL PRIMARY KEY,
    conversation_id VARCHAR(64) NOT NULL,
    role            VARCHAR(20) NOT NULL,          -- user / assistant / system
    content         TEXT NOT NULL,
    create_time     TIMESTAMP DEFAULT now()
);

-- 按会话查消息是最高频操作，必须建索引
CREATE INDEX IF NOT EXISTS idx_message_conv ON message(conversation_id);
CREATE INDEX IF NOT EXISTS idx_conversation_user ON conversation(user_id);

-- ========== 第 8~9 周新增 ==========

-- 长期记忆：用户画像（从聊天记录中周期性提取，如城市、意向项目、顾虑）
CREATE TABLE IF NOT EXISTS user_profile (
    user_id     VARCHAR(64) PRIMARY KEY,
    profile     TEXT,                        -- 画像文本，直接拼进 Prompt
    msg_count   INT DEFAULT 0,               -- 该用户累计消息数（控制提取频率）
    update_time TIMESTAMP DEFAULT now()
);

-- 图片素材库：本地存文件，这里存元数据（发送图片功能）
CREATE TABLE IF NOT EXISTS image_asset (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(255) NOT NULL,       -- 图片名（如"眼袋前后对比1"）
    tags        VARCHAR(500),                -- 检索标签，空格分隔（如"眼袋 对比图 案例"）
    project     VARCHAR(50),                 -- 所属项目：抗衰/淡斑/眼袋/胡子；空=通用
    asset_type  VARCHAR(20) DEFAULT 'effect',-- effect=效果图，promo=优惠海报
    enabled     BOOLEAN DEFAULT true,        -- 是否允许客服发送
    valid_from  TIMESTAMP,                   -- 优惠海报生效时间；空=立即生效
    valid_until TIMESTAMP,                   -- 优惠海报失效时间；空=长期有效
    file_path   VARCHAR(500) NOT NULL,       -- 本地存储路径
    url         VARCHAR(500) NOT NULL,       -- 对外访问地址
    create_time TIMESTAMP DEFAULT now()
);

-- 兼容已经创建过 image_asset 的数据库，应用重启执行 schema.sql 时自动补齐字段。
ALTER TABLE image_asset ADD COLUMN IF NOT EXISTS project VARCHAR(50);
ALTER TABLE image_asset ADD COLUMN IF NOT EXISTS asset_type VARCHAR(20) DEFAULT 'effect';
ALTER TABLE image_asset ADD COLUMN IF NOT EXISTS enabled BOOLEAN DEFAULT true;
ALTER TABLE image_asset ADD COLUMN IF NOT EXISTS valid_from TIMESTAMP;
ALTER TABLE image_asset ADD COLUMN IF NOT EXISTS valid_until TIMESTAMP;
CREATE INDEX IF NOT EXISTS idx_image_asset_project_type
    ON image_asset(project, asset_type, enabled);

-- 兼容旧素材：历史批量导入时只有名称/标签，新增字段会统一落成 effect/null。
-- 按已有命名规则一次性补齐，避免优惠活动图被当作效果图。
UPDATE image_asset SET asset_type = 'promo'
WHERE COALESCE(asset_type, 'effect') = 'effect'
  AND project IS NULL
  AND (name ~ '(优惠|活动价|优惠活动|海报)' OR COALESCE(tags, '') ~ '(优惠|活动价|优惠活动|海报)');
UPDATE image_asset SET project = '抗衰'
WHERE project IS NULL AND (name ~ '(抗衰|抗老|面部提升)' OR COALESCE(tags, '') ~ '(抗衰|抗老|面部提升)');
UPDATE image_asset SET project = '淡斑'
WHERE project IS NULL AND (name ~ '(淡斑|祛斑|色斑|黄褐斑|雀斑)' OR COALESCE(tags, '') ~ '(淡斑|祛斑|色斑|黄褐斑|雀斑)');
UPDATE image_asset SET project = '眼袋'
WHERE project IS NULL AND (name ~ '眼袋' OR COALESCE(tags, '') ~ '眼袋');
UPDATE image_asset SET project = '胡子'
WHERE project IS NULL AND (name ~ '(胡子|胡须)' OR COALESCE(tags, '') ~ '(胡子|胡须)');

-- 会话级图片发送记录：用于后续索图去重；图片或会话删除时记录自动清理。
CREATE TABLE IF NOT EXISTS conversation_image_send (
    conversation_id VARCHAR(64) NOT NULL REFERENCES conversation(conversation_id) ON DELETE CASCADE,
    image_id         BIGINT NOT NULL REFERENCES image_asset(id) ON DELETE CASCADE,
    trigger_type     VARCHAR(30) NOT NULL,
    sent_time        TIMESTAMP DEFAULT now(),
    PRIMARY KEY (conversation_id, image_id)
);
CREATE INDEX IF NOT EXISTS idx_conversation_image_send_time
    ON conversation_image_send(conversation_id, sent_time DESC);

-- ========== 第 10 周新增 ==========

-- 门店表：结构化门店数据（腾讯地图就近匹配、后台 CRUD 用）
CREATE TABLE IF NOT EXISTS store (
    id          BIGSERIAL PRIMARY KEY,
    city        VARCHAR(50) NOT NULL,        -- 城市（如"成都"）
    district    VARCHAR(50),                 -- 区县（如"武侯区"）
    name        VARCHAR(100) NOT NULL,       -- 门店名
    address     VARCHAR(255) NOT NULL,       -- 详细地址
    lat         DOUBLE PRECISION,            -- 纬度（腾讯地图坐标）
    lng         DOUBLE PRECISION,            -- 经度
    projects    VARCHAR(100),                -- 可做项目，逗号分隔（如"抗衰,胡子,淡斑"）
    tenant_id   VARCHAR(64) DEFAULT 'default', -- 多租户预留
    create_time TIMESTAMP DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_store_city ON store(city);

-- ========== 第 11 周新增 ==========

-- 系统配置表：Prompt、门槛值等热更新（改配置不用重启，调 /config/reload 即生效）
CREATE TABLE IF NOT EXISTS system_config (
    key         VARCHAR(100) PRIMARY KEY,
    value       TEXT,
    update_time TIMESTAMP DEFAULT now()
);

-- ========== 第 13~15 周新增 ==========

-- 预约登记表：Agent 收集的预约线索（称呼+电话+意向项目+城市）
CREATE TABLE IF NOT EXISTS appointment (
    id          BIGSERIAL PRIMARY KEY,
    user_id     VARCHAR(64),
    name        VARCHAR(50),                 -- 客户称呼
    phone       VARCHAR(20),                 -- 手机号（内部存储不脱敏，外发才脱敏）
    project     VARCHAR(50),                 -- 意向项目
    city        VARCHAR(50),
    note        VARCHAR(500),
    status      VARCHAR(20) DEFAULT 'new',   -- new / contacted / done
    tenant_id   VARCHAR(64) DEFAULT 'default',
    create_time TIMESTAMP DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_appointment_status ON appointment(status);

-- ========== 第 16~17 周新增 ==========

-- 坐席账号表（人工客服工作台）
CREATE TABLE IF NOT EXISTS agent (
    id                 BIGSERIAL PRIMARY KEY,
    username           VARCHAR(50) UNIQUE NOT NULL,
    name               VARCHAR(50) NOT NULL,        -- 展示给客户的称呼
    password           VARCHAR(100) NOT NULL,       -- BCrypt 哈希；禁止保存明文
    status             VARCHAR(20) DEFAULT 'offline', -- online / offline
    last_assigned_time TIMESTAMP DEFAULT now(),     -- 轮询分配依据：谁最久没接单谁先接
    create_time        TIMESTAMP DEFAULT now()
);

-- 不写入任何默认账号或口令。首次启动由 AICS_BOOTSTRAP_ADMIN_PASSWORD 创建管理员。

-- 会话表增加所属坐席（已存在的表补列，可重复执行）
ALTER TABLE conversation ADD COLUMN IF NOT EXISTS agent_id BIGINT;

-- ========== 第 23 周新增：排队入队时间 ==========
-- queue_time：客户进入排队/人工的时刻（首次进入时记录），队列按此排序保证 FIFO，
-- 不被后续消息刷新 update_time 打乱（修复排队公平性 P1-6）
ALTER TABLE conversation ADD COLUMN IF NOT EXISTS queue_time TIMESTAMP;

-- ========== 第 18 周新增 ==========

-- 留言表：非工作时间/排队兜底，客户留言（称呼+电话+问题），客服上班后回访
CREATE TABLE IF NOT EXISTS leave_message (
    id              BIGSERIAL PRIMARY KEY,
    conversation_id VARCHAR(64),
    user_id         VARCHAR(64),
    content         TEXT,                    -- 留言内容
    phone           VARCHAR(20),             -- 联系电话（本地正则提取）
    status          VARCHAR(20) DEFAULT 'new', -- new / contacted
    create_time     TIMESTAMP DEFAULT now()
);

-- ========== 权限简化版新增 ==========

-- 坐席角色：admin（管理员，可进管理后台）/ agent（普通坐席，只能工作台）
ALTER TABLE agent ADD COLUMN IF NOT EXISTS role VARCHAR(20) DEFAULT 'agent';
UPDATE agent SET role = 'admin' WHERE username = 'admin';

-- ========== 报表新增 ==========

-- 消息记录应答方式（FAQ直回/AGENT/AI/REFUSAL），用于拒答率、直回率等运营指标
ALTER TABLE message ADD COLUMN IF NOT EXISTS match_type VARCHAR(20);

-- ========== 唤醒任务新增 ==========

-- 不活跃用户唤醒任务：定时扫描生成，人工回访后标记
CREATE TABLE IF NOT EXISTS wake_task (
    id           BIGSERIAL PRIMARY KEY,
    user_id      VARCHAR(64) NOT NULL,
    last_active  TIMESTAMP,                  -- 最后活跃时间
    profile      TEXT,                       -- 用户画像快照（写话术参考）
    script       TEXT,                       -- AI 生成的个性化回访话术建议
    status       VARCHAR(20) DEFAULT 'new',  -- new / contacted
    create_time  TIMESTAMP DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_wake_task_status ON wake_task(status);

-- ========== 异步知识导入任务（第二批 #6） ==========

-- 知识库文档异步导入任务：上传接口秒回，解析/切片/Embedding 后台线程池执行
-- 前端按 taskId 轮询状态：processing → done（成功带切片数）/ failed（带失败原因）
CREATE TABLE IF NOT EXISTS import_task (
    id           BIGSERIAL PRIMARY KEY,
    file_name    VARCHAR(255) NOT NULL,      -- 原始文件名
    status       VARCHAR(20) DEFAULT 'processing', -- processing / done / failed
    chunk_count  INT,                        -- 成功入库切片数（done 时有值）
    error        VARCHAR(1000),              -- 失败原因（failed 时有值）
    create_time  TIMESTAMP DEFAULT now(),
    finish_time  TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_import_task_status ON import_task(status);

-- ========== channel 包（企微微信客服 Kf 接入，第二批 #7） ==========

-- 企微客服客户 ↔ 系统用户映射（身份收敛，修复 P1-6）
-- 注意：Kf 消息里的 touser 就是 external_userid（微信用户在企微客户体系里的唯一 ID）
CREATE TABLE IF NOT EXISTS external_user (
    external_userid VARCHAR(128) PRIMARY KEY,   -- Kf 消息里的 touser（EXTERNAL_USERID）
    user_id         VARCHAR(64) NOT NULL,       -- 我们系统的用户标识
    nickname        VARCHAR(100),               -- 客户昵称（Kf 事件可带）
    create_time     TIMESTAMP DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_external_user_user ON external_user(user_id);

-- conversation 表补渠道字段（web 网页 / kf 企微客服）
ALTER TABLE conversation ADD COLUMN IF NOT EXISTS channel VARCHAR(20) DEFAULT 'web';
ALTER TABLE conversation ADD COLUMN IF NOT EXISTS external_userid VARCHAR(128);

-- ========== W26-M6 操作日志 ==========
-- 记录管理端/坐席的关键写操作（删除/改密/新增等），审计可查
CREATE TABLE IF NOT EXISTS audit_log (
    id          BIGSERIAL PRIMARY KEY,
    action      VARCHAR(50) NOT NULL,        -- 如 DELETE_IMAGE / RESET_PASSWORD / CREATE_AGENT
    object      VARCHAR(255),                -- 操作对象描述（图片名/门店名/账号等）
    operator    VARCHAR(50),                 -- 操作人（管理后台固定 admin / 坐席姓名）
    create_time TIMESTAMP DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_audit_log_time ON audit_log(create_time);

-- ========== 知识库治理升级：元数据、发布状态、安全直回、结构化事实 ==========

CREATE TABLE IF NOT EXISTS knowledge_document (
    id              BIGSERIAL PRIMARY KEY,
    doc_name        VARCHAR(255) UNIQUE NOT NULL,
    doc_type        VARCHAR(30) DEFAULT 'reference',
    status          VARCHAR(20) DEFAULT 'published',
    source          VARCHAR(500),
    version         INT DEFAULT 1,
    valid_from      TIMESTAMP,
    valid_until     TIMESTAMP,
    reviewer        VARCHAR(100),
    create_time     TIMESTAMP DEFAULT now(),
    update_time     TIMESTAMP DEFAULT now()
);

ALTER TABLE knowledge_chunk ADD COLUMN IF NOT EXISTS document_id BIGINT;
ALTER TABLE knowledge_chunk ADD COLUMN IF NOT EXISTS search_text TEXT;
ALTER TABLE knowledge_chunk ADD COLUMN IF NOT EXISTS project VARCHAR(50);
ALTER TABLE knowledge_chunk ADD COLUMN IF NOT EXISTS category VARCHAR(100);
ALTER TABLE knowledge_chunk ADD COLUMN IF NOT EXISTS knowledge_type VARCHAR(30) DEFAULT 'reference';
ALTER TABLE knowledge_chunk ADD COLUMN IF NOT EXISTS risk_level VARCHAR(20) DEFAULT 'unreviewed';
ALTER TABLE knowledge_chunk ADD COLUMN IF NOT EXISTS safe_direct BOOLEAN DEFAULT false;
ALTER TABLE knowledge_chunk ADD COLUMN IF NOT EXISTS status VARCHAR(20) DEFAULT 'published';
ALTER TABLE knowledge_chunk ADD COLUMN IF NOT EXISTS embedding_strategy VARCHAR(50) DEFAULT 'legacy-content-v0';
ALTER TABLE knowledge_chunk ADD COLUMN IF NOT EXISTS update_time TIMESTAMP DEFAULT now();

INSERT INTO knowledge_document(doc_name, doc_type, status)
SELECT DISTINCT doc_name,
       CASE
         WHEN doc_name = '概念-医美营销模式.md' THEN 'internal'
         WHEN doc_name ILIKE '%FAQ%' THEN 'faq'
         WHEN doc_name = 'price.xlsx' THEN 'offer'
         ELSE 'reference'
       END,
       CASE WHEN doc_name = '概念-医美营销模式.md' THEN 'disabled' ELSE 'published' END
FROM knowledge_chunk
ON CONFLICT (doc_name) DO NOTHING;

-- 明确隔离内部营销资料；重复启动也会保持禁用，不删除原文，随时可审计恢复。
UPDATE knowledge_document
SET doc_type = 'internal', status = 'disabled', update_time = now()
WHERE doc_name = '概念-医美营销模式.md';

UPDATE knowledge_chunk k
SET document_id = d.id,
    search_text = COALESCE(k.search_text, k.content),
    knowledge_type = CASE
        WHEN d.doc_type = 'faq' THEN 'faq'
        WHEN d.doc_type = 'offer' THEN 'offer'
        ELSE COALESCE(k.knowledge_type, 'reference')
    END,
    category = COALESCE(k.category, NULLIF(substring(k.content from '分类：([^\n\r]+)'), '')),
    project = COALESCE(k.project, CASE
        WHEN substring(k.content from '分类：([^\n\r]+)') IN ('抗衰','眼袋','淡斑','胡子')
        THEN substring(k.content from '分类：([^\n\r]+)')
        ELSE NULL
    END)
FROM knowledge_document d
WHERE k.doc_name = d.doc_name;

CREATE INDEX IF NOT EXISTS idx_chunk_project_status ON knowledge_chunk(project, status);

CREATE TABLE IF NOT EXISTS business_fact (
    fact_key        VARCHAR(100) PRIMARY KEY,
    project         VARCHAR(50),
    fact_type       VARCHAR(30) NOT NULL,
    display_text    TEXT,
    status          VARCHAR(20) DEFAULT 'draft',
    source          VARCHAR(500),
    valid_from      TIMESTAMP,
    valid_until     TIMESTAMP,
    reviewer        VARCHAR(100),
    update_time     TIMESTAMP DEFAULT now()
);

-- 价格只允许从已上传的 price.xlsx 知识切片读取；移除旧版本硬编码价格，避免表格更新后仍回复旧值。
DELETE FROM business_fact WHERE fact_type = 'price' OR fact_key LIKE 'price.%';

-- 冲突项（接车条件、动态名额等）保持 draft，等待人工确认。
INSERT INTO business_fact(fact_key, project, fact_type, display_text, status, source)
VALUES
 ('activity.availability', NULL, 'availability', NULL, 'draft', '待运营确认实时名额'),
 ('service.pickupPolicy', NULL, 'service', NULL, 'draft', '待核对接车距离与报销标准')
ON CONFLICT (fact_key) DO NOTHING;
