package com.example.aics.config;

import com.example.aics.ai.AiModelProviderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class HarnessEnvironmentServiceTest {
    private JdbcTemplate jdbc;
    private ConfigService config;
    private AiModelProviderService providers;
    private HarnessEnvironmentService service;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        config = mock(ConfigService.class);
        providers = mock(AiModelProviderService.class);
        KeyConfigService keys = mock(KeyConfigService.class);
        when(config.get(anyString(), anyString())).thenAnswer(call -> call.getArgument(1));
        when(jdbc.queryForList(HarnessEnvironmentService.REVISIONS_SQL))
                .thenReturn(List.of(Map.of("name", "knowledge_chunk", "revision", "data-v1")));
        when(providers.active(anyString())).thenReturn(model("model-v1", "private-test-key"));
        service = new HarnessEnvironmentService(jdbc, providers, config, keys);
    }

    @Test
    void 同一环境指纹稳定且不暴露配置正文() {
        String hash = service.fingerprint();
        assertThat(hash).hasSize(64).matches("[a-f0-9]+")
                .isEqualTo(service.fingerprint()).doesNotContain("private-test-key");
    }

    @Test
    void 知识数据版本变化使指纹失效() {
        String before = service.fingerprint();
        when(jdbc.queryForList(HarnessEnvironmentService.REVISIONS_SQL))
                .thenReturn(List.of(Map.of("name", "knowledge_chunk", "revision", "data-v2")));
        assertThat(service.fingerprint()).isNotEqualTo(before);
    }

    @Test
    void 模型或密钥变化使指纹失效() {
        String before = service.fingerprint();
        when(providers.active("primary")).thenReturn(model("model-v2", "private-test-key"));
        String modelChanged = service.fingerprint();
        assertThat(modelChanged).isNotEqualTo(before);
        when(providers.active("primary")).thenReturn(model("model-v2", "rotated-test-key"));
        assertThat(service.fingerprint()).isNotEqualTo(modelChanged);
    }

    @Test
    void 运行参数变化使指纹失效() {
        String before = service.fingerprint();
        when(config.get(eq("model.temperature"), anyString())).thenReturn("0.8");
        assertThat(service.fingerprint()).isNotEqualTo(before);
    }

    @Test
    void 数据范围顺序不影响指纹但范围变化会失效() {
        String first;
        try (var ignored = AdminDataScope.use(new AdminDataScope.AccessScope(true, List.of(2L, 1L), List.of()))) {
            first = service.fingerprint();
        }
        try (var ignored = AdminDataScope.use(new AdminDataScope.AccessScope(true, List.of(1L, 2L), List.of()))) {
            assertThat(service.fingerprint()).isEqualTo(first);
        }
        try (var ignored = AdminDataScope.use(new AdminDataScope.AccessScope(true, List.of(1L), List.of()))) {
            assertThat(service.fingerprint()).isNotEqualTo(first);
        }
    }

    @Test
    void 读取版本失败不能生成可复用指纹() {
        when(jdbc.queryForList(HarnessEnvironmentService.REVISIONS_SQL)).thenThrow(new IllegalStateException("db unavailable"));
        assertThatThrownBy(service::fingerprint).isInstanceOf(IllegalStateException.class);
    }

    private AiModelProviderService.RuntimeModel model(String name, String key) {
        return new AiModelProviderService.RuntimeModel(1, "test", "test", "http://127.0.0.1/v1", name, key, "primary", true);
    }
}
