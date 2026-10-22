package com.example.aics.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AdminDataScopeTest {

    @AfterEach
    void clearRequest() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void ordinaryAdminCanOnlyUseAssignedEnterpriseAndKfAccount() {
        bindAdmin(List.of(3L, 5L), List.of(11L));

        AdminDataScope.Condition condition = AdminDataScope.condition("c");

        assertThat(condition.restricted()).isTrue();
        assertThat(condition.sql()).contains("c.tenant_id IS NULL", "c.tenant_id IN", "c.kf_account_id IN");
        assertThat(condition.args()).containsExactly(3L, 5L, 11L);
        assertThat(AdminDataScope.allowsResource(3L, 11L)).isTrue();
        assertThat(AdminDataScope.allowsResource(3L, 12L)).isFalse();
        assertThat(AdminDataScope.allowsResource(9L, null)).isFalse();
        assertThat(AdminDataScope.allowsResource(null, null)).isTrue();
    }

    @Test
    void emptyKfSelectionMeansAllAccountsInsideAssignedEnterprise() {
        bindAdmin(List.of(3L), List.of());

        assertThat(AdminDataScope.allowsResource(3L, 99L)).isTrue();
        assertThat(AdminDataScope.condition("c").sql()).doesNotContain("kf_account_id IN");
    }

    @Test
    void 无租户上下文和后台身份时默认只允许全局资源() {
        ScopedDataAccess.Condition condition = ScopedDataAccess.condition("r");

        assertThat(condition.active()).isTrue();
        assertThat(condition.sql()).contains("r.tenant_id IS NULL", "r.kf_account_id IS NULL");
        assertThat(condition.args()).isEmpty();
    }

    @Test
    void 超级管理员后台请求可以读取全部资源() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute("aics.admin.role", "super_admin");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        assertThat(AdminDataScope.authenticatedAdminRequest()).isTrue();
        assertThat(ScopedDataAccess.condition("r").active()).isFalse();
    }

    @Test
    void 后台任务线程可以继承普通管理员数据范围并在结束后清理() {
        AdminDataScope.AccessScope scope = new AdminDataScope.AccessScope(
                true, List.of(3L, 5L), List.of(11L));

        try (AdminDataScope.Lease ignored = AdminDataScope.use(scope)) {
            assertThat(AdminDataScope.authenticatedAdminRequest()).isTrue();
            assertThat(AdminDataScope.condition("c").args()).containsExactly(3L, 5L, 11L);
            assertThat(ScopedDataAccess.condition("c").sql()).contains("c.tenant_id IN");
        }

        assertThat(AdminDataScope.authenticatedAdminRequest()).isFalse();
        assertThat(ScopedDataAccess.condition("c").sql()).contains("c.tenant_id IS NULL");
    }

    private void bindAdmin(List<Long> tenants, List<Long> accounts) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute("aics.admin.role", "admin");
        request.setAttribute("aics.admin.tenantIds", tenants);
        request.setAttribute("aics.admin.kfAccountIds", accounts);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }
}
