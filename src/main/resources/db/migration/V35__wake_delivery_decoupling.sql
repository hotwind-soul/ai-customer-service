-- V35：唤醒"任务"与"触达"解耦（无悔设计，2026-08-28）
-- 背景：wake_task 保持"找谁 + 话术建议"；触达通道（微信客服被动会话 /
--       客户联系群发 / 人工导出）需等企微 keys 落地并验证后选定。
--       本迁移只建"通道无关"的触达记录表与运营参数，不绑定任何通道实现，
--       届时只新增对应通道的 delivery 驱动，任务侧数据链路不变。
-- 决策依据：docs/设计文档/唤醒触达通道决策.md

-- 触达尝试记录：一个唤醒任务可有多条触达（重试/换通道）。
CREATE TABLE IF NOT EXISTS wake_delivery (
    id          BIGSERIAL PRIMARY KEY,
    task_id     BIGINT NOT NULL REFERENCES wake_task(id) ON DELETE CASCADE,
    channel     VARCHAR(30)  NOT NULL,              -- manual / wecom_kf / wecom_mass / export
    status      VARCHAR(20)  NOT NULL DEFAULT 'pending',  -- pending / sent / failed / skipped
    detail      TEXT,                               -- 失败原因、企微返回码、导出文件名等
    operator    VARCHAR(64),                        -- manual 通道记录操作人；自动通道为 system
    create_time TIMESTAMP DEFAULT now(),
    update_time TIMESTAMP DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_wake_delivery_task ON wake_delivery(task_id);

-- 运营参数默认值（热配置：改 system_config 立即生效，无需重启；
-- 代码里 config.get 的内置默认与此保持一致）
INSERT INTO system_config(key, value) VALUES
    ('wakeup.inactiveDays', '3'),
    ('wakeup.quietStart', '22'),        -- 免打扰起始小时（含），22:00 后不触达
    ('wakeup.quietEnd', '9'),           -- 免打扰结束小时（不含），9:00 前不触达
    ('wakeup.maxPerUserPerWeek', '1')   -- 同一用户每周最多触达次数（防骚扰）
ON CONFLICT (key) DO NOTHING;
