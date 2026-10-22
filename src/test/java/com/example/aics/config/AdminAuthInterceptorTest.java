package com.example.aics.config;

import com.example.aics.agent.AgentAccountService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AdminAuthInterceptorTest {

    private AgentAccountService accounts;
    private AdminAuthInterceptor interceptor;

    @BeforeEach
    void setUp() {
        accounts = mock(AgentAccountService.class);
        interceptor = new AdminAuthInterceptor(accounts, new WebAuthCookieService(mock(ConfigService.class)));
    }

    @Test
    void unauthenticatedRequestReturns401() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(request("/report/overview", "missing"), response, new Object());

        assertThat(allowed).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void ordinaryAdminCanOnlyUseAssignedModule() throws Exception {
        when(accounts.adminSession("admin-token")).thenReturn(Map.of(
                "username", "editor", "role", AgentAccountService.ROLE_ADMIN,
                "permissions", List.of("report.view")));

        MockHttpServletResponse reportResponse = new MockHttpServletResponse();
        MockHttpServletResponse knowledgeResponse = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(request("/report/overview", "admin-token"),
                reportResponse, new Object())).isTrue();
        assertThat(interceptor.preHandle(request("/knowledge/list", "admin-token"),
                knowledgeResponse, new Object())).isFalse();
        assertThat(knowledgeResponse.getStatus()).isEqualTo(403);
    }

    @Test
    void 项目中心根接口和子接口都要求知识权限() throws Exception {
        when(accounts.adminSession("knowledge-token")).thenReturn(Map.of(
                "username", "editor", "role", AgentAccountService.ROLE_ADMIN,
                "permissions", List.of("knowledge.manage")));
        when(accounts.adminSession("store-token")).thenReturn(Map.of(
                "username", "store-editor", "role", AgentAccountService.ROLE_ADMIN,
                "permissions", List.of("stores.manage")));

        assertThat(interceptor.preHandle(request("/projects", "knowledge-token"),
                new MockHttpServletResponse(), new Object())).isTrue();
        assertThat(interceptor.preHandle(request("/projects/12", "knowledge-token"),
                new MockHttpServletResponse(), new Object())).isTrue();

        MockHttpServletResponse denied = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(request("/projects", "store-token"),
                denied, new Object())).isFalse();
        assertThat(denied.getStatus()).isEqualTo(403);
    }

    @Test
    void 已登录图片或门店管理员可以读取项目下拉但不能编辑项目() throws Exception {
        when(accounts.adminSession("image-token")).thenReturn(Map.of(
                "username", "image-editor", "role", AgentAccountService.ROLE_ADMIN,
                "permissions", List.of("images.manage")));

        assertThat(interceptor.preHandle(request("/projects/options", "image-token"),
                new MockHttpServletResponse(), new Object())).isTrue();
        MockHttpServletResponse denied = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(request("/projects/12", "image-token"),
                denied, new Object())).isFalse();
        assertThat(denied.getStatus()).isEqualTo(403);
    }

    @Test
    void qualityEvaluationRequiresDedicatedPermission() throws Exception {
        when(accounts.adminSession("quality-token")).thenReturn(Map.of(
                "username", "reviewer", "role", AgentAccountService.ROLE_ADMIN,
                "permissions", List.of("quality.manage")));
        when(accounts.adminSession("report-token")).thenReturn(Map.of(
                "username", "viewer", "role", AgentAccountService.ROLE_ADMIN,
                "permissions", List.of("report.view")));

        assertThat(interceptor.preHandle(request("/api/quality/overview", "quality-token"),
                new MockHttpServletResponse(), new Object())).isTrue();
        MockHttpServletResponse denied = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(request("/api/quality/overview", "report-token"),
                denied, new Object())).isFalse();
        assertThat(denied.getStatus()).isEqualTo(403);
    }

    @Test
    void aiDebugPermissionCanManageSharedRegressionCasesButNotScores() throws Exception {
        when(accounts.adminSession("debug-token")).thenReturn(Map.of(
                "username", "tester", "role", AgentAccountService.ROLE_ADMIN,
                "permissions", List.of("ai.debug")));

        assertThat(interceptor.preHandle(request("/api/quality/regression-cases", "debug-token"),
                new MockHttpServletResponse(), new Object())).isTrue();
        MockHttpServletResponse denied = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(request("/api/quality/evaluations", "debug-token"),
                denied, new Object())).isFalse();
        assertThat(denied.getStatus()).isEqualTo(403);
    }

    @Test
    void knowledgeAndAiDebugPermissionsCanRunKnowledgeAnswerTest() throws Exception {
        when(accounts.adminSession("knowledge-token")).thenReturn(Map.of(
                "username", "editor", "role", AgentAccountService.ROLE_ADMIN,
                "permissions", List.of("knowledge.manage")));
        when(accounts.adminSession("debug-token")).thenReturn(Map.of(
                "username", "tester", "role", AgentAccountService.ROLE_ADMIN,
                "permissions", List.of("ai.debug")));
        when(accounts.adminSession("report-token")).thenReturn(Map.of(
                "username", "viewer", "role", AgentAccountService.ROLE_ADMIN,
                "permissions", List.of("report.view")));

        assertThat(interceptor.preHandle(request("/api/chat/debug/run", "knowledge-token"),
                new MockHttpServletResponse(), new Object())).isTrue();
        assertThat(interceptor.preHandle(request("/api/chat/debug/run", "debug-token"),
                new MockHttpServletResponse(), new Object())).isTrue();
        MockHttpServletResponse denied = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(request("/api/chat/debug/run", "report-token"),
                denied, new Object())).isFalse();
        assertThat(denied.getStatus()).isEqualTo(403);
    }

    @Test
    void qualityPermissionCanAlsoAddSharedRegressionCases() throws Exception {
        when(accounts.adminSession("quality-token")).thenReturn(Map.of(
                "username", "reviewer", "role", AgentAccountService.ROLE_ADMIN,
                "permissions", List.of("quality.manage")));

        assertThat(interceptor.preHandle(request("/api/quality/regression-cases", "quality-token"),
                new MockHttpServletResponse(), new Object())).isTrue();
    }

    @Test
    void interfaceSettingsRemainSuperAdminOnly() throws Exception {
        when(accounts.adminSession("admin-token")).thenReturn(Map.of(
                "username", "editor", "role", AgentAccountService.ROLE_ADMIN,
                "permissions", AdminPermissionCatalog.codes()));

        MockHttpServletResponse listResponse = new MockHttpServletResponse();
        MockHttpServletResponse secretResponse = new MockHttpServletResponse();
        MockHttpServletResponse bigModelResponse = new MockHttpServletResponse();
        MockHttpServletResponse visionKeyResponse = new MockHttpServletResponse();
        MockHttpServletResponse embeddingKeyResponse = new MockHttpServletResponse();
        MockHttpServletResponse embeddingSwitchResponse = new MockHttpServletResponse();
        MockHttpServletResponse embeddingSwitchStatusResponse = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(request("/config/list", "admin-token"),
                listResponse, new Object())).isFalse();
        assertThat(interceptor.preHandle(request("/config/ai.deepseek_key", "admin-token"),
                secretResponse, new Object())).isFalse();
        assertThat(interceptor.preHandle(request("/config/ai.bigmodel_key", "admin-token"),
                bigModelResponse, new Object())).isFalse();
        assertThat(interceptor.preHandle(request("/config/model.vision.apiKey", "admin-token"),
                visionKeyResponse, new Object())).isFalse();
        assertThat(interceptor.preHandle(request("/config/model.embedding.apiKey", "admin-token"),
                embeddingKeyResponse, new Object())).isFalse();
        assertThat(interceptor.preHandle(request("/config/embedding-model/switch", "admin-token"),
                embeddingSwitchResponse, new Object())).isFalse();
        assertThat(interceptor.preHandle(request("/config/embedding-model/switch/status", "admin-token"),
                embeddingSwitchStatusResponse, new Object())).isFalse();
        assertThat(listResponse.getStatus()).isEqualTo(403);
        assertThat(secretResponse.getStatus()).isEqualTo(403);
        assertThat(bigModelResponse.getStatus()).isEqualTo(403);
        assertThat(visionKeyResponse.getStatus()).isEqualTo(403);
        assertThat(embeddingKeyResponse.getStatus()).isEqualTo(403);
        assertThat(embeddingSwitchResponse.getStatus()).isEqualTo(403);
        assertThat(embeddingSwitchStatusResponse.getStatus()).isEqualTo(403);
    }

    @Test
    void weComDiagnosticsRemainSuperAdminOnly() throws Exception {
        when(accounts.adminSession("admin-token")).thenReturn(Map.of(
                "username", "editor", "role", AgentAccountService.ROLE_ADMIN,
                "permissions", AdminPermissionCatalog.codes()));
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(request("/api/wecom/diagnostics", "admin-token"),
                response, new Object())).isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void aiModelManagementRemainsSuperAdminOnly() throws Exception {
        when(accounts.adminSession("admin-token")).thenReturn(Map.of(
                "username", "editor", "role", AgentAccountService.ROLE_ADMIN,
                "permissions", AdminPermissionCatalog.codes()));
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(request("/api/ai-models", "admin-token"),
                response, new Object())).isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void superAdminCanUseSettingsAndAccountManagement() throws Exception {
        when(accounts.adminSession("super-token")).thenReturn(Map.of(
                "username", "admin", "role", AgentAccountService.ROLE_SUPER_ADMIN,
                "permissions", AdminPermissionCatalog.codes()));

        assertThat(interceptor.preHandle(request("/config/list", "super-token"),
                new MockHttpServletResponse(), new Object())).isTrue();
        assertThat(interceptor.preHandle(request("/admin/accounts", "super-token"),
                new MockHttpServletResponse(), new Object())).isTrue();
        assertThat(interceptor.preHandle(request("/config/model.vision.apiKey", "super-token"),
                new MockHttpServletResponse(), new Object())).isTrue();
        assertThat(interceptor.preHandle(request("/config/model.embedding.apiKey", "super-token"),
                new MockHttpServletResponse(), new Object())).isTrue();
        assertThat(interceptor.preHandle(request("/config/embedding-model/switch", "super-token"),
                new MockHttpServletResponse(), new Object())).isTrue();
        assertThat(interceptor.preHandle(request("/config/embedding-model/switch/status", "super-token"),
                new MockHttpServletResponse(), new Object())).isTrue();
    }

    @Test
    void ordinaryAdminCanReadOwnSessionWithoutModulePermission() throws Exception {
        when(accounts.adminSession("admin-token")).thenReturn(Map.of(
                "username", "viewer", "role", AgentAccountService.ROLE_ADMIN,
                "permissions", List.of()));

        assertThat(interceptor.preHandle(request("/admin/session", "admin-token"),
                new MockHttpServletResponse(), new Object())).isTrue();
    }

    @Test
    void 删除会话需要独立删除权限而查看权限不足() throws Exception {
        when(accounts.adminSession("viewer-token")).thenReturn(Map.of(
                "username", "viewer", "role", AgentAccountService.ROLE_ADMIN,
                "permissions", List.of("conversations.view")));
        when(accounts.adminSession("deleter-token")).thenReturn(Map.of(
                "username", "deleter", "role", AgentAccountService.ROLE_ADMIN,
                "permissions", List.of("conversations.view", "conversations.delete")));

        // 只读查看：查询与读消息放行
        assertThat(interceptor.preHandle(request("/api/conversations/query", "viewer-token"),
                new MockHttpServletResponse(), new Object())).isTrue();
        assertThat(interceptor.preHandle(request("/api/conversations/abc/messages", "viewer-token"),
                new MockHttpServletResponse(), new Object())).isTrue();

        // 单条物理删除：仅有查看权限被拒
        MockHttpServletResponse deniedSingle = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(request("/api/conversations/abc", "viewer-token", "DELETE"),
                deniedSingle, new Object())).isFalse();
        assertThat(deniedSingle.getStatus()).isEqualTo(403);

        // 清空全部：同样按删除权限拦截
        MockHttpServletResponse deniedClear = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(request("/api/conversations/clear", "viewer-token", "DELETE"),
                deniedClear, new Object())).isFalse();
        assertThat(deniedClear.getStatus()).isEqualTo(403);

        // 显式授予删除权限后放行
        assertThat(interceptor.preHandle(request("/api/conversations/abc", "deleter-token", "DELETE"),
                new MockHttpServletResponse(), new Object())).isTrue();
    }

    @Test
    void 超级管理员始终保留会话删除能力() throws Exception {
        when(accounts.adminSession("super-token")).thenReturn(Map.of(
                "username", "admin", "role", AgentAccountService.ROLE_SUPER_ADMIN,
                "permissions", List.of()));

        assertThat(interceptor.preHandle(request("/api/conversations/abc", "super-token", "DELETE"),
                new MockHttpServletResponse(), new Object())).isTrue();
        assertThat(interceptor.preHandle(request("/api/conversations/clear", "super-token", "DELETE"),
                new MockHttpServletResponse(), new Object())).isTrue();
    }

    private MockHttpServletRequest request(String uri, String token) {
        return request(uri, token, "GET");
    }

    private MockHttpServletRequest request(String uri, String token, String method) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.addHeader("X-Admin-Token", token);
        return request;
    }
}
