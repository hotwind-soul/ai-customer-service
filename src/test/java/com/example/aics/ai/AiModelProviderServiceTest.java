package com.example.aics.ai;

import com.example.aics.config.ConfigService;
import com.example.aics.config.KeyConfigService;
import com.example.aics.config.SecretCryptoService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiModelProviderServiceTest {

    @Mock private JdbcTemplate jdbc;
    @Mock private SecretCryptoService crypto;
    @Mock private KeyConfigService keys;
    @Mock private ConfigService config;
    @Mock private ResultSet rs;

    @Test
    void acceptsOpenAiCompatibleBaseUrlsAndRemovesTrailingSlash() {
        assertThat(AiModelProviderService.normalizeBaseUrl("https://api.deepseek.com/v1/"))
                .isEqualTo("https://api.deepseek.com/v1");
        assertThat(AiModelProviderService.normalizeBaseUrl("http://127.0.0.1:8000/v1"))
                .isEqualTo("http://127.0.0.1:8000/v1");
    }

    @Test
    void rejectsUnsafeOrFullCompletionUrls() {
        assertThatThrownBy(() -> AiModelProviderService.normalizeBaseUrl(
                "http://api.example.com/v1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HTTPS");
        assertThatThrownBy(() -> AiModelProviderService.normalizeBaseUrl(
                "https://api.example.com/v1/chat/completions"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Base URL");
        assertThatThrownBy(() -> AiModelProviderService.normalizeBaseUrl(
                "file:///tmp/model"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** V15 事故回归：candidate（未启用、未配 Key）必须允许直接降级为评测专用角色。 */
    @Test
    void candidateCanBeAssignedEvalRoleWithoutEnabledOrKey() throws Exception {
        stubRowLookup(AiModelProviderService.ROLE_CANDIDATE, false);
        AiModelProviderService service = new AiModelProviderService(jdbc, crypto, keys, config);

        assertThatCode(() -> service.assignRole(5L, AiModelProviderService.ROLE_EVAL))
                .doesNotThrowAnyException();

        verify(jdbc).update("UPDATE ai_model_provider SET role=?,update_time=now() WHERE id=?",
                "eval", 5L);
    }

    /** 未启用的模型不允许直接设为主模型（主备链路必须有可用 Key 与启用状态）。 */
    @Test
    void candidateCannotBeAssignedPrimaryWithoutEnabled() throws Exception {
        stubRowLookup(AiModelProviderService.ROLE_CANDIDATE, false);
        AiModelProviderService service = new AiModelProviderService(jdbc, crypto, keys, config);

        assertThatThrownBy(() -> service.assignRole(5L, AiModelProviderService.ROLE_PRIMARY))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("请先启用该模型");

        verify(jdbc, never()).update(anyString(), any(), any());
    }

    /** requireRow 走 SELECT ... WHERE id=?；用 mock ResultSet 驱动真实 ROW_MAPPER 的列名读取。 */
    private void stubRowLookup(String role, boolean enabled) throws Exception {
        when(jdbc.query(anyString(), ArgumentMatchers.<RowMapper<Object>>any(), any(Object[].class)))
                .thenAnswer(inv -> {
                    RowMapper<?> mapper = inv.getArgument(1);
                    return List.of(mapper.mapRow(rs, 0));
                });
        // ROW_MAPPER 会读取全部 12 列，先给宽松默认值，再覆盖关心的列。
        when(rs.getLong(anyString())).thenReturn(0L);
        when(rs.getString(anyString())).thenReturn(null);
        when(rs.getBoolean(anyString())).thenReturn(false);
        when(rs.getTimestamp(anyString())).thenReturn(null);
        when(rs.getLong("id")).thenReturn(5L);
        when(rs.getString("role")).thenReturn(role);
        when(rs.getBoolean("enabled")).thenReturn(enabled);
    }
}
