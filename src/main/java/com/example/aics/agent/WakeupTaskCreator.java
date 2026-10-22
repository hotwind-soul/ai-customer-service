package com.example.aics.agent;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 用数据库事务锁原子创建唤醒任务，兼容多实例并发扫描。 */
@Service
public class WakeupTaskCreator {

    private final JdbcTemplate jdbc;

    public WakeupTaskCreator(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public boolean createIfAbsent(String userId, Object lastActive, String profile, String script) {
        return createIfAbsent(userId, lastActive, profile, script, null, null);
    }

    @Transactional
    public boolean createIfAbsent(String userId, Object lastActive, String profile, String script,
                                  Long tenantId, Long kfAccountId) {
        // PostgreSQL 事务级 advisory lock：同一用户跨进程串行，事务结束自动释放。
        jdbc.query("SELECT pg_advisory_xact_lock(hashtext(?))", resultSet -> {
            if (resultSet.next()) { /* 读取一行即代表锁已获得 */ }
            return null;
        }, userId);
        int inserted = jdbc.update(
                "INSERT INTO wake_task(user_id, last_active, profile, script, tenant_id, kf_account_id) " +
                        "SELECT ?,?,?,?,?,? WHERE NOT EXISTS (" +
                        "SELECT 1 FROM wake_task WHERE user_id = ? AND status IN ('new','delivered'))",
                userId, lastActive, profile, script, tenantId, kfAccountId, userId);
        return inserted > 0;
    }

    /**
     * 批量运营手动创建（P2-11）：除待办去重外，额外增加冷却期频控——
     * 同一用户 cooldownDays 天内已建过任务（无论状态）则跳过，防止营销骚扰。
     */
    @Transactional
    public boolean createManual(String userId, Object lastActive, String script,
                                Long tenantId, Long kfAccountId, int cooldownDays) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtext(?))", resultSet -> {
            if (resultSet.next()) { /* 读取一行即代表锁已获得 */ }
            return null;
        }, userId);
        int inserted = jdbc.update(
                "INSERT INTO wake_task(user_id, last_active, script, tenant_id, kf_account_id) " +
                        "SELECT ?,?,?,?,? WHERE NOT EXISTS (" +
                        "SELECT 1 FROM wake_task WHERE user_id = ? AND status IN ('new','delivered')) " +
                        "AND NOT EXISTS (" +
                        "SELECT 1 FROM wake_task WHERE user_id = ? " +
                        "AND create_time >= now() - make_interval(days => ?))",
                userId, lastActive, script, tenantId, kfAccountId, userId, userId, cooldownDays);
        return inserted > 0;
    }
}
