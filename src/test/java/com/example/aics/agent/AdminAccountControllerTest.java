package com.example.aics.agent;

import com.example.aics.audit.AuditService;
import com.example.aics.config.WebAuthCookieService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AdminAccountControllerTest {

    private AgentAccountService accounts;
    private AuditService audit;
    private AdminAccountController controller;
    private MockHttpServletRequest request;

    @BeforeEach
    void setUp() {
        accounts = mock(AgentAccountService.class);
        audit = mock(AuditService.class);
        controller = new AdminAccountController(accounts, audit,
                new WebAuthCookieService(mock(com.example.aics.config.ConfigService.class)));
        request = new MockHttpServletRequest();
        request.addHeader("X-Admin-Token", "super-token");
        when(accounts.adminSession("super-token")).thenReturn(Map.of(
                "id", 1L, "username", "owner", "role", AgentAccountService.ROLE_SUPER_ADMIN));
        when(accounts.verifyCurrentPassword("super-token", "owner-password")).thenReturn(true);
    }

    @Test
    void createRequiresMatchingRepeatedPassword() {
        Map<String, Object> body = Map.of(
                "username", "editor",
                "name", "内容编辑",
                "password", "simple123",
                "confirmPassword", "different123",
                "currentPassword", "owner-password",
                "permissions", List.of("report.view"));

        assertThatThrownBy(() -> controller.create(body, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("两次输入的密码不一致");
        verifyNoInteractions(audit);
    }

    @Test
    void passwordResetRequiresMatchingRepeatedPassword() {
        assertThatThrownBy(() -> controller.resetPassword(1L, Map.of(
                "password", "simple123", "confirmPassword", "simple124",
                "currentPassword", "owner-password"), request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("两次输入的密码不一致");
    }

    @Test
    void profileUpdateDelegatesForSuperAdminAndAudits() {
        Map<String, Object> result = controller.updateProfile(1L,
                Map.of("username", "owner", "name", "超级管理员",
                        "currentPassword", "owner-password"), request);

        assertThat(result).containsEntry("ok", true);
        verify(accounts).updateAdminProfile(1L, "owner", "超级管理员");
        verify(audit).log("UPDATE_ADMIN_PROFILE", "管理员ID=1，账号=owner", "owner");
    }

    @Test
    void matchingPasswordIsPassedToService() {
        Map<String, Object> result = controller.resetPassword(2L, Map.of(
                "password", "simple123", "confirmPassword", "simple123",
                "currentPassword", "owner-password"), request);

        assertThat(result).containsEntry("ok", true);
        verify(accounts).resetAdminPassword(2L, "simple123");
        verify(audit).log("RESET_ADMIN_PASSWORD", "管理员ID=2", "owner");
    }

    @Test
    void everySensitiveOperationRequiresServerSideReauthentication() {
        assertThatThrownBy(() -> controller.updatePermissions(2L,
                Map.of("permissions", List.of("report.view")), request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("二次验证失败");
        verifyNoInteractions(audit);
    }
}
