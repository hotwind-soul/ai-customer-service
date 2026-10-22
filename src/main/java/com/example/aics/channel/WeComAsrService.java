package com.example.aics.channel;

import com.example.aics.config.KeyConfigService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Map;

/**
 * 腾讯云一句话识别（ASR，channel 包骨架，2026-08-07）。
 *
 * 企微客户发语音（含川渝方言）→ 下载媒体 → 本服务转文字 → 进 ChatEngine 上下文。
 * 调用腾讯云 asr.tencentcloudapi.com（TC3-HMAC-SHA256 签名，一句话识别 16k_zh 引擎）。
 * SecretId/SecretKey 未配置时仅警告，不阻塞启动（真实联调需腾讯云开通语音识别）。
 */
@Component
public class WeComAsrService {

    private static final Logger log = LoggerFactory.getLogger(WeComAsrService.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String HOST = "asr.tencentcloudapi.com";
    private static final String ENDPOINT = "https://" + HOST;

    private final KeyConfigService keys;
    private final String region;
    private final HttpClient http;

    public WeComAsrService(KeyConfigService keys,
                           @Value("${wecom.kf.asr-region:ap-guangzhou}") String region) {
        this.keys = keys;
        this.region = region;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5)).build();
        if (!isConfigured()) {
            log.warn("[WeComAsr] 腾讯云语音识别凭证未配置（管理后台设置页 wecom.asr_secret_id/key），语音转文字暂不可用");
        }
    }

    public boolean isConfigured() {
        return !keys.get("wecom.asr_secret_id", "").isBlank()
                && !keys.get("wecom.asr_secret_key", "").isBlank();
    }

    /**
     * 语音字节 → 文字（一句话识别）。
     * @param audioBytes  语音文件字节（企微 media/get_media 下载的 amr/silk 等格式需先转 wav/mp3 16k）
     * @param voiceFormat wav / mp3 / amr
     * @param isDialect   是否方言（川渝方言走 16k_zh 引擎足够，可扩展）
     */
    public String recognize(byte[] audioBytes, String voiceFormat, boolean isDialect) {
        if (!isConfigured()) {
            throw new IllegalStateException("腾讯云 ASR 凭证未配置，无法语音转文字");
        }
        try {
            String secretId = keys.get("wecom.asr_secret_id", "");
            String secretKey = keys.get("wecom.asr_secret_key", "");
            String base64Audio = Base64.getEncoder().encodeToString(audioBytes);
            // 一句话识别（同步）请求参数
            String body = JSON.writeValueAsString(Map.of(
                    "EngineModelType", isDialect ? "16k_zh_dialect" : "16k_zh",
                    "ChannelNum", 1,
                    "ResTextFormat", 0,
                    "SourceType", 1,
                    "VoiceFormat", voiceFormat == null ? "wav" : voiceFormat,
                    "Data", base64Audio));

            String service = "asr";
            String action = "SentenceRecognition";
            String version = "2019-06-14";
            String timestamp = String.valueOf(System.currentTimeMillis() / 1000);
            ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
            String date = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));

            // TC3-HMAC-SHA256 签名
            String payloadHash = sha256Hex(body);
            String canonicalHeaders = "content-type:application/json; charset=utf-8\nhost:" + HOST + "\nx-tc-action:" + action.toLowerCase() + "\n";
            String signedHeaders = "content-type;host;x-tc-action";
            String canonicalRequest = "POST\n/\n\n" + canonicalHeaders + "\n" + signedHeaders + "\n" + payloadHash;
            String credentialScope = date + "/" + service + "/tc3_request";
            String stringToSign = "TC3-HMAC-SHA256\n" + timestamp + "\n" + credentialScope + "\n" + sha256Hex(canonicalRequest);
            String signature = Base64.getEncoder().encodeToString(
                    hmacSha256(hmacSha256(hmacSha256(hmacSha256(("TC3" + secretKey).getBytes(StandardCharsets.UTF_8),
                            date.getBytes(StandardCharsets.UTF_8)), service.getBytes(StandardCharsets.UTF_8)),
                            "tc3_request".getBytes(StandardCharsets.UTF_8)), stringToSign.getBytes(StandardCharsets.UTF_8)));
            String authorization = "TC3-HMAC-SHA256 Credential=" + secretId + "/" + credentialScope
                    + ", SignedHeaders=" + signedHeaders + ", Signature=" + signature;

            HttpRequest req = HttpRequest.newBuilder(URI.create(ENDPOINT))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .header("Host", HOST)
                    .header("X-TC-Action", action)
                    .header("X-TC-Timestamp", timestamp)
                    .header("X-TC-Version", version)
                    .header("X-TC-Region", region)
                    .header("Authorization", authorization)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            JsonNode node = JSON.readTree(resp.body());
            JsonNode error = node.path("Response").path("Error");
            if (!error.isMissingNode() && !error.path("Code").asText().isEmpty()) {
                throw new IllegalStateException("腾讯云 ASR 调用失败: " + error.path("Message").asText());
            }
            String result = node.path("Response").path("Result").asText();
            if (result.isBlank()) {
                throw new IllegalStateException("语音识别结果为空（语音可能不清晰或格式不支持）");
            }
            return result;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("语音识别异常: " + e.getMessage(), e);
        }
    }

    private static String sha256Hex(String s) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] digest = md.digest(s.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : digest) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static byte[] hmacSha256(byte[] key, byte[] data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data);
    }
}
