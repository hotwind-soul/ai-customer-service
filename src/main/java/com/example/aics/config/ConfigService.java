package com.example.aics.config;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 系统配置：Prompt、门槛值等存数据库，内存缓存 + 手动刷新。
 * 普通配置通过通用接口发布后立即生效；版本化 Prompt 由 PromptReleaseService
 * 在发布或回滚时原子写入，草稿阶段不会进入本服务。
 */
@Service
public class ConfigService {

    private final JdbcTemplate jdbc;
    private final Map<String, String> cache = new ConcurrentHashMap<>();

    public ConfigService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public String get(String key, String defaultValue) {
        String cached = cache.get(key);
        if (cached != null) return cached;
        List<String> rows = jdbc.queryForList(
                "SELECT value FROM system_config WHERE key = ?", String.class, key);
        // 只缓存数据库中真实存在的值：查询为空时返回默认值但不入缓存，
        // 这样裸 SQL 插入/更新 system_config 后无需重启或 reload 即可生效（W28 修正）。
        if (rows.isEmpty() || rows.get(0) == null) {
            return defaultValue;
        }
        String value = rows.get(0);
        cache.put(key, value);
        return value;
    }

    public double getDouble(String key, double defaultValue) {
        try {
            return Double.parseDouble(get(key, String.valueOf(defaultValue)));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public int getInt(String key, int defaultValue) {
        try {
            return Integer.parseInt(get(key, String.valueOf(defaultValue)));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        String value = get(key, String.valueOf(defaultValue));
        if ("true".equalsIgnoreCase(value)) return true;
        if ("false".equalsIgnoreCase(value)) return false;
        return defaultValue;
    }

    public void set(String key, String value) {
        jdbc.update(
                "INSERT INTO system_config(key, value, update_time) VALUES (?, ?, now()) " +
                "ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, update_time = now()",
                key, value);
        clearCacheAfterCompletion(List.of(key));
    }

    /** 批量配置在一个事务中发布，避免保存到一半失败后线上处于混合状态。 */
    @Transactional
    public Map<String, String> setAll(Map<String, String> values) {
        Map<String, String> saved = new LinkedHashMap<>();
        values.forEach((key, value) -> {
            jdbc.update(
                    "INSERT INTO system_config(key, value, update_time) VALUES (?, ?, now()) " +
                    "ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, update_time = now()",
                    key, value);
            saved.put(key, value);
        });
        clearCacheAfterCompletion(saved.keySet());
        return saved;
    }

    /**
     * 清缓存必须在事务结束之后：若在提交前清，并发 get() 会读不到缓存 → 从库里读到"尚未提交的旧值"
     * → 把旧值写回缓存，若其晚于本次 remove，新配置在该实例上会长期不生效（直到重启或 reload）。
     * 无事务时（set 的常规调用）直接清即可。
     */
    private void clearCacheAfterCompletion(Collection<String> keys) {
        List<String> snapshot = List.copyOf(keys);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    snapshot.forEach(cache::remove);
                }
            });
            return;
        }
        snapshot.forEach(cache::remove);
    }

    public void reload() {
        cache.clear();
    }
}
