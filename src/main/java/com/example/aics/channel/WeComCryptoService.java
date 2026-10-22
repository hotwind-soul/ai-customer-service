package com.example.aics.channel;

import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.function.Supplier;

/**
 * 企业微信回调消息加解密（channel 包骨架，2026-08-07）。
 *
 * 官方算法（WXBizMsgCrypt Java 版）：
 * - 验签：msg_signature = SHA1(字典序排序后拼接 [token, timestamp, nonce, encrypt])
 * - 解密：AES-256-CBC；key = Base64Decode(EncodingAESKey + "=")，IV = key 前 16 字节；
 *   明文 = AES 解密 → 去 PKCS7 填充 → [16字节随机][4字节网络序消息长度][消息][corpid]
 * - 加密：反向组装后 AES-CBC 加密 + Base64
 *
 * 生产运行只接受明确的企业凭证，不读取旧版全局 wecom.* 配置。
 */
@Component
public class WeComCryptoService {

    private final Supplier<Credentials> credentials;

    /** 生产构造器不绑定任何全局企业，调用时必须显式传入企业凭证。 */
    public WeComCryptoService() {
        this(() -> new Credentials("", "", ""));
    }

    /** 测试用固定凭证构造器。 */
    WeComCryptoService(String corpid, String token, String encodingAesKey) {
        this(() -> new Credentials(corpid, token, encodingAesKey));
    }

    private WeComCryptoService(Supplier<Credentials> credentials) {
        this.credentials = credentials;
    }

    public boolean isConfigured() {
        return current().configured();
    }

    public boolean isConfigured(WeComTenantService.TenantCredentials tenant) {
        return credentials(tenant).configured();
    }

    /** 验签：sha1(sort[token, timestamp, nonce, msg]) 是否等于 msgSignature（不区分大小写） */
    public boolean verifySignature(String msgSignature, String timestamp, String nonce, String msg) {
        return verifySignature(current(), msgSignature, timestamp, nonce, msg);
    }

    public boolean verifySignature(WeComTenantService.TenantCredentials tenant,
                                   String msgSignature, String timestamp, String nonce, String msg) {
        return verifySignature(credentials(tenant), msgSignature, timestamp, nonce, msg);
    }

    private boolean verifySignature(Credentials c, String msgSignature, String timestamp,
                                    String nonce, String msg) {
        if (!c.configured() || msgSignature == null) return false;
        String expected = sha1(String.join("", sorted(c.token(), timestamp, nonce, msg)));
        return expected.equalsIgnoreCase(msgSignature);
    }

    /** 解密回调密文 → 明文消息 XML（失败抛 IllegalStateException，由调用方兜底） */
    public String decrypt(String encrypt) {
        return decrypt(current(), encrypt);
    }

    public String decrypt(WeComTenantService.TenantCredentials tenant, String encrypt) {
        return decrypt(credentials(tenant), encrypt);
    }

    private String decrypt(Credentials c, String encrypt) {
        if (!c.configured()) {
            throw new IllegalStateException("回调密钥未配置，无法解密");
        }
        try {
            byte[] key = deriveKey(c);
            Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new IvParameterSpec(Arrays.copyOfRange(key, 0, 16)));
            byte[] plain = cipher.doFinal(Base64.getDecoder().decode(encrypt));

            // 去 PKCS7 填充
            int pad = plain[plain.length - 1] & 0xFF;
            if (pad < 1 || pad > 32) {
                throw new IllegalStateException("PKCS7 填充长度非法: " + pad);
            }
            byte[] unpadded = Arrays.copyOf(plain, plain.length - pad);

            // 明文 = [16字节随机][4字节网络序长度][消息][corpid]
            int msgLen = ((unpadded[16] & 0xFF) << 24) | ((unpadded[17] & 0xFF) << 16)
                    | ((unpadded[18] & 0xFF) << 8) | (unpadded[19] & 0xFF);
            String msg = new String(unpadded, 20, msgLen, StandardCharsets.UTF_8);
            String fromCorpid = new String(unpadded, 20 + msgLen, unpadded.length - 20 - msgLen,
                    StandardCharsets.UTF_8);
            if (!c.corpid().equals(fromCorpid)) {
                throw new IllegalStateException("解密后 corpid 不匹配");
            }
            return msg;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("回调消息解密失败: " + e.getMessage(), e);
        }
    }

    /** 加密回复（被动回复消息时用；Kf 场景主用主动 send_msg，此方法留作通用） */
    public String encrypt(String replyMsg) {
        return encrypt(current(), replyMsg);
    }

    public String encrypt(WeComTenantService.TenantCredentials tenant, String replyMsg) {
        return encrypt(credentials(tenant), replyMsg);
    }

    private String encrypt(Credentials c, String replyMsg) {
        if (!c.configured()) {
            throw new IllegalStateException("回调密钥未配置，无法加密");
        }
        try {
            byte[] key = deriveKey(c);
            byte[] random = new byte[16];
            new SecureRandom().nextBytes(random);
            byte[] msgBytes = replyMsg.getBytes(StandardCharsets.UTF_8);
            byte[] corpidBytes = c.corpid().getBytes(StandardCharsets.UTF_8);

            byte[] body = new byte[random.length + 4 + msgBytes.length + corpidBytes.length];
            System.arraycopy(random, 0, body, 0, random.length);
            body[16] = (byte) (msgBytes.length >>> 24);
            body[17] = (byte) (msgBytes.length >>> 16);
            body[18] = (byte) (msgBytes.length >>> 8);
            body[19] = (byte) msgBytes.length;
            System.arraycopy(msgBytes, 0, body, 20, msgBytes.length);
            System.arraycopy(corpidBytes, 0, body, 20 + msgBytes.length, corpidBytes.length);

            // PKCS7 填充
            int block = 32;
            int padLen = block - (body.length % block);
            byte[] padded = Arrays.copyOf(body, body.length + padLen);
            Arrays.fill(padded, body.length, padded.length, (byte) padLen);

            Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new IvParameterSpec(Arrays.copyOfRange(key, 0, 16)));
            return Base64.getEncoder().encodeToString(cipher.doFinal(padded));
        } catch (Exception e) {
            throw new IllegalStateException("回调消息加密失败: " + e.getMessage(), e);
        }
    }

    /** EncodingAESKey(43位) + "=" → Base64 解码得 32 字节 AES key */
    private byte[] deriveKey(Credentials credentials) {
        return Base64.getDecoder().decode(credentials.encodingAesKey() + "=");
    }

    /** 生成 msg_signature 用的签名（对外暴露供测试/回调组装用） */
    public String sign(String timestamp, String nonce, String msg) {
        return sign(current(), timestamp, nonce, msg);
    }

    public String sign(WeComTenantService.TenantCredentials tenant,
                       String timestamp, String nonce, String msg) {
        return sign(credentials(tenant), timestamp, nonce, msg);
    }

    private String sign(Credentials c, String timestamp, String nonce, String msg) {
        if (!c.configured()) {
            throw new IllegalStateException("回调密钥未配置，无法签名");
        }
        return sha1(String.join("", sorted(c.token(), timestamp, nonce, msg)));
    }

    private String[] sorted(String... values) {
        String[] copy = Arrays.stream(values).map(v -> v == null ? "" : v).toArray(String[]::new);
        Arrays.sort(copy);
        return copy;
    }

    private Credentials current() {
        Credentials c = credentials.get();
        return c == null ? new Credentials("", "", "") : c;
    }

    private Credentials credentials(WeComTenantService.TenantCredentials tenant) {
        if (tenant == null) return new Credentials("", "", "");
        return new Credentials(tenant.corpId(), tenant.callbackToken(), tenant.aesKey());
    }

    private record Credentials(String corpid, String token, String encodingAesKey) {
        private Credentials {
            corpid = corpid == null ? "" : corpid.trim();
            token = token == null ? "" : token.trim();
            encodingAesKey = encodingAesKey == null ? "" : encodingAesKey.trim();
        }

        boolean configured() {
            return !corpid.isBlank() && !token.isBlank() && encodingAesKey.length() == 43;
        }
    }

    private static String sha1(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA1 计算失败", e);
        }
    }
}
