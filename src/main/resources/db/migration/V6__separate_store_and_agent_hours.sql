-- 门店营业时间与客服在线时间是两个独立业务概念。
-- 新配置固定门店为 10:00-18:00；仅把历史默认客服时间迁移到 09:00-22:00，保留其他人工自定义值。
INSERT INTO system_config(key, value, update_time)
VALUES ('store.businessHours', '10:00-18:00', now())
ON CONFLICT (key) DO NOTHING;

INSERT INTO system_config(key, value, update_time)
VALUES ('agent.workHours', '09:00-22:00', now())
ON CONFLICT (key) DO UPDATE
SET value = '09:00-22:00', update_time = now()
WHERE system_config.value IN ('10:00-22:00', '10:00-18:00');
