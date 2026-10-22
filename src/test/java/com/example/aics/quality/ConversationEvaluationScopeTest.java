package com.example.aics.quality;

import com.example.aics.audit.AuditService;
import com.example.aics.config.ConfigService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConversationEvaluationScopeTest {

    @AfterEach
    void clearRequest() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void ordinaryAdminCannotScoreConversationOutsideAssignedTenant() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute("aics.admin.role", "admin");
        request.setAttribute("aics.admin.tenantIds", List.of(4L));
        request.setAttribute("aics.admin.kfAccountIds", List.of());
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(contains("FROM conversation c"), eq(Integer.class), any(Object[].class)))
                .thenReturn(0);
        ConversationEvaluationService service = new ConversationEvaluationService(
                jdbc, mock(ConfigService.class), mock(QualityJudgeService.class), mock(AuditService.class));

        assertThatThrownBy(() -> service.score("outside-conversation", "rules", "limited-admin"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("无权访问");
        verify(jdbc, never()).query(contains("FROM message"),
                org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<Object>>any(),
                eq("outside-conversation"));
    }
}
