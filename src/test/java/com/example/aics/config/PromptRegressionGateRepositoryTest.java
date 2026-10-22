package com.example.aics.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class PromptRegressionGateRepositoryTest {
    private JdbcTemplate jdbc;
    private PromptRegressionGateRepository repository;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        repository = new PromptRegressionGateRepository(jdbc);
    }

    @ParameterizedTest
    @ValueSource(strings = {"failed", "queued", "running"})
    void 最新运行未通过时不能回落旧通过记录(String status) {
        when(jdbc.queryForList(anyString(), eq("editor"), eq(1L), eq("draft"), eq("cases")))
                .thenReturn(List.of(row(status, false, "env-v1"), row("passed", true, "env-v1")));
        assertThat(repository.hasPassingProof("editor", 1L, "draft", "cases", "env-v1")).isFalse();
        ArgumentCaptor<String> query = ArgumentCaptor.forClass(String.class);
        verify(jdbc).queryForList(query.capture(), eq("editor"), eq(1L), eq("draft"), eq("cases"));
        assertThat(query.getValue()).endsWith("ORDER BY id DESC LIMIT 1")
                .contains("NOT EXISTS", "newer.id>r.id");
        String filter = query.getValue().substring(query.getValue().lastIndexOf(" WHERE requested_by=?"));
        assertThat(filter).doesNotContain("status", "environment_hash");
    }

    @Test
    void 环境变化或旧迁移记录不能用于发布() {
        when(jdbc.queryForList(anyString(), eq("editor"), eq(1L), eq("draft"), eq("cases")))
                .thenReturn(List.of(row("passed", true, "env-v1")));
        assertThat(repository.hasPassingProof("editor", 1L, "draft", "cases", "env-v1")).isTrue();
        assertThat(repository.hasPassingProof("editor", 1L, "draft", "cases", "env-v2")).isFalse();
        when(jdbc.queryForList(anyString(), eq("editor"), eq(1L), eq("draft"), eq("cases")))
                .thenReturn(List.of(row("passed", true, "")));
        assertThat(repository.hasPassingProof("editor", 1L, "draft", "cases", "env-v1")).isFalse();
    }

    @Test
    void 超时或不完整的通过记录不可发布() {
        when(jdbc.queryForList(anyString(), eq("editor"), eq(1L), eq("draft"), eq("cases")))
                .thenReturn(List.of(row("passed", false, "env-v1")));
        assertThat(repository.hasPassingProof("editor", 1L, "draft", "cases", "env-v1")).isFalse();
    }

    private Map<String, Object> row(String status, boolean proof, String environment) {
        return Map.of("id", 1L, "status", status, "proofValid", proof,
                "environmentHash", environment, "failuresJson", "[]");
    }
}
