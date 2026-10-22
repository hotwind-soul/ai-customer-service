package com.example.aics.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;

/** 坐席/管理员登录防爆破：账号+IP 5 次、单 IP 20 次失败后锁定 15 分钟。 */
@Component
public class LoginRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(LoginRateLimiter.class);
    private static final int ACCOUNT_IP_LIMIT = 5;
    private static final int IP_LIMIT = 20;
    private static final Duration WINDOW = Duration.ofMinutes(15);

    private final StringRedisTemplate redis;

    public LoginRateLimiter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public boolean isBlocked(String username, String ip) {
        try {
            return value(accountKey(username, ip)) >= ACCOUNT_IP_LIMIT
                    || value(ipKey(ip)) >= IP_LIMIT;
        } catch (RuntimeException e) {
            log.error("登录限流 Redis 不可用", e);
            throw new LoginProtectionUnavailableException();
        }
    }

    /** 记录一次失败；若本次失败后达到阈值则返回 true。 */
    public boolean recordFailure(String username, String ip) {
        try {
            long accountCount = increment(accountKey(username, ip));
            long ipCount = increment(ipKey(ip));
            return accountCount >= ACCOUNT_IP_LIMIT || ipCount >= IP_LIMIT;
        } catch (RuntimeException e) {
            log.error("记录登录失败次数时 Redis 不可用", e);
            throw new LoginProtectionUnavailableException();
        }
    }

    /** 登录成功只清除该账号+IP 计数；IP 总计数保留，用于抵抗撞库扫描。 */
    public void recordSuccess(String username, String ip) {
        try {
            redis.delete(accountKey(username, ip));
        } catch (RuntimeException e) {
            log.warn("清理登录失败计数失败: {}", e.getMessage());
        }
    }

    private long increment(String key) {
        Long count = redis.opsForValue().increment(key);
        if (count != null && count == 1L) {
            redis.expire(key, WINDOW);
        } else if (count != null) {
            // 兜底：首次 EXPIRE 失败会让键永久存在，计数只增不减 → 该账号+IP 被永久锁定，
            // 且 isBlocked 在密码校验之前执行，正确密码也无法自救。缺 TTL 就补回来。
            Long ttl = redis.getExpire(key);
            if (ttl != null && ttl < 0) {
                redis.expire(key, WINDOW);
            }
        }
        return count == null ? 0L : count;
    }

    private long value(String key) {
        String value = redis.opsForValue().get(key);
        if (value == null) return 0L;
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            redis.delete(key);
            return 0L;
        }
    }

    private String accountKey(String username, String ip) {
        String normalized = (username == null ? "" : username.trim().toLowerCase())
                + "|" + safeIp(ip);
        return "auth:fail:account:" + sha256(normalized);
    }

    private String ipKey(String ip) {
        return "auth:fail:ip:" + sha256(safeIp(ip));
    }

    private String safeIp(String ip) {
        return ip == null || ip.isBlank() ? "unknown" : ip;
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JDK 缺少 SHA-256", e);
        }
    }

    public static class LoginProtectionUnavailableException extends RuntimeException {
        public LoginProtectionUnavailableException() {
            super("登录保护服务暂不可用，请稍后重试");
        }
    }
}
