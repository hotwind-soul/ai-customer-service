package com.example.aics.config;

import com.example.aics.channel.TenantExecutionContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminResourceScopeServiceTest {

    @AfterEach
    void clear() {
        RequestContextHolder.resetRequestAttributes();
        assertThat(TenantExecutionContext.current()).isNull();
    }

    @Test
    void validatesOwnershipAndPropagatesSelectedScopeOnlyForOperation() {
        bindAdmin(List.of(7L), List.of(21L));
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(contains("FROM wecom_tenant"), eq(Integer.class), eq(7L))).thenReturn(1);
        when(jdbc.queryForObject(contains("FROM wecom_kf_account"), eq(Integer.class), eq(21L), eq(7L))).thenReturn(1);
        AdminResourceScopeService service = new AdminResourceScopeService(jdbc);

        String value = service.execute(7L, 21L, () -> {
            TenantExecutionContext.Scope scope = TenantExecutionContext.current();
            return scope.tenantId() + ":" + scope.kfAccountId();
        });

        assertThat(value).isEqualTo("7:21");
        assertThat(TenantExecutionContext.current()).isNull();
        verify(jdbc).queryForObject(contains("FROM wecom_kf_account"), eq(Integer.class), eq(21L), eq(7L));
    }

    @Test
    void rejectsUnassignedTenantBeforeWriting() {
        bindAdmin(List.of(7L), List.of());
        AdminResourceScopeService service = new AdminResourceScopeService(mock(JdbcTemplate.class));

        assertThatThrownBy(() -> service.execute(8L, null, () -> "unexpected"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("数据范围");
    }

    private void bindAdmin(List<Long> tenants, List<Long> accounts) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute("aics.admin.role", "admin");
        request.setAttribute("aics.admin.tenantIds", tenants);
        request.setAttribute("aics.admin.kfAccountIds", accounts);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }
}
