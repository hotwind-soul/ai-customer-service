package com.example.aics.chat;

import com.example.aics.config.ConfigService;
import com.example.aics.config.AdminDataScope;
import com.example.aics.image.ImageService;
import com.example.aics.message.LinkCard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 会话管理：创建会话、存消息、取最近 N 轮历史。
 *
 * 第 8~9 周升级：短期记忆加 Redis 缓存。
 * - 读：先查 Redis（"hist:{convId}"），没有再查 PostgreSQL 并回填缓存；
 * - 写：落库后删缓存（下次读重建），保证两边一致；
 * - 缓存有效期 2 小时：超过 2 小时没说话的会话，从库里重新读，开销忽略不计。
 */
@Service
public class ConversationService {

    private static final Logger log = LoggerFactory.getLogger(ConversationService.class);
    private static final List<String> LEGACY_RISK_PHRASES = List.of(
            "立竿见影", "保证有效", "保证能", "百分百", "100%有效", "100％有效",
            "永不反弹", "不会反弹", "永久不反弹", "绝对安全", "零风险", "完全不痛",
            "无需停药", "不需要停药", "无条件退款");

    /** 拼进 Prompt 的历史轮数上限：10 轮（一问一答算一轮，即最近 20 条消息） */
    private static final int HISTORY_ROUNDS = 10;

    private static final String HISTORY_KEY_PREFIX = "hist:";

    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;
    private final ConfigService config;
    private final ImageService imageService;

    public ConversationService(JdbcTemplate jdbc, StringRedisTemplate redis, ConfigService config,
                               ImageService imageService) {
        this.jdbc = jdbc;
        this.redis = redis;
        this.config = config;
        this.imageService = imageService;
    }

    /**
     * 确保会话存在：传了 conversationId 且属于该 userId 就用，否则新建。
     * 修复 W25-B1（写路径 IDOR）：之前只校验会话存在不校验归属，攻击者可借他人
     * conversationId 往别人的会话写消息、读别人的历史；现在不匹配归属一律新建，
     * 正常用户传自己的会话不受影响。
     * @return 会话 ID（新建的是随机 UUID）
     */
    public String ensureConversation(String conversationId, String userId) {
        String safeUserId = userId == null || userId.isBlank() ? "anonymous" : userId;
        if (conversationId != null && !conversationId.isBlank()) {
            Integer n = jdbc.queryForObject(
                    "SELECT count(*) FROM conversation WHERE conversation_id = ? AND user_id = ? AND status <> 'closed'",
                    Integer.class, conversationId, safeUserId);
            if (n != null && n > 0) {
                return conversationId;
            }
        }
        String newId = UUID.randomUUID().toString();
        jdbc.update(
                "INSERT INTO conversation(conversation_id, user_id) VALUES (?, ?)",
                newId, safeUserId);
        return newId;
    }

    /**
     * 企微渠道会话复用（W27-B2）：回调没有前端页面、没有 conversationId 可传，
     * 必须按用户复用最近一个未关闭会话，否则转人工/排队后下一条消息会错误新建 active 会话，
     * 预约流程（Redis 按 convId 存进度）和多轮上下文全部断裂。
     * @return 该用户最近的未关闭会话 ID；没有则新建（带 channel='kf' 标记）
     */
    public String findOrCreateKfConversation(String userId) {
        String safeUserId = userId == null || userId.isBlank() ? "anonymous" : userId;
        List<String> rows = jdbc.queryForList(
                "SELECT conversation_id FROM conversation " +
                "WHERE user_id = ? AND channel = 'kf' AND status IN ('active','need_human','human') " +
                "ORDER BY update_time DESC, id DESC LIMIT 1",
                String.class, safeUserId);
        if (!rows.isEmpty()) {
            return rows.get(0);
        }
        String newId = UUID.randomUUID().toString();
        jdbc.update(
                "INSERT INTO conversation(conversation_id, user_id, channel) VALUES (?, ?, 'kf')",
                newId, safeUserId);
        return newId;
    }

    /** 多企业客服会话按企业和客服号隔离；同一客户跨客服号不会复用上下文。 */
    public String findOrCreateKfConversation(String userId, Long tenantId, Long kfAccountId) {
        if (tenantId == null || kfAccountId == null) return findOrCreateKfConversation(userId);
        String safeUserId = userId == null || userId.isBlank() ? "anonymous" : userId;
        List<String> rows = jdbc.queryForList(
                "SELECT conversation_id FROM conversation WHERE user_id=? AND channel='kf' " +
                        "AND tenant_id=? AND kf_account_id=? " +
                        "AND status IN ('active','need_human','human') " +
                        "ORDER BY update_time DESC, id DESC LIMIT 1",
                String.class, safeUserId, tenantId, kfAccountId);
        if (!rows.isEmpty()) return rows.get(0);
        String newId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO conversation(conversation_id, user_id, channel, tenant_id, kf_account_id) " +
                        "VALUES (?, ?, 'kf', ?, ?)",
                newId, safeUserId, tenantId, kfAccountId);
        return newId;
    }

    /** 该会话是否还没有任何消息（用于"新用户接入欢迎语"判断） */
    public boolean isFirstMessage(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return false;
        }
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM message WHERE conversation_id = ?",
                Integer.class, conversationId);
        return n == null || n == 0;
    }

    /** 当前会话是否已经真正发送过该项目的首次活动介绍。 */
    public boolean hasProjectIntroduced(String conversationId, String project) {
        if (conversationId == null || conversationId.isBlank() || project == null || project.isBlank()) {
            return false;
        }
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM conversation_project_intro WHERE conversation_id = ? AND project = ?",
                Integer.class, conversationId, project);
        return n != null && n > 0;
    }

    /** 文字应答成功落库后再标记；调试预览只调用 prepare，不会写入本表。 */
    public void markProjectIntroduced(String conversationId, String project) {
        if (conversationId == null || conversationId.isBlank() || project == null || project.isBlank()) {
            return;
        }
        jdbc.update(
                "INSERT INTO conversation_project_intro(conversation_id, project) VALUES (?, ?) " +
                        "ON CONFLICT (conversation_id, project) DO NOTHING",
                conversationId, project);
    }

    /** 该用户最近一个 active 会话（网页欢迎语复用用）；没有返回 null */
    public String findActiveConversation(String userId) {
        String safeUserId = userId == null || userId.isBlank() ? "anonymous" : userId;
        List<String> rows = jdbc.queryForList(
                "SELECT conversation_id FROM conversation " +
                "WHERE user_id = ? AND status = 'active' " +
                "ORDER BY update_time DESC, id DESC LIMIT 1",
                String.class, safeUserId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 保存一条消息，刷新会话时间，并把新消息【追加】进短期记忆缓存。
     * 注意不能删除缓存：每轮问答是"先读历史、后存消息"，删除会导致缓存永远等不到命中。
     */
    public void saveMessage(String conversationId, String role, String content) {
        saveMessage(conversationId, role, content, null);
    }

    /** 带应答方式（matchType）的保存：报表统计用 */
    public long saveMessage(String conversationId, String role, String content, String matchType) {
        Long messageId = jdbc.queryForObject(
                "INSERT INTO message(conversation_id, role, content, match_type) VALUES (?, ?, ?, ?) RETURNING id",
                Long.class, conversationId, role, content, matchType);
        jdbc.update(
                "UPDATE conversation SET update_time = now() WHERE conversation_id = ?",
                conversationId);
        // 外层存在消息事务时只在提交后更新缓存，避免后续附件/画像写入失败却留下“幽灵历史”。
        afterCommit(() -> appendHistoryCache(conversationId, role, content));
        return messageId == null ? 0L : messageId;
    }

    /**
     * 保存不含用户原文的路由追踪摘要。与助手消息分开写入，保留现有消息保存 API；
     * 仍受外层会话事务约束，附件或画像写入失败时会一并回滚。
     */
    public void saveDecisionTrace(long messageId, String traceJson) {
        if (messageId <= 0 || traceJson == null || traceJson.isBlank()) return;
        jdbc.update("UPDATE message SET decision_trace=CAST(? AS jsonb) WHERE id=?",
                traceJson, messageId);
    }

    /** 将已实际发送或上传的图片绑定具体消息，供历史恢复长期展示。 */
    public void saveImageAttachments(long messageId, List<Map<String, Object>> images) {
        if (messageId <= 0 || images == null || images.isEmpty()) return;
        for (Map<String, Object> image : images) {
            String url = image.get("url") == null ? "" : String.valueOf(image.get("url"));
            if (!url.matches("^/uploads/images/[A-Za-z0-9._-]+$")) continue;
            String name = image.get("name") == null ? "图片" : String.valueOf(image.get("name"));
            Long assetId = null;
            Object id = image.get("id");
            if (id instanceof Number number) assetId = number.longValue();
            jdbc.update(
                    "INSERT INTO message_attachment(message_id, attachment_type, name, url, asset_id) " +
                            "VALUES (?, 'image', ?, ?, ?)",
                    messageId, name, url, assetId);
        }
    }

    /** 保存图文链接卡片，保证刷新页面和历史会话中仍以卡片形式展示。 */
    public void saveLinkCards(long messageId, List<LinkCard> linkCards) {
        if (messageId <= 0 || linkCards == null || linkCards.isEmpty()) return;
        for (LinkCard card : linkCards) {
            if (card == null || !card.isDeliverable()) continue;
            jdbc.update(
                    "INSERT INTO message_link_card(message_id, title, description, url, thumbnail_url) " +
                            "VALUES (?,?,?,?,?)",
                    messageId, limit(card.title(), 200), limit(card.description(), 500),
                    limit(card.url(), 2000), limit(card.thumbnailUrl(), 1000));
        }
    }

    /** 把新消息追加进缓存；只保留最近 N*2 行；没有缓存就等下次读时回源数据库重建 */
    private void appendHistoryCache(String conversationId, String role, String content) {
        try {
            String key = HISTORY_KEY_PREFIX + conversationId;
            List<String> cached = redis.opsForList().range(key, 0, -1);
            if (cached == null || cached.isEmpty()) {
                return;
            }
            String speaker = "user".equals(role) ? "用户" : "客服";
            String line = speaker + "：" + content;
            if ("（无历史对话）".equals(cached.get(0)) && cached.size() == 1) {
                // 缓存是占位符：直接替换成第一条真实消息
                redis.opsForList().set(key, 0, line);
            } else {
                // 原子追加 + 截断只留最近 N*2 行（修复 W25-S2：原来 get→set 非原子，并发丢行）
                redis.opsForList().rightPush(key, line);
                redis.opsForList().trim(key, -historyRounds() * 2, -1);
            }
            redis.expire(key, historyTtl());
        } catch (RuntimeException e) {
            log.warn("消息已落库，但更新 Redis 历史缓存失败: conversationId={}, error={}",
                    conversationId, e.toString());
        }
    }

    /**
     * 取最近 N 轮历史（短期记忆）：先读 Redis 缓存（LIST），没有再读库并回填。
     * 返回拼好的文本，供组装 Prompt 使用。
     */
    public String recentHistoryText(String conversationId) {
        String key = HISTORY_KEY_PREFIX + conversationId;
        boolean cacheAvailable = true;
        try {
            List<String> cached = redis.opsForList().range(key, 0, -1);
            if (cached != null && !cached.isEmpty()) {
                int maxLines = historyRounds() * 2;
                if (cached.size() > maxLines) {
                    redis.opsForList().trim(key, -maxLines, -1);
                    cached = redis.opsForList().range(key, 0, -1);
                }
                return String.join("\n", cached) + "\n";
            }
        } catch (RuntimeException e) {
            cacheAvailable = false;
            log.warn("读取 Redis 历史缓存失败，回源数据库: conversationId={}, error={}",
                    conversationId, e.toString());
        }

        // 先按时间倒序拿最近 N*2 条，再反转成正序
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT role, content FROM (" +
                "  SELECT id, role, content, create_time FROM message " +
                "  WHERE conversation_id = ? ORDER BY create_time DESC, id DESC LIMIT ?" +
                ") t ORDER BY create_time ASC, id ASC",
                conversationId, historyRounds() * 2);

        String result;
        if (rows.isEmpty()) {
            result = "（无历史对话）";
        } else {
            StringBuilder sb = new StringBuilder();
            for (Map<String, Object> row : rows) {
                String speaker = "user".equals(row.get("role")) ? "用户" : "客服";
                String line = speaker + "：" + row.get("content");
                sb.append(line).append("\n");
            }
            result = sb.toString();
        }
        if (cacheAvailable) {
            refillHistoryCacheBestEffort(key, rows, result);
        }
        return result;
    }

    private void refillHistoryCacheBestEffort(String key, List<Map<String, Object>> rows, String result) {
        try {
            if (rows.isEmpty()) {
                redis.opsForList().rightPush(key, result);
            } else {
                List<String> lines = new ArrayList<>(rows.size());
                for (Map<String, Object> row : rows) {
                    String speaker = "user".equals(row.get("role")) ? "用户" : "客服";
                    lines.add(speaker + "：" + row.get("content"));
                }
                // 一次性回填（并发下两个线程可能重复回填，但 append 时 trim 会收敛，2 小时 TTL 后重建，可接受）
                redis.opsForList().rightPushAll(key, lines.toArray(String[]::new));
            }
            redis.expire(key, historyTtl());
        } catch (RuntimeException e) {
            log.warn("回填 Redis 历史缓存失败，本次继续使用数据库结果: key={}, error={}",
                    key, e.toString());
        }
    }

    private int historyRounds() {
        return Math.max(1, Math.min(config.getInt("conversation.historyRounds", HISTORY_ROUNDS), 50));
    }

    private Duration historyTtl() {
        int minutes = Math.max(5, Math.min(config.getInt("conversation.historyTtlMinutes", 120), 1440));
        return Duration.ofMinutes(minutes);
    }

    /**
     * 清除某用户的全部记忆（"清除我的记忆"功能）：
     * 会话、消息、用户画像、Redis 短期缓存，一并删除。
     * W25-Nit：预约、留言、唤醒任务也按 userId 清理——"清除全部记忆"语义下
     * 这些带着手机号/个人信息的业务数据不应继续留在运营后台。
     */
    @Transactional
    public int deleteUserData(String userId) {
        List<String> convIds = jdbc.queryForList(
                "SELECT conversation_id FROM conversation WHERE user_id = ?",
                String.class, userId);
        jdbc.update("DELETE FROM user_profile WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM appointment WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM leave_message WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM wake_task WHERE user_id = ?", userId);
        List<String> customerImages = imageService.removeCustomerImageRecords(userId);
        int deleted = jdbc.update("DELETE FROM conversation WHERE user_id = ?", userId);
        afterCommit(() -> {
            convIds.forEach(this::deleteHistoryCache);
            imageService.deleteCustomerImageFiles(customerImages);
        });
        return deleted;
    }

    /** 企微原生接待：记录接待成员（企微 userid）。转接/换人覆盖写入，接待结束保留归属供质检归属。 */
    @Transactional
    public void setServicer(String conversationId, String servicerUserid) {
        if (servicerUserid == null || servicerUserid.isBlank()) return;
        jdbc.update("UPDATE conversation SET servicer_userid=?, update_time=now() " +
                "WHERE conversation_id=?", servicerUserid, conversationId);
    }

    /**
     * 幽灵坐席回收：会话仍挂在某坐席名下，但该坐席 WS 已断时，把会话退回排队队列。
     * 条件更新（agent_id 必须仍等于该坐席）保证并发下不会误动已被他人接管的会话。
     * @return true 表示本次确实完成了回收
     */
    @Transactional
    public boolean releaseFromUnavailableAgent(String conversationId, long agentId) {
        return jdbc.update(
                "UPDATE conversation SET agent_id=NULL, status='need_human', " +
                        "queue_time=COALESCE(queue_time, now()), update_time=now() " +
                        "WHERE conversation_id=? AND status='human' AND agent_id=?",
                conversationId, agentId) > 0;
    }

    /**
     * 删除单条会话（后台「会话质检」删除指定会话）：级联删除其消息与短期记忆缓存，
     * 但保留该用户的用户画像/预约/留言等业务数据（不影响其它会话）。
     */
    @Transactional
    public int deleteConversation(String conversationId) {
        AdminDataScope.Condition scope = AdminDataScope.condition("conversation");
        List<Object> args = new ArrayList<>();
        args.add(conversationId);
        args.addAll(scope.args());
        int deleted = jdbc.update("DELETE FROM conversation WHERE conversation_id = ?"
                + (scope.restricted() ? " AND " + scope.sql() : ""), args.toArray());
        if (deleted > 0) {
            afterCommit(() -> deleteHistoryCache(conversationId));
        }
        return deleted;
    }

    /**
     * 清空全部会话（后台「会话质检」超级管理员操作）：物理删除全部会话记录，
     * 外键级联删除其全部消息，并清理对应的短期记忆缓存。
     * 保留用户画像/预约/留言等业务数据，不影响其他业务表。
     * 注意：超级管理员的全清操作绕过数据范围限制，会删除全部企业的会话。
     */
    @Transactional
    public int deleteAllConversations() {
        int deleted = jdbc.update("DELETE FROM conversation");
        if (deleted > 0) {
            afterCommit(() -> {
                try {
                    Set<String> keys = redis.keys(HISTORY_KEY_PREFIX + "*");
                    if (keys != null && !keys.isEmpty()) redis.delete(keys);
                } catch (Exception ignored) { }
            });
        }
        return deleted;
    }

    private void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { action.run(); }
        });
    }

    private void deleteHistoryCache(String conversationId) {
        try { redis.delete(HISTORY_KEY_PREFIX + conversationId); } catch (Exception ignored) { }
    }

    /** 更新会话状态（active / need_human / human / closed）——转人工用 */
    public void setStatus(String conversationId, String status) {
        // 进入排队/人工时记录入队时间（仅首次），保证队列 FIFO 不被后续 update_time 刷新打乱
        if ("need_human".equals(status) || "human".equals(status)) {
            jdbc.update(
                    "UPDATE conversation SET status = ?, queue_time = COALESCE(queue_time, now()), " +
                    "update_time = now() WHERE conversation_id = ?",
                    status, conversationId);
        } else {
            jdbc.update(
                    "UPDATE conversation SET status = ?, update_time = now() WHERE conversation_id = ?",
                    status, conversationId);
        }
    }

    /** 状态事件由数据库原子记录；这里补充业务原因，不依靠当前状态反推历史。 */
    public void recordHandoffReason(String conversationId, String reason) {
        try {
            jdbc.update("UPDATE conversation_handoff_event SET reason=? WHERE id=(" +
                            "SELECT id FROM conversation_handoff_event WHERE conversation_id=? " +
                            "ORDER BY id DESC LIMIT 1) AND reason='UNKNOWN'", reason, conversationId);
        } catch (org.springframework.dao.DataAccessException e) {
            log.warn("记录转人工原因失败，会话转接已完成", e);
        }
    }

    /** 仅在渠道确认首次答复发送成功后调用；统计失败不影响客户接待。 */
    public void recordFirstResponse(String conversationId, ResponseTiming timing) {
        if (conversationId == null || conversationId.isBlank() || timing == null) return;
        long elapsedMs = timing.elapsedMs();
        try {
            jdbc.update("INSERT INTO ai_response_timing(sample_key,conversation_id,received_at,first_response_ms) " +
                            "VALUES (?,?,?,?) ON CONFLICT (sample_key) DO NOTHING",
                    timing.sampleKey(), conversationId, timing.receivedAt(), elapsedMs);
        } catch (org.springframework.dao.DataAccessException e) {
            log.warn("记录 AI 首响耗时失败，客户答复已发送", e);
        }
    }

    /** 聊天入口统一读取状态；不存在时返回 active，调用方此前应先 ensureConversation。 */
    public Map<String, Object> getRoute(String conversationId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT status, agent_id, channel, external_userid, open_kfid, tenant_id, kf_account_id " +
                        "FROM conversation WHERE conversation_id = ?",
                conversationId);
        return rows.isEmpty() ? Map.of("status", "active") : rows.get(0);
    }

    /** 更新企微渠道身份，保留本次回调实际使用的客服账号，供坐席回复时原路发送。 */
    public void bindKfChannel(String conversationId, String externalUserId, String openKfId) {
        jdbc.update("UPDATE conversation SET channel = 'kf', external_userid = ?, open_kfid = ? WHERE conversation_id = ?",
                externalUserId, openKfId, conversationId);
    }

    public void bindKfChannel(String conversationId, String externalUserId, String openKfId,
                              Long tenantId, Long kfAccountId) {
        if (tenantId == null || kfAccountId == null) {
            bindKfChannel(conversationId, externalUserId, openKfId);
            return;
        }
        jdbc.update("UPDATE conversation SET channel='kf', external_userid=?, open_kfid=?, " +
                        "tenant_id=?, kf_account_id=? WHERE conversation_id=?",
                externalUserId, openKfId, tenantId, kfAccountId, conversationId);
    }

    /** 按企微客服账号和客户身份查找当前未关闭会话，供会话状态回调同步使用。 */
    public String findOpenKfConversation(String externalUserId, String openKfId) {
        if (externalUserId == null || externalUserId.isBlank()) return null;
        List<String> rows;
        if (openKfId == null || openKfId.isBlank()) {
            rows = jdbc.queryForList(
                    "SELECT conversation_id FROM conversation WHERE channel = 'kf' " +
                            "AND external_userid = ? AND status <> 'closed' " +
                            "ORDER BY update_time DESC, id DESC LIMIT 1",
                    String.class, externalUserId);
        } else {
            rows = jdbc.queryForList(
                    "SELECT conversation_id FROM conversation WHERE channel = 'kf' " +
                            "AND external_userid = ? AND open_kfid = ? AND status <> 'closed' " +
                            "ORDER BY update_time DESC, id DESC LIMIT 1",
                    String.class, externalUserId, openKfId);
        }
        return rows.isEmpty() ? null : rows.get(0);
    }

    public String findOpenKfConversation(String externalUserId, String openKfId,
                                         Long tenantId, Long kfAccountId) {
        if (tenantId == null || kfAccountId == null) {
            return findOpenKfConversation(externalUserId, openKfId);
        }
        if (externalUserId == null || externalUserId.isBlank()) return null;
        List<String> rows = jdbc.queryForList(
                "SELECT conversation_id FROM conversation WHERE channel='kf' AND tenant_id=? " +
                        "AND kf_account_id=? AND external_userid=? AND open_kfid=? " +
                        "AND status<>'closed' ORDER BY update_time DESC, id DESC LIMIT 1",
                String.class, tenantId, kfAccountId, externalUserId, openKfId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 按会话 ID 查归属用户（长期记忆提取时用） */
    public String getUserId(String conversationId) {
        List<String> rows = jdbc.queryForList(
                "SELECT user_id FROM conversation WHERE conversation_id = ?",
                String.class, conversationId);
        return rows.isEmpty() ? "anonymous" : rows.get(0);
    }

    /**
     * 管理端全量会话浏览（会话质检用）：按内容关键词/最近 N 天过滤，按更新时间倒序，支持分页。
     * W26-M2：新增分页，避免全量渲染卡顿。
     * @param keyword  可选，消息内容关键词（LIKE 模糊匹配，任一条消息命中即算）
     * @param days     可选，只看最近 N 天创建的会话（null 或 <=0 表示不过滤）
     * @param page     页码，从 1 开始
     * @param pageSize 每页条数（默认 20，上限 100）
     * @return Map(total 总条数, rows 当前页数据：conversation_id/user_id/first_question/update_time/message_count)
     */
    public Map<String, Object> queryConversations(String keyword, Integer days, Integer page, Integer pageSize) {
        int p = (page == null || page < 1) ? 1 : page;
        int size = (pageSize == null || pageSize < 1) ? 20 : Math.min(pageSize, 100);
        StringBuilder where = new StringBuilder(" WHERE 1=1 ");
        List<Object> args = new ArrayList<>();
        if (keyword != null && !keyword.isBlank()) {
            where.append("AND EXISTS (SELECT 1 FROM message m WHERE m.conversation_id = c.conversation_id " +
                    "AND m.content ILIKE ?) ");
            args.add("%" + keyword.trim() + "%");
        }
        if (days != null && days > 0) {
            where.append("AND c.create_time >= now() - (? * interval '1 day') ");
            args.add(days);
        }
        AdminDataScope.appendAnd(where, args, "c");
        String whereSql = where.toString();
        Integer total = jdbc.queryForObject(
                "SELECT count(*) FROM conversation c" + whereSql, Integer.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add((p - 1) * size);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT c.conversation_id, c.user_id, c.update_time, " +
                "  (SELECT content FROM message m WHERE m.conversation_id = c.conversation_id " +
                "    AND m.role = 'user' ORDER BY create_time, id LIMIT 1) AS first_question, " +
                "  (SELECT count(*) FROM message m WHERE m.conversation_id = c.conversation_id) AS message_count " +
                "FROM conversation c" + whereSql + " ORDER BY c.update_time DESC, c.id DESC LIMIT ? OFFSET ?",
                pageArgs.toArray());
        return Map.of("total", total == null ? 0 : total, "rows", rows);
    }

    /**
     * 顾客侧「我的历史会话」列表（仅网页渠道）：强制 user_id = 当前 Cookie 身份，杜绝越权；
     * 只列最近 90 天，按最近活跃倒序，服务端分页。
     */
    public Map<String, Object> listConversationsOwned(String userId, Integer page, Integer pageSize) {
        int p = (page == null || page < 1) ? 1 : page;
        int size = (pageSize == null || pageSize < 1) ? 10 : Math.min(pageSize, 20);
        String where = " WHERE c.user_id = ? AND c.channel = 'web' " +
                "AND c.create_time >= now() - interval '90 days' ";
        List<Object> args = new ArrayList<>(List.of(userId));
        Integer total = jdbc.queryForObject(
                "SELECT count(*) FROM conversation c" + where, Integer.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add((p - 1) * size);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT c.conversation_id, c.status, c.update_time, " +
                        "  (SELECT content FROM message m WHERE m.conversation_id = c.conversation_id " +
                        "    AND m.role = 'user' ORDER BY create_time, id LIMIT 1) AS first_question, " +
                        "  (SELECT count(*) FROM message m WHERE m.conversation_id = c.conversation_id) AS message_count " +
                        "FROM conversation c" + where + " ORDER BY c.update_time DESC, c.id DESC LIMIT ? OFFSET ?",
                pageArgs.toArray());
        return Map.of("total", total == null ? 0 : total, "page", p, "pageSize", size, "rows", rows);
    }

    /** 某会话的全部消息（前端刷新后恢复聊天界面用） */
    public List<Map<String, Object>> listMessages(String conversationId) {
        AdminDataScope.Condition scope = AdminDataScope.condition("c");
        if (scope.restricted()) {
            List<Object> scopeArgs = new ArrayList<>();
            scopeArgs.add(conversationId);
            scopeArgs.addAll(scope.args());
            Integer allowed = jdbc.queryForObject(
                    "SELECT count(*) FROM conversation c WHERE c.conversation_id=? AND " + scope.sql(),
                    Integer.class, scopeArgs.toArray());
            if (allowed == null || allowed == 0) return List.of();
        }
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, role, content, match_type, decision_trace, create_time FROM message " +
                "WHERE conversation_id = ? ORDER BY create_time, id",
                conversationId);
        if (rows.isEmpty()) return rows;
        List<Long> ids = rows.stream().map(r -> ((Number) r.get("id")).longValue()).toList();
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        List<Map<String, Object>> attachments = jdbc.queryForList(
                "SELECT message_id, name, url, attachment_type AS type FROM message_attachment " +
                        "WHERE message_id IN (" + placeholders + ") ORDER BY id",
                ids.toArray());
        Map<Long, List<Map<String, Object>>> byMessage = new java.util.HashMap<>();
        for (Map<String, Object> attachment : attachments) {
            long messageId = ((Number) attachment.remove("message_id")).longValue();
            byMessage.computeIfAbsent(messageId, ignored -> new java.util.ArrayList<>()).add(attachment);
        }
        List<Map<String, Object>> cardRows = jdbc.queryForList(
                "SELECT message_id, title, description, url, thumbnail_url FROM message_link_card " +
                        "WHERE message_id IN (" + placeholders + ") ORDER BY id",
                ids.toArray());
        Map<Long, List<Map<String, Object>>> cardsByMessage = new java.util.HashMap<>();
        for (Map<String, Object> cardRow : cardRows) {
            long messageId = ((Number) cardRow.remove("message_id")).longValue();
            Map<String, Object> card = new java.util.LinkedHashMap<>();
            card.put("title", cardRow.get("title"));
            card.put("description", cardRow.get("description"));
            card.put("url", cardRow.get("url"));
            card.put("thumbnailUrl", cardRow.get("thumbnail_url"));
            cardsByMessage.computeIfAbsent(messageId, ignored -> new java.util.ArrayList<>()).add(card);
        }
        List<Map<String, Object>> result = new java.util.ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            long id = ((Number) row.get("id")).longValue();
            Map<String, Object> item = new java.util.LinkedHashMap<>(row);
            item.remove("id");
            item.put("images", byMessage.getOrDefault(id, List.of()));
            item.put("linkCards", cardsByMessage.getOrDefault(id, List.of()));
            String role = String.valueOf(item.get("role"));
            String content = String.valueOf(item.getOrDefault("content", ""));
            item.put("legacyRisk", !"user".equals(role) && containsLegacyRisk(content));
            result.add(item);
        }
        return result;
    }

    private String limit(String value, int maxLength) {
        if (value == null) return "";
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private boolean containsLegacyRisk(String content) {
        if (content == null || content.isBlank()) return false;
        return LEGACY_RISK_PHRASES.stream().anyMatch(content::contains);
    }

    /**
     * 查询会话的转人工状态（带归属校验）：前端切换/刷新会话后恢复"人工服务中/排队中"提示。
     * @return Map(status, agent_name)；会话不存在或不属于该 userId 返回 null
     */
    public Map<String, Object> getStatusOwned(String conversationId, String userId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT c.status, a.name AS agent_name, c.queue_time FROM conversation c " +
                "LEFT JOIN agent a ON c.agent_id = a.id " +
                "WHERE c.conversation_id = ? AND c.user_id = ?",
                conversationId, userId);
        if (rows.isEmpty()) return null;
        Map<String, Object> st = rows.get(0);
        // 排队位次：按入队时间 FIFO，算前面还有几个排队会话（UX 走查：排队无位次客户焦虑）
        if ("need_human".equals(st.get("status")) && st.get("queue_time") != null) {
            Integer position = jdbc.queryForObject(
                    "SELECT count(*) + 1 FROM conversation " +
                    "WHERE status = 'need_human' AND queue_time < ?",
                    Integer.class, st.get("queue_time"));
            st.put("queuePosition", position == null ? 1 : position);
            // P1-5：预计等待时间（基于近 30 天历史中位接入时长），样本不足时为 null
            st.put("estimatedWaitMinutes", estimateWaitMinutes());
        }
        return st;
    }

    /**
     * 排队预计等待（分钟）：取近 30 天网页渠道「入队 → 首条坐席消息」时长的中位数。
     * 中位数对个别超长会话不敏感；有效样本少于 3 条时返回 null（前端不展示估算）。
     * 任何异常都不影响状态查询主流程。
     */
    private Integer estimateWaitMinutes() {
        try {
            List<Double> waits = jdbc.queryForList(
                    "SELECT EXTRACT(EPOCH FROM (fa.first_agent_msg - c.queue_time)) / 60.0 " +
                    "FROM conversation c " +
                    "JOIN (SELECT conversation_id, MIN(create_time) AS first_agent_msg FROM message " +
                    "      WHERE role = 'agent' GROUP BY conversation_id) fa " +
                    "  ON fa.conversation_id = c.conversation_id " +
                    "WHERE c.queue_time IS NOT NULL AND c.channel = 'web' " +
                    "  AND c.create_time >= now() - interval '30 days'",
                    Double.class).stream()
                    .filter(w -> w != null && w >= 0)
                    .sorted()
                    .toList();
            if (waits.size() < 3) return null;
            double median = waits.get(waits.size() / 2);
            return (int) Math.min(480, Math.ceil(median));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 某会话的全部消息（带归属校验）：仅当会话属于该 userId 才返回，否则返回 null。
     * 修复 P0-2：防止任意 conversationId 越权读取他人聊天记录。
     */
    public List<Map<String, Object>> listMessagesOwned(String conversationId, String userId) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM conversation WHERE conversation_id = ? AND user_id = ?",
                Integer.class, conversationId, userId);
        if (n == null || n == 0) {
            return null;
        }
        return listMessages(conversationId);
    }
}
