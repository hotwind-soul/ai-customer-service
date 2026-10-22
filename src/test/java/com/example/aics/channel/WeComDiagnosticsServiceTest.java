package com.example.aics.channel;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WeComDiagnosticsServiceTest {

    @Test
    void configuredWhenChecksPassButNoValidCallbackHasArrived() throws Exception {
        WeComTenantService.TenantCredentials tenant = tenant();
        WeComCryptoService crypto = mock(WeComCryptoService.class);
        when(crypto.isConfigured(tenant)).thenReturn(true);
        WeComKfApiClient apiClient = mock(WeComKfApiClient.class);
        when(apiClient.listAccounts(tenant)).thenReturn(List.of(
                new WeComKfApiClient.KfAccountProfile("wk-1", "客服一", "")));
        WeComKfService kf = mock(WeComKfService.class);
        when(kf.executorStatus()).thenReturn(Map.of("queued", 0));
        WeComRuntimeMonitor monitor = new WeComRuntimeMonitor();
        WeComDiagnosticsService service = new WeComDiagnosticsService(
                mock(WeComKfTokenService.class), apiClient, crypto, monitor, kf);

        Map<String, Object> result = service.diagnostics(tenant,
                List.of(Map.of("enabled", true, "openKfid", "wk-1")),
                "https://kf.example.com");

        assertThat(result).containsEntry("overall", "configured")
                .containsEntry("statusLabel", "配置已通过");
        assertThat(result.get("callbackUrl"))
                .isEqualTo("https://kf.example.com/kf/callback/tenant-a");
    }

    @Test
    void connectedOnlyAfterThisTenantHasAcceptedAValidCallback() throws Exception {
        WeComTenantService.TenantCredentials tenant = tenant();
        WeComCryptoService crypto = mock(WeComCryptoService.class);
        when(crypto.isConfigured(tenant)).thenReturn(true);
        WeComKfApiClient apiClient = mock(WeComKfApiClient.class);
        when(apiClient.listAccounts(tenant)).thenReturn(List.of(
                new WeComKfApiClient.KfAccountProfile("wk-1", "客服一", "")));
        WeComKfService kf = mock(WeComKfService.class);
        when(kf.executorStatus()).thenReturn(Map.of("queued", 0));
        WeComRuntimeMonitor monitor = new WeComRuntimeMonitor();
        monitor.callbackReceived("tenant-b");
        monitor.callbackAccepted("tenant-b");
        WeComDiagnosticsService service = new WeComDiagnosticsService(
                mock(WeComKfTokenService.class), apiClient, crypto, monitor, kf);

        Map<String, Object> before = service.diagnostics(tenant,
                List.of(Map.of("enabled", true, "openKfid", "wk-1")),
                "https://kf.example.com");
        assertThat(before.get("overall")).isEqualTo("configured");

        monitor.callbackReceived("tenant-a");
        monitor.callbackAccepted("tenant-a");
        Map<String, Object> after = service.diagnostics(tenant,
                List.of(Map.of("enabled", true, "openKfid", "wk-1")),
                "https://kf.example.com");

        assertThat(after).containsEntry("overall", "connected")
                .containsEntry("statusLabel", "已连接");
        @SuppressWarnings("unchecked")
        Map<String, Object> runtime = (Map<String, Object>) after.get("runtime");
        assertThat(runtime).containsEntry("callbackScope", "tenant")
                .containsEntry("callbacksAccepted", 1L);
        assertThat(String.valueOf(runtime.get("lastAcceptedCallbackAt"))).isNotBlank();
    }

    @Test
    void permissionCheckNeverReturnsTokenAndRecordsResult() throws Exception {
        WeComKfTokenService token = mock(WeComKfTokenService.class);
        WeComKfApiClient apiClient = mock(WeComKfApiClient.class);
        WeComRuntimeMonitor monitor = mock(WeComRuntimeMonitor.class);
        WeComDiagnosticsService service = new WeComDiagnosticsService(token, apiClient,
                mock(WeComCryptoService.class), monitor, mock(WeComKfService.class));
        WeComTenantService.TenantCredentials tenant = tenant();
        when(apiClient.listAccounts(tenant)).thenReturn(List.of(
                new WeComKfApiClient.KfAccountProfile("wk-1", "客服一", "")));

        Map<String, Object> result = service.verifyToken(tenant);

        assertThat(result).containsEntry("passed", true)
                .containsEntry("accountCount", 1)
                .doesNotContainKey("token");
        verify(token).refreshAndVerify(tenant);
        verify(apiClient).listAccounts(tenant);
        verify(monitor).tokenCheck(true,
                "企业 tenant-a 凭证和微信客服接口权限有效，已读取 1 个客服号");
    }

    @Test
    void diagnosisRejectsEnabledLocalAccountMissingFromWeCom() throws Exception {
        WeComTenantService.TenantCredentials tenant = tenant();
        WeComKfApiClient apiClient = mock(WeComKfApiClient.class);
        when(apiClient.listAccounts(tenant)).thenReturn(List.of(
                new WeComKfApiClient.KfAccountProfile("wk-other", "其他客服", "")));
        WeComCryptoService crypto = mock(WeComCryptoService.class);
        when(crypto.isConfigured(tenant)).thenReturn(true);
        WeComKfService kf = mock(WeComKfService.class);
        when(kf.executorStatus()).thenReturn(Map.of("queued", 0));
        WeComDiagnosticsService service = new WeComDiagnosticsService(
                mock(WeComKfTokenService.class), apiClient, crypto,
                new WeComRuntimeMonitor(), kf);

        Map<String, Object> result = service.diagnostics(tenant,
                List.of(Map.of("enabled", true, "openKfid", "wk-missing")),
                "https://kf.example.com");

        assertThat(result.get("overall")).isEqualTo("incomplete");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> checks = (List<Map<String, Object>>) result.get("checks");
        assertThat(checks).anySatisfy(check -> {
            assertThat(check.get("key")).isEqualTo("accountMatch");
            assertThat(check.get("passed")).isEqualTo(false);
            assertThat(String.valueOf(check.get("detail"))).contains("wk-missing");
        });
    }

    private WeComTenantService.TenantCredentials tenant() {
        return new WeComTenantService.TenantCredentials(
                1, "tenant-a", "A 企业", "ww-corp", "secret",
                "callback-token", "a".repeat(43), true);
    }
}
