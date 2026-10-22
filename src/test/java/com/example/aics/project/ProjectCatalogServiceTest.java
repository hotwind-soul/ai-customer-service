package com.example.aics.project;

import com.example.aics.channel.TenantExecutionContext;
import com.example.aics.config.ConfigService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProjectCatalogServiceTest {

    @Mock private JdbcTemplate jdbc;
    @Mock private ConfigService config;

    @Test
    void 网页无租户上下文时只读取全局项目() {
        when(jdbc.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(Map.of("id", 1L, "name", "淡斑", "sort_order", 1)));
        ProjectCatalogService service = new ProjectCatalogService(jdbc, config);

        assertThat(service.publishedNames()).containsExactly("淡斑");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).queryForList(sql.capture(), any(Object[].class));
        assertThat(sql.getValue()).contains("p.tenant_id IS NULL", "p.kf_account_id IS NULL",
                "p.parent_id IS NULL");
    }

    @Test
    void 有租户上下文时读取租户和全局项目() {
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());
        ProjectCatalogService service = new ProjectCatalogService(jdbc, config);

        try (TenantExecutionContext.Lease ignored = TenantExecutionContext.use(7L, 9L)) {
            service.publishedNames();
        }

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).queryForList(sql.capture(), any(Object[].class));
        assertThat(sql.getValue()).contains("p.tenant_id=?", "p.kf_account_id=?")
                .doesNotContain("AND p.tenant_id IS NULL AND p.kf_account_id IS NULL");
    }

    @Test
    void 已发布报价只由项目和报价状态控制() {
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(Map.of(
                "activity_price", new BigDecimal("198"),
                "source", "project-center")));
        ProjectCatalogService service = new ProjectCatalogService(jdbc, config);

        ProjectCatalogService.Offer offer = service.publishedOffer("淡斑");

        assertThat(offer).isNotNull();
        assertThat(offer.activityPrice()).isEqualByComparingTo("198");
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).queryForList(sql.capture(), any(Object[].class));
        assertThat(sql.getValue())
                .contains("p.status='published'", "o.status='published'", "parent.status='published'")
                .doesNotContain("valid_from", "valid_until");
    }

    @Test
    void 已发布服务范围从项目中心读取() {
        when(jdbc.queryForList(anyString(), eq(String.class), any(Object[].class)))
                .thenReturn(List.of("额头、眼周和下颌线，具体以专业评估为准。"));
        ProjectCatalogService service = new ProjectCatalogService(jdbc, config);

        assertThat(service.publishedServiceScope("抗衰全脸"))
                .contains("额头、眼周和下颌线");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).queryForList(sql.capture(), eq(String.class), any(Object[].class));
        assertThat(sql.getValue()).contains("p.service_scope", "p.status='published'");
    }

    @Test
    void 项目编辑器门店选项包含城市和区县() {
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());
        ProjectCatalogService service = new ProjectCatalogService(jdbc, config);

        service.resourceOptions();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, times(2)).queryForList(sql.capture(), any(Object[].class));
        assertThat(sql.getAllValues()).anySatisfy(value -> assertThat(value)
                .contains("s.city", "s.district", "ORDER BY s.city,s.district"));
    }

    @Test
    void 报价校验拒绝订金高于活动价() {
        ProjectCatalogService service = new ProjectCatalogService(jdbc, config);
        ProjectCatalogService.OfferCommand offer = new ProjectCatalogService.OfferCommand(
                new BigDecimal("100"), null, new BigDecimal("101"), null, null,
                "published");
        ProjectCatalogService.ProjectCommand command = new ProjectCatalogService.ProjectCommand(
                null, "test", "测试项目", "published", 1, List.of(), offer, List.of(), List.of());

        assertThatThrownBy(() -> service.create(command))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("订金不能大于活动价");
    }

    @Test
    void 已发布报价拒绝稀缺营销和无条件退款承诺() {
        ProjectCatalogService service = new ProjectCatalogService(jdbc, config);
        ProjectCatalogService.OfferCommand offer = new ProjectCatalogService.OfferCommand(
                new BigDecimal("198"), new BigDecimal("1980"), new BigDecimal("8"),
                "活动价198元", "限时活动30个名额，到店不满意无条件退款", "published");
        ProjectCatalogService.ProjectCommand command = new ProjectCatalogService.ProjectCommand(
                null, "spot", "淡斑", "published", 1, List.of(), offer, List.of(), List.of());

        assertThatThrownBy(() -> service.create(command))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("已发布销售话术包含不允许的承诺");
    }

    @Test
    void 重复项目转换成可读业务错误() {
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class)))
                .thenThrow(new DataIntegrityViolationException("duplicate"));
        ProjectCatalogService service = new ProjectCatalogService(jdbc, config);
        ProjectCatalogService.ProjectCommand command = new ProjectCatalogService.ProjectCommand(
                null, "test", "测试项目", "published", 1, List.of(), null, List.of(), List.of());

        assertThatThrownBy(() -> service.create(command))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("同一作用域内的项目名称或项目编码已存在");
    }

    @Test
    void 子项目仍进入客户意图识别规则() {
        when(jdbc.queryForList(contains("FROM service_project p LEFT JOIN service_project parent"),
                any(Object[].class))).thenReturn(List.of(
                Map.of("id", 1L, "name", "抗衰", "sort_order", 10),
                Map.of("id", 2L, "parent_id", 1L, "name", "抗衰全脸", "sort_order", 20)));
        when(jdbc.queryForList(contains("FROM service_project_alias"), eq(String.class), eq(1L)))
                .thenReturn(List.of("抗衰"));
        when(jdbc.queryForList(contains("FROM service_project_alias"), eq(String.class), eq(2L)))
                .thenReturn(List.of("全脸抗衰"));
        ProjectCatalogService service = new ProjectCatalogService(jdbc, config);

        assertThat(service.projectRules())
                .containsEntry("抗衰", List.of("抗衰"))
                .containsEntry("抗衰全脸", List.of("抗衰全脸", "全脸抗衰"));
    }

    @Test
    void 不允许跨作用域关联父项目() {
        when(jdbc.queryForList(contains("SELECT id,parent_id,tenant_id,kf_account_id"), eq(10L)))
                .thenReturn(List.of(Map.of("id", 10L, "tenant_id", 2L)));
        ProjectCatalogService service = new ProjectCatalogService(jdbc, config);
        ProjectCatalogService.ProjectCommand command = new ProjectCatalogService.ProjectCommand(
                10L, "child", "抗衰全脸", "published", 1, List.of(), null, List.of(), List.of());

        try (TenantExecutionContext.Lease ignored = TenantExecutionContext.use(1L, null)) {
            assertThatThrownBy(() -> service.create(command))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("父项目必须与子项目属于同一企业和客服号");
        }
    }

    @Test
    void 不允许选择子项目作为父项目() {
        when(jdbc.queryForList(contains("SELECT id,parent_id,tenant_id,kf_account_id"), eq(10L)))
                .thenReturn(List.of(Map.of("id", 10L, "parent_id", 9L)));
        ProjectCatalogService service = new ProjectCatalogService(jdbc, config);
        ProjectCatalogService.ProjectCommand command = new ProjectCatalogService.ProjectCommand(
                10L, "grandchild", "全脸升级版", "published", 1, List.of(), null, List.of(), List.of());

        assertThatThrownBy(() -> service.create(command))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("仅支持一级子项目，不能选择子项目作为父项目");
    }
}
