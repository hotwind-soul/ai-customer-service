package com.example.aics.config;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 当前后台请求的数据范围。超级管理员和非后台调用不加过滤；普通管理员始终只能读取
 * 全局/网页数据以及超级管理员分配给他的企微企业、客服号数据。
 */
public final class AdminDataScope {

    private static final ThreadLocal<AccessScope> OVERRIDE = new ThreadLocal<>();

    private AdminDataScope() {}

    public record Condition(String sql, List<Object> args, boolean restricted) {}
     
    public record AccessScope(boolean restricted, List<Long> tenantIds, List<Long> kfAccountIds) {}

    public static AccessScope current() {
        AccessScope override = OVERRIDE.get();
        if (override != null) return override;
        HttpServletRequest request = currentRequest();
        if (request == null || !"admin".equals(String.valueOf(request.getAttribute("aics.admin.role")))) {
            return new AccessScope(false, List.of(), List.of());
        }
        return new AccessScope(true,
                ids(request.getAttribute("aics.admin.tenantIds")),
                ids(request.getAttribute("aics.admin.kfAccountIds")));
    }

    /** 区分已通过后台拦截器鉴权的请求与普通网页/后台线程，供默认全局隔离使用。 */
    public static boolean authenticatedAdminRequest() {
        if (OVERRIDE.get() != null) return true;
        HttpServletRequest request = currentRequest();
        if (request == null) return false;
        String role = String.valueOf(request.getAttribute("aics.admin.role"));
        return "admin".equals(role) || "super_admin".equals(role);
    }

    /** 全局共享资源始终可见；企业资源必须落在当前普通管理员的数据范围内。 */
    public static boolean allowsResource(Long tenantId, Long kfAccountId) {
        AccessScope scope = current();
        if (!scope.restricted() || tenantId == null) return true;
        if (!scope.tenantIds().contains(tenantId)) return false;
        return kfAccountId == null || scope.kfAccountIds().isEmpty()
                || scope.kfAccountIds().contains(kfAccountId);
    }

    public static Condition condition(String alias) {
        AccessScope access = current();
        if (!access.restricted()) {
            return new Condition("", List.of(), false);
        }
        String prefix = alias == null || alias.isBlank() ? "" : alias + ".";
        List<Long> tenants = access.tenantIds();
        List<Long> accounts = access.kfAccountIds();
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("(").append(prefix).append("tenant_id IS NULL");
        if (!tenants.isEmpty()) {
            sql.append(" OR ").append(prefix).append("tenant_id IN (")
                    .append(placeholders(tenants.size())).append(")");
            args.addAll(tenants);
        }
        sql.append(")");
        if (!accounts.isEmpty()) {
            sql.append(" AND (").append(prefix).append("tenant_id IS NULL OR ")
                    .append(prefix).append("kf_account_id IS NULL OR ")
                    .append(prefix).append("kf_account_id IN (")
                    .append(placeholders(accounts.size())).append("))");
            args.addAll(accounts);
        }
        return new Condition(sql.toString(), List.copyOf(args), true);
    }

    public static void appendAnd(StringBuilder where, List<Object> args, String alias) {
        Condition scope = condition(alias);
        if (!scope.restricted()) return;
        where.append(" AND ").append(scope.sql()).append(' ');
        args.addAll(scope.args());
    }

    /** 把已鉴权请求的数据范围安全传递给受信任的后台任务线程。 */
    public static Lease use(AccessScope scope) {
        AccessScope previous = OVERRIDE.get();
        if (scope == null) OVERRIDE.remove();
        else OVERRIDE.set(new AccessScope(scope.restricted(),
                List.copyOf(scope.tenantIds()), List.copyOf(scope.kfAccountIds())));
        return () -> {
            if (previous == null) OVERRIDE.remove();
            else OVERRIDE.set(previous);
        };
    }

    @FunctionalInterface
    public interface Lease extends AutoCloseable {
        @Override
        void close();
    }

    private static HttpServletRequest currentRequest() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs)) return null;
        return attrs.getRequest();
    }

    private static List<Long> ids(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        List<Long> result = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Number number && number.longValue() > 0) result.add(number.longValue());
        }
        return result.stream().distinct().sorted().toList();
    }

    private static String placeholders(int size) {
        return String.join(",", Collections.nCopies(size, "?"));
    }
}
