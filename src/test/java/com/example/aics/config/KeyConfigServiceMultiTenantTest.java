package com.example.aics.config;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class KeyConfigServiceMultiTenantTest {

    private static final Set<String> LEGACY_WECOM_KEYS = Set.of(
            "wecom.corpid", "wecom.secret", "wecom.token",
            "wecom.aes_key", "wecom.open_kfid");

    @Test
    void legacySingleTenantCredentialsAreNotExposedOrWritable() {
        KeyConfigService service = new KeyConfigService(
                mock(ConfigService.class), mock(SecretCryptoService.class));

        assertThat(service.knownKeys().keySet()).doesNotContainAnyElementsOf(LEGACY_WECOM_KEYS);
        LEGACY_WECOM_KEYS.forEach(key -> assertThat(service.isSecretKey(key)).isFalse());
        assertThat(service.knownKeys()).containsKeys(
                "ai.dashscope_key", "map.tencent_key", "wecom.asr_secret_id")
                .doesNotContainKeys("ai.deepseek_key", "ai.bigmodel_key");
    }
}
