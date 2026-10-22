package com.example.aics.channel;

import com.example.aics.config.SecretCryptoService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WeComTenantServiceTest {

    @Test
    void synchronizationAddsDisabledAccountsAndPreservesExistingConfiguration() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        SecretCryptoService crypto = mock(SecretCryptoService.class);
        when(crypto.decrypt(anyString())).thenReturn("configured");
        when(jdbc.queryForList("SELECT * FROM wecom_tenant WHERE id=?", 9L))
                .thenReturn(List.of(Map.of(
                        "id", 9L, "tenant_code", "tenant-a", "name", "A 企业",
                        "corp_id", "corp-a", "secret_ciphertext", "secret",
                        "callback_token_ciphertext", "token", "aes_key_ciphertext", "aes",
                        "enabled", true)));
        when(jdbc.queryForList(
                "SELECT id, open_kfid, name FROM wecom_kf_account WHERE tenant_id=?", 9L))
                .thenReturn(List.of(Map.of(
                        "id", 21L, "open_kfid", "wk-existing", "name", "旧名称")));
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        WeComTenantService service = new WeComTenantService(jdbc, crypto);

        WeComTenantService.AccountSyncSummary summary = service.synchronizeAccounts(9L, List.of(
                new WeComKfApiClient.KfAccountProfile("wk-existing", "新名称", ""),
                new WeComKfApiClient.KfAccountProfile("wk-new", "新增客服", "")));

        assertThat(summary).isEqualTo(new WeComTenantService.AccountSyncSummary(2, 1, 1, 0, 0));
        verify(jdbc).update(
                "UPDATE wecom_kf_account SET name=?, update_time=now() WHERE id=? AND tenant_id=?",
                "新名称", 21L, 9L);
        verify(jdbc).update(
                "INSERT INTO wecom_kf_account(tenant_id, open_kfid, name, business_scope, " +
                        "welcome_message, enabled) VALUES (?,?,?,'','',FALSE) " +
                        "ON CONFLICT (tenant_id, open_kfid) DO NOTHING",
                9L, "wk-new", "新增客服");
    }
}
