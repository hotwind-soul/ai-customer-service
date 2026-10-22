-- 人工接待迁移至企业微信原生接待（W26 决策）：
-- 转人工后由企微按接待规则自动分配接待成员，会话的服务状态变化
-- （session_status_change 事件）把接待成员的 userid 记到本列，
-- 质检与报表按「企微成员」归属人工接待，不再依赖本站坐席账号。
ALTER TABLE conversation ADD COLUMN IF NOT EXISTS servicer_userid VARCHAR(64);
