-- 索引补齐 + 冗余索引清理（缺陷修复：队列轮询与报表全表扫描）
--
-- 背景：
-- 1) 排队位次接口 /api/conversation-status 由每个排队客户每 30 秒调用一次，
--    而 conversation 上没有任何以 status 为前导列的索引 → 每次都对全表做 Seq Scan。
-- 2) 报表「AI 应答构成」按 create_time 范围统计 role='assistant' 的消息，
--    已有的 message 索引（V1/V4/V44）全部以 conversation_id 或 role='user' 为前导，无法服务该范围查询。
-- 3) 两个单列索引被后续复合索引完全覆盖，只增加每次写入的索引维护成本，删除。

-- 排队队列：状态 + 入队时间（只索引真正排队的行，体积小、写放大低）
CREATE INDEX IF NOT EXISTS idx_conversation_status_queue
    ON conversation(status, queue_time) WHERE status = 'need_human';

-- 报表：AI 应答按时间范围扫描（与 V44 的 role='user' 部分索引对应，这里补 assistant 侧）
CREATE INDEX IF NOT EXISTS idx_message_assistant_period
    ON message(create_time, conversation_id) WHERE role = 'assistant';

-- 冗余索引清理：均被下列复合索引的前导列完全覆盖，规划器不会再选中它们
DROP INDEX IF EXISTS idx_message_conv;        -- 覆盖者：idx_message_conv_time(conversation_id, create_time, id)
DROP INDEX IF EXISTS idx_conversation_user;   -- 覆盖者：idx_conversation_user_channel_status_time(user_id, channel, status, update_time DESC)
