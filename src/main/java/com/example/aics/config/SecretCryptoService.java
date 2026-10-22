package com.example.aics.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * system_config 中敏感配置的信封加密。
 *
 * 主密钥只从环境变量 AICS_CONFIG_ENCRYPTION_KEY 注入，不进入数据库；格式为
 * 32 字节随机值的 Base64。密文格式固定为 ENC[v1]:base64(iv + ciphertext + tag)，
 * 便于后续轮换算法或密钥版本。
 */
@Component
public class SecretCryptoService {

    public static final String PREFIX = "ENC[v1]:";
    private static final Logger log = LoggerFactory.getLogger(SecretCryptoService.class);
    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;

    private final SecureRandom random = new SecureRandom();
    private final SecretKey key;

    public SecretCryptoService(
            @Value("${aics.config-encryption-key:}") String encodedKey,
            @Value("${spring.profiles.active:}") String activeProfiles) {
        boolean prod = Arrays.stream(activeProfiles.split(","))
                .map(String::trim).anyMatch("prod"::equals);
        this.key = decodeKey(encodedKey, prod);
    }

    private SecretKey decodeKey(String encodedKey, boolean prod) {
        if (encodedKey == null || encodedKey.isBlank()) {
            if (prod) {
                throw new IllegalStateException(
                        "生产环境必须配置 AICS_CONFIG_ENCRYPTION_KEY（32 字节随机值的 Base64）");
            }
            log.warn("未配置 AICS_CONFIG_ENCRYPTION_KEY：可以使用环境变量中的外部服务密钥，"
                    + "但管理后台暂不能把密钥写入数据库");
            return null;
        }
        try {
            byte[] bytes = Base64.getDecoder().decode(encodedKey.trim());
            if (bytes.length != 32) {
                throw new IllegalArgumentException("解码后必须恰好为 32 字节");
            }
            return new SecretKeySpec(bytes, "AES");
        } catch (IllegalArgumentException e) {
            if (prod) {
                throw new IllegalStateException("AICS_CONFIG_ENCRYPTION_KEY 格式无效：" + e.getMessage());
            }
            log.error("AICS_CONFIG_ENCRYPTION_KEY 格式无效，后台密钥写入功能已禁用：{}", e.getMessage());
            return null;
        }
    }

    public boolean isConfigured() {
        return key != null;
    }

    public boolean isEncrypted(String value) {
        return value != null && value.startsWith(PREFIX);
    }

    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) {
            return "";
        }
        requireKey();
        try {
            byte[] iv = new byte[IV_LENGTH];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] packed = ByteBuffer.allocate(iv.length + ciphertext.length)
                    .put(iv).put(ciphertext).array();
            return PREFIX + Base64.getEncoder().encodeToString(packed);
        } catch (GeneralSecurityException e) {
            throw new SecretConfigurationException("密钥加密失败", e);
        }
    }

    public String decrypt(String storedValue) {
        if (storedValue == null || storedValue.isBlank()) {
            return "";
        }
        if (!isEncrypted(storedValue)) {
            return storedValue;
        }
        requireKey();
        try {
            byte[] packed = Base64.getDecoder().decode(storedValue.substring(PREFIX.length()));
            if (packed.length <= IV_LENGTH) {
                throw new GeneralSecurityException("密文长度无效");
            }
            byte[] iv = Arrays.copyOfRange(packed, 0, IV_LENGTH);
            byte[] ciphertext = Arrays.copyOfRange(packed, IV_LENGTH, packed.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException | GeneralSecurityException e) {
            throw new SecretConfigurationException("数据库中的密钥无法解密，请检查主密钥是否正确", e);
        }
    }

    private void requireKey() {
        if (key == null) {
            throw new SecretConfigurationException(
                    "请先配置环境变量 AICS_CONFIG_ENCRYPTION_KEY，再保存后台密钥");
        }
    }

    public static class SecretConfigurationException extends RuntimeException {
        public SecretConfigurationException(String message) {
            super(message);
        }

        public SecretConfigurationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
