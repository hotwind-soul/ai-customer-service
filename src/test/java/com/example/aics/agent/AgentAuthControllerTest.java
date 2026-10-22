package com.example.aics.agent;

import com.example.aics.audit.AuditService;
import com.example.aics.chat.ConversationService;
import com.example.aics.chat.SessionRegistry;
import com.example.aics.chat.UserProfileService;
import com.example.aics.config.LoginRateLimiter;
import com.example.aics.config.ConfigService;
import com.example.aics.config.WebAuthCookieService;
import com.example.aics.image.ImageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentAuthControllerTest {

    private AgentAuthController controller;
    private AgentAccountService accounts;
    private LoginRateLimiter rateLimiter;
    private ConfigService controllerConfig;

    @BeforeEach
    void setUp() {
        accounts = mock(AgentAccountService.class);
        rateLimiter = mock(LoginRateLimiter.class);
        ConfigService config = mock(ConfigService.class);
        when(config.getInt("auth.token_ttl_hours", 24)).thenReturn(24);
        controllerConfig = mock(ConfigService.class);
        controller = new AgentAuthController(
                accounts,
                mock(ConversationService.class),
                mock(UserProfileService.class),
                mock(AuditService.class),
                rateLimiter,
                new WebAuthCookieService(config),
                mock(HumanReplyService.class),
                controllerConfig,
                mock(ImageService.class),
                mock(SessionRegistry.class));
    }

    @Test
    void unauthenticatedQueueRequestReturnsReal401() {
        Object result = controller.queue(new MockHttpServletRequest());

        assertThat(result).isInstanceOf(ResponseEntity.class);
        ResponseEntity<?> response = (ResponseEntity<?>) result;
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).isEqualTo(
                Map.of("ok", false, "error", "坐席登录已失效，请重新登录"));
    }

    @Test
    void validAgentCookieCanRestoreSession() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Agent-Token", "agent-token");
        when(accounts.validate("agent-token")).thenReturn(Map.of(
                "id", 3L, "name", "客服小李", "username", "agent1", "role", "agent"));

        ResponseEntity<Map<String, Object>> response = controller.session(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("ok", true)
                .containsEntry("name", "客服小李")
                .containsEntry("role", "agent");
    }

    @Test
    void authenticatedQueueUsesCurrentAgentScope() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Agent-Token", "agent-token");
        when(accounts.validate("agent-token")).thenReturn(Map.of(
                "id", 3L, "name", "客服小李", "username", "agent1", "role", "agent"));

        controller.queue(request);

        verify(accounts).queue(3L);
    }

    @Test
    void profileRejectsCustomerOutsideCurrentServingConversation() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Agent-Token", "agent-token");
        when(accounts.validate("agent-token")).thenReturn(Map.of(
                "id", 3L, "name", "客服小李", "username", "agent1", "role", "agent"));
        when(accounts.customerStats(3L, "conv-other", "user-other")).thenReturn(null);

        Object result = controller.profile("user-other", "conv-other", request);

        assertThat(result).isEqualTo(Map.of("ok", false, "error", "该客户不属于您当前服务的会话"));
    }

    @Test
    void adminAccountIsRejectedByAgentPortalAndStaleAgentCookieIsCleared() {
        when(accounts.login("owner", "password123")).thenReturn(Map.of(
                "ok", true, "token", "admin-token", "role", "super_admin",
                "name", "负责人", "username", "owner"));
        MockHttpServletResponse servletResponse = new MockHttpServletResponse();

        ResponseEntity<Map<String, Object>> response = controller.login(
                Map.of("username", "owner", "password", "password123", "portal", "agent"),
                new MockHttpServletRequest(), servletResponse);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).containsEntry("ok", false)
                .containsEntry("error", "该账号不是坐席账号，请使用管理员分配的坐席账号登录");
        assertThat(servletResponse.getHeader("Set-Cookie"))
                .contains("AICS_AGENT=")
                .contains("Max-Age=0")
                .contains("HttpOnly");
        verify(accounts).logout("admin-token");
    }

    @Test
    void validAgentPortalSetsHttpOnlyAgentCookieAndDoesNotExposeToken() {
        // 坐席工作台默认封存；本用例验证工作台开启时的正常登录链路。
        when(controllerConfig.getBoolean("agent.workbenchEnabled", false)).thenReturn(true);
        when(accounts.login("agent1", "password123")).thenReturn(Map.of(
                "ok", true, "token", "agent-token", "role", "agent",
                "name", "客服小李", "username", "agent1"));
        MockHttpServletResponse servletResponse = new MockHttpServletResponse();

        ResponseEntity<Map<String, Object>> response = controller.login(
                Map.of("username", "agent1", "password", "password123", "portal", "agent"),
                new MockHttpServletRequest(), servletResponse);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("ok", true)
                .containsEntry("role", "agent")
                .doesNotContainKey("token");
        assertThat(servletResponse.getHeader("Set-Cookie"))
                .contains("AICS_AGENT=agent-token")
                .contains("HttpOnly")
                .contains("SameSite=Strict");
    }

    @Test
    void agentLoginBlockedWhileWorkbenchSealed() {
        // 默认封存（agent.workbenchEnabled=false）：坐席登录被拒绝、令牌立即撤销、不写 Cookie
        when(accounts.login("agent1", "password123")).thenReturn(Map.of(
                "ok", true, "token", "agent-token", "role", "agent",
                "name", "客服小李", "username", "agent1"));
        MockHttpServletResponse servletResponse = new MockHttpServletResponse();

        ResponseEntity<Map<String, Object>> response = controller.login(
                Map.of("username", "agent1", "password", "password123", "portal", "agent"),
                new MockHttpServletRequest(), servletResponse);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).containsEntry("ok", false).containsEntry("error",
                "坐席工作台已暂停使用：人工接待已迁移至企业微信原生接待，网页渠道仅保留智能客服答疑。");
        assertThat(servletResponse.getHeader("Set-Cookie"))
                .contains("AICS_AGENT=")
                .contains("Max-Age=0");
        verify(accounts).logout("agent-token");
    }
}
