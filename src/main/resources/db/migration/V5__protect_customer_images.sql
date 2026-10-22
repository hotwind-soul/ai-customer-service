-- 客户上传图片访问归属：静态 URL 仍保持兼容，但 c_ 前缀文件必须校验 HttpOnly 客户身份。
CREATE TABLE IF NOT EXISTS customer_uploaded_image (
    id          BIGSERIAL PRIMARY KEY,
    user_id     VARCHAR(64) NOT NULL,
    filename    VARCHAR(255) NOT NULL UNIQUE,
    create_time TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_customer_uploaded_image_user
    ON customer_uploaded_image(user_id, create_time DESC);

-- 为 V5 以前已经绑定到客户消息的图片回填归属。
INSERT INTO customer_uploaded_image(user_id, filename)
SELECT DISTINCT c.user_id,
       substring(a.url from '/uploads/images/(c_[A-Za-z0-9._-]+)$')
FROM message_attachment a
JOIN message m ON m.id = a.message_id AND m.role = 'user'
JOIN conversation c ON c.conversation_id = m.conversation_id
WHERE a.url ~ '^/uploads/images/c_[A-Za-z0-9._-]+$'
ON CONFLICT (filename) DO NOTHING;
