package com.example.aics.config;

import com.example.aics.channel.TenantExecutionContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.io.IOException;

/** 校验管理端选择的资源归属，并在本次写操作期间建立企业/客服号上下文。 */
@Service
public class AdminResourceScopeService {

    @FunctionalInterface
    public interface IoSupplier<T> {
        T get() throws IOException;
    }

    private final JdbcTemplate jdbc;

    public AdminResourceScopeService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public <T> T execute(Long tenantId, Long kfAccountId, Supplier<T> operation) {
        Long tenant = positiveOrNull(tenantId);
        Long account = positiveOrNull(kfAccountId);
        validate(tenant, account);
        if (tenant == null) return operation.get();
        try (TenantExecutionContext.Lease ignored = TenantExecutionContext.use(tenant, account)) {
            return operation.get();
        }
    }

    public void run(Long tenantId, Long kfAccountId, Runnable operation) {
        execute(tenantId, kfAccountId, () -> {
            operation.run();
            return null;
        });
    }

    public <T> T executeIo(Long tenantId, Long kfAccountId, IoSupplier<T> operation) throws IOException {
        Long tenant = positiveOrNull(tenantId);
        Long account = positiveOrNull(kfAccountId);
        validate(tenant, account);
        if (tenant == null) return operation.get();
        try (TenantExecutionContext.Lease ignored = TenantExecutionContext.use(tenant, account)) {
            return operation.get();
        }
    }

    public Map<String, Object> catalog() {
        AdminDataScope.AccessScope access = AdminDataScope.current();
        List<Object> tenantArgs = new ArrayList<>();
        String tenantFilter = idFilter("t.id", access.restricted() ? access.tenantIds() : List.of(),
                access.restricted(), tenantArgs);
        List<Map<String, Object>> tenants = jdbc.queryForList(
                "SELECT t.id, t.tenant_code AS code, t.name, t.enabled FROM wecom_tenant t" +
                        tenantFilter + " ORDER BY t.name, t.id", tenantArgs.toArray());

        List<Object> accountArgs = new ArrayList<>();
        StringBuilder accountWhere = new StringBuilder();
        if (access.restricted()) {
            accountWhere.append(idFilter("a.tenant_id", access.tenantIds(), true, accountArgs));
            if (!access.kfAccountIds().isEmpty()) {
                accountWhere.append(" AND a.id IN (")
                        .append(placeholders(access.kfAccountIds().size())).append(')');
                accountArgs.addAll(access.kfAccountIds());
            }
        }
        List<Map<String, Object>> accounts = jdbc.queryForList(
                "SELECT a.id, a.tenant_id AS \"tenantId\", a.open_kfid AS \"openKfid\", " +
                        "a.name, a.enabled FROM wecom_kf_account a" + accountWhere +
                        " ORDER BY a.tenant_id, a.name, a.id", accountArgs.toArray());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("globalAllowed", true);
        result.put("tenants", tenants);
        result.put("accounts", accounts);
        return result;
    }

    private void validate(Long tenantId, Long kfAccountId) {
        if (tenantId == null) {
            if (kfAccountId != null) throw new IllegalArgumentException("选择客服号前必须先选择所属企业");
            return;
        }
        if (!AdminDataScope.allowsResource(tenantId, kfAccountId)) {
            throw new IllegalArgumentException("所选企业或客服号不在当前管理员的数据范围内");
        }
        Integer tenants = jdbc.queryForObject("SELECT count(*) FROM wecom_tenant WHERE id=?",
                Integer.class, tenantId);
        if (tenants == null || tenants == 0) throw new IllegalArgumentException("所选企业不存在");
        if (kfAccountId != null) {
            Integer accounts = jdbc.queryForObject(
                    "SELECT count(*) FROM wecom_kf_account WHERE id=? AND tenant_id=?",
                    Integer.class, kfAccountId, tenantId);
            if (accounts == null || accounts == 0) throw new IllegalArgumentException("所选客服号不属于该企业");
        }
    }

    private String idFilter(String column, List<Long> ids, boolean restricted, List<Object> args) {
        if (!restricted) return "";
        if (ids.isEmpty()) return " WHERE 1=0";
        args.addAll(ids);
        return " WHERE " + column + " IN (" + placeholders(ids.size()) + ")";
    }

    private String placeholders(int size) {
        return String.join(",", Collections.nCopies(size, "?"));
    }

    private Long positiveOrNull(Long value) {
        return value == null || value <= 0 ? null : value;
    }
}
