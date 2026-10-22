package com.example.aics.agent;

import com.example.aics.chat.SessionRegistry;
import com.example.aics.state.StateStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentAccountServiceTest {

    private JdbcTemplate jdbc;
    private SessionRegistry sessions;
    private StateStore stateStore;
    private AgentAccountService service;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        sessions = mock(SessionRegistry.class);
        stateStore = mock(StateStore.class);
        service = new AgentAccountService(jdbc, sessions, stateStore, "");
    }

    @Test
    void existingUsernameAcrossAnyRoleIsRejectedClearly() {
        when(jdbc.queryForList(
                "SELECT role FROM agent WHERE username = ?", String.class, "admin"))
                .thenReturn(java.util.List.of(AgentAccountService.ROLE_SUPER_ADMIN));

        assertThatThrownBy(() -> service.createAgent(" admin ", "客服小王", "simple123"))
                .isInstanceOf(AgentAccountService.AccountAlreadyExistsException.class)
                .hasMessage("该登录账号已被超级管理员使用，不会显示在客服账号列表中，请更换一个账号");

        verify(jdbc, never()).update(eq(
                "INSERT INTO agent(username, name, password, role) VALUES (?,?,?,?)"),
                eq("admin"), eq("客服小王"), anyString(), eq(AgentAccountService.ROLE_AGENT));
    }

    @Test
    void newUsernameCanBeCreated() {
        when(jdbc.queryForList(
                "SELECT role FROM agent WHERE username = ?", String.class, "service01"))
                .thenReturn(java.util.List.of());

        service.createAgent(" service01 ", " 客服小王 ", "simple123");

        verify(jdbc).update(eq(
                "INSERT INTO agent(username, name, password, role) VALUES (?,?,?,?)"),
                eq("service01"), eq("客服小王"), anyString(), eq(AgentAccountService.ROLE_AGENT));
    }

    @Test
    void concurrentUniqueKeyConflictIsTranslatedToBusinessError() {
        List<String> noRoles = List.of();
        List<String> agentRole = List.of(AgentAccountService.ROLE_AGENT);
        when(jdbc.queryForList(
                "SELECT role FROM agent WHERE username = ?", String.class, "service01"))
                .thenReturn(noRoles).thenReturn(agentRole);
        when(jdbc.update(eq(
                "INSERT INTO agent(username, name, password, role) VALUES (?,?,?,?)"),
                eq("service01"), eq("客服小王"), anyString(), eq(AgentAccountService.ROLE_AGENT)))
                .thenThrow(new DuplicateKeyException("agent_username_key"));

        assertThatThrownBy(() -> service.createAgent("service01", "客服小王", "simple123"))
                .isInstanceOf(AgentAccountService.AccountAlreadyExistsException.class)
                .hasMessage("该登录账号已被客服账号使用，请在下方客服账号列表中查看或更换账号");
    }

    @Test
    void failedTakeDoesNotAdvanceRoundRobinTimestamp() {
        when(jdbc.update(
                contains("UPDATE conversation c SET agent_id"),
                eq(7L), eq("conv-1"), eq(7L), eq(7L), eq(7L))).thenReturn(0);

        org.assertj.core.api.Assertions.assertThat(service.take("conv-1", 7L)).isFalse();

        verify(jdbc, never()).update("UPDATE agent SET last_assigned_time = now() WHERE id = ?", 7L);
    }

    @Test
    void closeReportsWhetherOwnedActiveConversationWasActuallyClosed() {
        when(jdbc.update(
                contains("UPDATE conversation c SET status = 'closed'"),
                eq("conv-1"), eq(7L), eq(7L), eq(7L), eq(7L))).thenReturn(1);

        org.assertj.core.api.Assertions.assertThat(service.close("conv-1", 7L)).isTrue();
    }

    @Test
    void queueIsFilteredByCurrentAgentTenantAndKfScope() {
        service.queue(7L);

        verify(jdbc).queryForList(
                org.mockito.ArgumentMatchers.argThat(sql -> sql.contains("agent_tenant_scope")
                        && sql.contains("agent_kf_scope") && sql.contains("c.status = 'need_human'")),
                eq(7L), eq(7L), eq(7L));
    }

    @Test
    void arbitraryCustomerProfileIsRejectedWithoutOwnedServingConversation() {
        org.assertj.core.api.Assertions.assertThat(
                service.customerStats(7L, "conv-other", "user-other")).isNull();

        verify(jdbc, never()).queryForObject(
                contains("SELECT count(*) FROM conversation WHERE user_id="),
                eq(Integer.class), org.mockito.ArgumentMatchers.any(Object[].class));
    }

    @Test
    void logoutOneTokenDoesNotMarkAgentOfflineWhileAnotherWebSocketIsOnline() {
        when(stateStore.getAuth("token-1")).thenReturn(7L);
        when(sessions.isAgentOnline(7L)).thenReturn(true);

        service.logout("token-1");

        verify(stateStore).removeAuth("token-1");
        verify(sessions).closeAgentSessionsForToken(7L, "token-1");
        verify(jdbc, never()).update("UPDATE agent SET status = 'offline' WHERE id = ?", 7L);
    }
}
