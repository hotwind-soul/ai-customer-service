package com.example.aics.config;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class SecretCryptoServiceTest {

    private static final String KEY = Base64.getEncoder().encodeToString(
            "0123456789abcdef0123456789abcdef".getBytes(java.nio.charset.StandardCharsets.UTF_8));

    @Test
    void encryptsAndDecryptsWithoutLeavingPlaintext() {
        SecretCryptoService crypto = new SecretCryptoService(KEY, "test");
        String encrypted = crypto.encrypt("sk-sensitive-value");

        assertThat(encrypted).startsWith(SecretCryptoService.PREFIX);
        assertThat(encrypted).doesNotContain("sk-sensitive-value");
        assertThat(crypto.decrypt(encrypted)).isEqualTo("sk-sensitive-value");
    }

    @Test
    void usesRandomIvForEveryEncryption() {
        SecretCryptoService crypto = new SecretCryptoService(KEY, "test");
        assertThat(crypto.encrypt("same-value")).isNotEqualTo(crypto.encrypt("same-value"));
    }

    @Test
    void refusesDatabaseWritesWithoutMasterKey() {
        SecretCryptoService crypto = new SecretCryptoService("", "test");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> crypto.encrypt("secret"))
                .isInstanceOf(SecretCryptoService.SecretConfigurationException.class);
    }
}
