package com.example.aics.state;

import com.example.aics.config.ConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 状态外置（ADR-003）：把"进程内存里的业务状态"迁到 Redis。
 *
 * 之前这些状态在 ConcurrentHashMap 里，应用重启即丢、多实例部署时各实例互相看不见。
 * 现在统一收口到这里，TTL 策略沿用原逻辑：
 *   booking:{convId}  预约进行中的信息槽（JSON）         TTL 30 分钟（原 BOOKING_TTL_MS）
 *   auth:{token}      坐席登录 token → agentId           TTL 24 小时（原内存态无 TTL，重启清空；
 *                                                         现在持久化后设一天，避免 token 无限堆积）
 */
@Component
public class StateStore {

    private static final Logger log = LoggerFactory.getLogger(StateStore.class);
    private static final Duration BOOKING_TTL = Duration.ofMinutes(30);

    private static final String BOOKING_PREFIX = "booking:";
    private static final String AUTH_PREFIX = "auth:";
    private static final String AUTH_AGENT_PREFIX = "auth-agent:";

    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final ConfigService configService;

    public StateStore(StringRedisTemplate redis, ConfigService configService) {
        this.redis = redis;
        this.json = new ObjectMapper();
        this.configService = configService;
    }

    // ==================== 预约流程 ====================

    /** 读取预约进度；没有或已过期（Redis TTL 自动消失）返回 null */
    @SuppressWarnings("unchecked")
    public Map<String, String> getBooking(String conversationId) {
        String raw;
        try {
            raw = redis.opsForValue().get(BOOKING_PREFIX + conversationId);
        } catch (RuntimeException e) {
            log.warn("读取 Redis 预约进度失败，本轮按无预约流程继续: conversationId={}, error={}",
                    conversationId, e.toString());
            return null;
        }
        if (raw == null) {
            return null;
        }
        try {
            return json.readValue(raw, Map.class);
        } catch (Exception e) {
            // 数据损坏（理论不发生）：清掉让流程重新开始，不阻塞
            try { redis.delete(BOOKING_PREFIX + conversationId); } catch (RuntimeException ignored) { }
            return null;
        }
    }

    /** 保存预约进度（30 分钟未推进自动作废，语义与原 BOOKING_TTL_MS 一致） */
    public void putBooking(String conversationId, Map<String, String> slots) {
        try {
            redis.opsForValue().set(BOOKING_PREFIX + conversationId,
                    json.writeValueAsString(slots), BOOKING_TTL);
        } catch (Exception e) {
            log.warn("保存 Redis 预约进度失败，本轮不阻断对话: conversationId={}, error={}",
                    conversationId, e.toString());
        }
    }

    /** 取消/完成预约时清理进度 */
    public void removeBooking(String conversationId) {
        try {
            redis.delete(BOOKING_PREFIX + conversationId);
        } catch (RuntimeException e) {
            log.warn("清理 Redis 预约进度失败: conversationId={}, error={}", conversationId, e.toString());
        }
    }

    // ==================== 已完成预约标记（P1：防确认语二次触发预约） ====================

    private static final Duration BOOKED_TTL = Duration.ofHours(24);
    private static final String BOOKED_PREFIX = "booked:";

    /** 预约落单成功后标记（TTL 24h）：客户确认语（"对，帮我登记吧"）不再重新触发预约流程 */
    public void markBooked(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) return;
        try {
            redis.opsForValue().set(BOOKED_PREFIX + conversationId, "1", BOOKED_TTL);
        } catch (RuntimeException e) {
            log.warn("写入 Redis 已预约标记失败: conversationId={}, error={}", conversationId, e.toString());
        }
    }

    /** 该会话最近 24h 内是否已完成过预约 */
    public boolean isBooked(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) return false;
        try {
            return Boolean.TRUE.equals(redis.hasKey(BOOKED_PREFIX + conversationId));
        } catch (RuntimeException e) {
            log.warn("读取 Redis 已预约标记失败，本轮按未预约继续: conversationId={}, error={}",
                    conversationId, e.toString());
            return false;
        }
    }

    /** 客户明确要再约/改约时清除标记，允许重新走预约流程 */
    public void clearBooked(String conversationId) {
        if (conversationId != null) {
            try {
                redis.delete(BOOKED_PREFIX + conversationId);
            } catch (RuntimeException e) {
                log.warn("清理 Redis 已预约标记失败: conversationId={}, error={}",
                        conversationId, e.toString());
            }
        }
    }

    // ==================== 坐席登录 token ====================

    /** 登录成功：token → agentId（TTL 24 小时） */
    public void putAuth(String token, long agentId) {
        // 登录时长从 system_config 读 auth.token_ttl_hours（默认 24h），改库立即生效（P1-5）。
        Duration ttl = Duration.ofHours(configService.getInt("auth.token_ttl_hours", 24));
        redis.opsForValue().set(AUTH_PREFIX + token, String.valueOf(agentId), ttl);
        String agentKey = AUTH_AGENT_PREFIX + agentId;
        redis.opsForSet().add(agentKey, token);
        redis.expire(agentKey, ttl);
    }

    /** 校验 token，返回 agentId；无效/过期返回 null */
    public Long getAuth(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        String v = redis.opsForValue().get(AUTH_PREFIX + token);
        if (v == null) {
            return null;
        }
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            redis.delete(AUTH_PREFIX + token);
            return null;
        }
    }

    /** 登出/失效：删除 token */
    public void removeAuth(String token) {
        if (token != null) {
            Long agentId = getAuth(token);
            redis.delete(AUTH_PREFIX + token);
            if (agentId != null) redis.opsForSet().remove(AUTH_AGENT_PREFIX + agentId, token);
        }
    }

    /** 密码修改、账号停用或权限变更后撤销该账号全部登录 token。 */
    public void removeAllAuth(long agentId) {
        String key = AUTH_AGENT_PREFIX + agentId;
        Set<String> tokens = redis.opsForSet().members(key);
        if (tokens != null && !tokens.isEmpty()) {
            redis.delete(tokens.stream().map(t -> AUTH_PREFIX + t).toList());
        }
        // 兼容升级前未维护反向索引的 token：用 SCAN 非阻塞遍历，且只认 UUID 形态的 token 键。
        // 不能用 KEYS auth:* 再按值比对——同前缀的限流计数键 auth:fail:* 的值恰好也是小整数，
        // 会被误删从而削弱防爆破阈值；KEYS 本身在大 keyspace 下还会阻塞 Redis 单线程。
        List<String> stale = new ArrayList<>();
        try {
            redis.execute((RedisCallback<Void>) connection -> {
                ScanOptions options = ScanOptions.scanOptions().match(AUTH_PREFIX + "*").count(500).build();
                try (Cursor<byte[]> cursor = connection.keyCommands().scan(options)) {
                    while (cursor.hasNext()) {
                        String candidate = new String(cursor.next(), StandardCharsets.UTF_8);
                        if (!LEGACY_TOKEN_KEY.matcher(candidate).matches()) continue;
                        byte[] raw = connection.stringCommands().get(candidate.getBytes(StandardCharsets.UTF_8));
                        if (raw != null && String.valueOf(agentId).equals(new String(raw, StandardCharsets.UTF_8))) {
                            stale.add(candidate);
                        }
                    }
                }
                return null;
            });
        } catch (Exception e) {
            log.warn("扫描历史 token 失败（已忽略，不影响本次注销）：{}", e.getMessage());
        }
        if (!stale.isEmpty()) redis.delete(stale);
        redis.delete(key);
    }

    /** 历史 token 键形态：auth:&lt;UUID&gt;。用于与 auth:fail:* 等同前缀计数键区分。 */
    private static final Pattern LEGACY_TOKEN_KEY = Pattern.compile(
            "^auth:[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    // ==================== 通用幂等标记（channel 回调去重等） ====================

    /**
     * SET NX EX：首次写入返回 true，已存在返回 false（幂等去重用，如企微回调按 msgid 去重）。
     * @param key        标记键（调用方自行带前缀，避免与业务 key 冲突，如 "kf:msg:" + msgId）
     * @param value      值（一般填 "1"）
     * @param ttlSeconds 过期秒数
     */
    public Boolean setNx(String key, String value, long ttlSeconds) {
        return redis.opsForValue().setIfAbsent(key, value, Duration.ofSeconds(ttlSeconds));
    }

    /** 是否存在该 key（幂等检查：欢迎语是否已主动推送过） */
    public boolean hasKey(String key) {
        return Boolean.TRUE.equals(redis.hasKey(key));
    }

    /** 读取通用渠道状态（如企微 sync_msg 游标）。 */
    public String getValue(String key) {
        return redis.opsForValue().get(key);
    }

    /** 持久保存通用渠道状态；Redis 数据卷负责跨进程重启保留。 */
    public void putValue(String key, String value) {
        if (key != null && !key.isBlank() && value != null) {
            redis.opsForValue().set(key, value);
        }
    }

    /** 删除通用渠道状态；处理失败时撤销临时幂等标记，以便外部平台重试。 */
    public void removeValue(String key) {
        if (key != null && !key.isBlank()) redis.delete(key);
    }
}
