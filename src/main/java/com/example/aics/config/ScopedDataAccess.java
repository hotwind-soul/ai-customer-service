package com.example.aics.config;

import com.example.aics.channel.TenantExecutionContext;

import java.util.ArrayList;
import java.util.List;

/** 合并企微执行上下文和后台管理员数据范围，生成资源表的 SQL 过滤条件。 */
public final class ScopedDataAccess {

    private ScopedDataAccess() {}

    public record Condition(String sql, List<Object> args) {
        public boolean active() { return sql != null && !sql.isBlank(); }
    }

    public static Condition condition(String alias) {
        String prefix = alias == null || alias.isBlank() ? "" : alias + ".";
        List<String> clauses = new ArrayList<>();
        List<Object> args = new ArrayList<>();
        TenantExecutionContext.Scope runtime = TenantExecutionContext.current();
        if (runtime != null && runtime.tenantId() > 0) {
            clauses.add("(" + prefix + "tenant_id IS NULL OR " + prefix + "tenant_id=?)");
            args.add(runtime.tenantId());
            if (runtime.kfAccountId() != null && runtime.kfAccountId() > 0) {
                clauses.add("(" + prefix + "kf_account_id IS NULL OR " + prefix + "kf_account_id=?)");
                args.add(runtime.kfAccountId());
            }
        } else if (!AdminDataScope.authenticatedAdminRequest()) {
            // 网页客户、定时任务和无上下文后台线程只能使用全局资源。
            // 已鉴权管理请求由下面的 AdminDataScope 决定全量或受限范围。
            clauses.add(prefix + "tenant_id IS NULL");
            clauses.add(prefix + "kf_account_id IS NULL");
        }
        AdminDataScope.Condition admin = AdminDataScope.condition(alias);
        if (admin.restricted()) {
            clauses.add("(" + admin.sql() + ")");
            args.addAll(admin.args());
        }
        return new Condition(String.join(" AND ", clauses), List.copyOf(args));
    }

    public static void appendAnd(StringBuilder sql, List<Object> args, String alias) {
        Condition scope = condition(alias);
        if (!scope.active()) return;
        sql.append(" AND ").append(scope.sql());
        args.addAll(scope.args());
    }
}
