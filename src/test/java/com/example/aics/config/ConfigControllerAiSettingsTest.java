package com.example.aics.config;

import com.example.aics.agent.AgentAccountService;
import com.example.aics.ai.EmbeddingModelSwitchService;
import com.example.aics.audit.AuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConfigControllerAiSettingsTest {

    private ConfigService config;
    private AgentAccountService accounts;
    private EmbeddingModelSwitchService embeddingSwitch;
    private PromptReleaseService promptReleases;
    private ConfigController controller;

    @BeforeEach
    void setUp() {
        config = mock(ConfigService.class);
        accounts = mock(AgentAccountService.class);
        embeddingSwitch = mock(EmbeddingModelSwitchService.class);
        promptReleases = mock(PromptReleaseService.class);
        when(config.get(anyString(), anyString())).thenAnswer(inv -> inv.getArgument(1));
        controller = new ConfigController(config, mock(KeyConfigService.class),
                mock(AuditService.class), accounts, new WebAuthCookieService(mock(ConfigService.class)),
                embeddingSwitch, promptReleases, mock(PromptRegressionGateService.class));
    }

    @Test
    void 普通管理员目录不返回高级模型参数() {
        MockHttpServletRequest request = request("admin-token");
        when(accounts.adminSession("admin-token")).thenReturn(Map.of(
                "username", "editor", "role", AgentAccountService.ROLE_ADMIN));

        Map<String, Object> result = controller.aiSettings(request);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) result.get("items");
        assertThat(items).extracting(item -> String.valueOf(item.get("key")))
                .contains("welcome.message", "intent.projects")
                .doesNotContain("model.embedding.baseUrl", "model.embedding.name");
        Map<String, Object> faqThreshold = items.stream()
                .filter(item -> "threshold.faqDirect".equals(item.get("key")))
                .findFirst().orElseThrow();
        assertThat(faqThreshold)
                .containsEntry("min", 0.0)
                .containsEntry("max", 2.0)
                .containsEntry("step", "0.01");
        assertThat(String.valueOf(faqThreshold.get("help")))
                .contains("可填写范围为 0 至 2", "向量距离", "误匹配风险");
        Map<String, Object> judgeTimeout = items.stream()
                .filter(item -> "quality.judgeTimeoutSeconds".equals(item.get("key")))
                .findFirst().orElseThrow();
        assertThat(judgeTimeout)
                .containsEntry("min", 5.0)
                .containsEntry("max", 120.0);
        assertThat(String.valueOf(judgeTimeout.get("help")))
                .contains("可填写范围为 5 至 120", "立即失败", "自动重试");
    }

    @Test
    void 普通管理员不能绕过页面读取或批量修改高级模型参数() {
        MockHttpServletRequest request = request("admin-token");
        when(accounts.adminSession("admin-token")).thenReturn(Map.of(
                "username", "editor", "role", AgentAccountService.ROLE_ADMIN));

        assertThatThrownBy(() -> controller.get("model.embedding.name", request))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode().value()).isEqualTo(403));
        assertThatThrownBy(() -> controller.saveAiSettings(
                Map.of("values", Map.of("model.embedding.name", "other-model")), request))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode().value()).isEqualTo(403));
    }

    @Test
    void 超级管理员不能通过通用配置接口绕过向量重建() {
        MockHttpServletRequest request = request("super-token");
        when(accounts.adminSession("super-token")).thenReturn(Map.of(
                "username", "root", "role", AgentAccountService.ROLE_SUPER_ADMIN));

        assertThatThrownBy(() -> controller.saveAiSettings(
                Map.of("values", Map.of("model.embedding.name", "other-model")), request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("安全切换接口");
        assertThatThrownBy(() -> controller.set(
                "model.embedding.baseUrl", Map.of("value", "https://example.com/v1"), request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("安全切换接口");
    }

    @Test
    void 项目和别名只能通过项目中心维护() {
        MockHttpServletRequest request = request("super-token");
        when(accounts.adminSession("super-token")).thenReturn(Map.of(
                "username", "root", "role", AgentAccountService.ROLE_SUPER_ADMIN));

        assertThatThrownBy(() -> controller.saveAiSettings(
                Map.of("values", Map.of("intent.projects", "{}")), request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("项目中心");
        assertThatThrownBy(() -> controller.set(
                "home.projectList", Map.of("value", "新项目"), request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("项目中心");
    }

    @Test
    void 超级管理员通过安全接口提交自动重建任务() {
        MockHttpServletRequest request = request("super-token");
        when(accounts.adminSession("super-token")).thenReturn(Map.of(
                "username", "root", "role", AgentAccountService.ROLE_SUPER_ADMIN));
        when(embeddingSwitch.submit("https://example.com/v1", "embedding-1024", "new-key", "root"))
                .thenReturn(42L);

        Map<String, Object> result = controller.switchEmbeddingModel(
                new ConfigController.EmbeddingSwitchRequest(
                        "https://example.com/v1", "embedding-1024", "new-key"), request);

        assertThat(result.get("taskId")).isEqualTo(42L);
        assertThat(result.get("status")).isEqualTo("queued");
        verify(embeddingSwitch).submit(
                "https://example.com/v1", "embedding-1024", "new-key", "root");
    }

    @Test
    void Prompt不能再绕过版本流程直接发布() {
        MockHttpServletRequest request = request("admin-token");
        when(accounts.adminSession("admin-token")).thenReturn(Map.of(
                "username", "editor", "role", AgentAccountService.ROLE_ADMIN));

        assertThatThrownBy(() -> controller.saveAiSettings(
                Map.of("values", Map.of("prompt.tone", "新语气")), request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("先保存草稿");
    }

    @Test
    void Prompt草稿保存到当前管理员工作区() {
        MockHttpServletRequest request = request("admin-token");
        when(accounts.adminSession("admin-token")).thenReturn(Map.of(
                "username", "editor", "role", AgentAccountService.ROLE_ADMIN));
        when(promptReleases.saveDraft(eq("editor"), eq(false), anyMap()))
                .thenReturn(Map.of("draft", Map.of("changedKeys", List.of("prompt.tone"))));

        Map<String, Object> result = controller.savePromptDraft(
                Map.of("values", Map.of("prompt.tone", "新语气")), request);

        assertThat(result).containsKey("draft");
        verify(promptReleases).saveDraft(
                "editor", false, Map.of("prompt.tone", "新语气"));
    }

    private MockHttpServletRequest request(String token) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Admin-Token", token);
        return request;
    }
}
