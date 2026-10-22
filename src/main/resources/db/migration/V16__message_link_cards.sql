-- 图文链接卡片独立持久化：地址卡片在刷新和历史会话中长期保留。
CREATE TABLE IF NOT EXISTS message_link_card (
    id            BIGSERIAL PRIMARY KEY,
    message_id    BIGINT NOT NULL REFERENCES message(id) ON DELETE CASCADE,
    title         VARCHAR(200) NOT NULL,
    description   VARCHAR(500) NOT NULL DEFAULT '',
    url           VARCHAR(2000) NOT NULL,
    thumbnail_url VARCHAR(1000) NOT NULL DEFAULT '',
    create_time   TIMESTAMP NOT NULL DEFAULT now(),
    CONSTRAINT chk_message_link_card_url CHECK (url LIKE 'https://%' OR url LIKE 'http://%'),
    CONSTRAINT chk_message_link_card_thumbnail CHECK (
        thumbnail_url = '' OR thumbnail_url LIKE '/%' OR
        thumbnail_url LIKE 'https://%' OR thumbnail_url LIKE 'http://%'
    )
);

CREATE INDEX IF NOT EXISTS idx_message_link_card_message
    ON message_link_card(message_id, id);
