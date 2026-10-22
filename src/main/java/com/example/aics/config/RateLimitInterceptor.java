package com.example.aics.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 聊天接口限流：每个 IP 每分钟最多 30 次（计数放 Redis，多实例共享同一 Redis 即共享限流）。
 * 防止恶意刷接口把 DeepSeek 额度打爆。正常客户一分钟问不了几句，30 次很宽裕。
 * Redis 故障时降级到进程内 localFallback（此时限流仅本实例有效，属故障窗口的兜底，非常态）。
 */
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private static final int LIMIT_PER_MINUTE = 30;

    private final StringRedisTemplate redis;
    private final ConcurrentHashMap<String, LocalWindow> localFallback = new ConcurrentHashMap<>();

    private static final class LocalWindow {
        final long minute = System.currentTimeMillis() / 60_000L;
        final AtomicInteger count = new AtomicInteger();
    }

    public RateLimitInterceptor(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) throws Exception {
        String ip = request.getRemoteAddr();
        String key = "rl:" + ip;
        try {
            Long count = redis.opsForValue().increment(key);
            if (count != null && count == 1L) {
                redis.expire(key, Duration.ofMinutes(1));
            }
            if (count != null && count > LIMIT_PER_MINUTE) {
                // 兜底补 TTL：若首次 EXPIRE 失败，键会永久存在且计数只增不减，
                // 该 IP 将永久 429（且无法自愈）。这里在即将限流时补一次，保证能自动解除。
                Long ttl = redis.getExpire(key);
                if (ttl != null && ttl < 0) {
                    redis.expire(key, Duration.ofMinutes(1));
                }
                response.setStatus(429);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"error\":\"您提问太频繁啦，请稍等一分钟再试\"}");
                return false;
            }
        } catch (Exception ignored) {
            // Redis 故障时使用进程内后备限流，避免故障窗口被无限刷模型额度。
            long minute = System.currentTimeMillis() / 60_000L;
            LocalWindow window = localFallback.compute(ip, (k, current) ->
                    current == null || current.minute != minute ? new LocalWindow() : current);
            if (window.count.incrementAndGet() > LIMIT_PER_MINUTE) {
                response.setStatus(429);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"error\":\"您提问太频繁啦，请稍等一分钟再试\"}");
                return false;
            }
            if (localFallback.size() > 10_000) {
                localFallback.entrySet().removeIf(e -> e.getValue().minute < minute);
            }
        }
        return true;
    }
}
