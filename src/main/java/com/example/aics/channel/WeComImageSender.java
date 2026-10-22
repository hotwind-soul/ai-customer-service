package com.example.aics.channel;

import com.example.aics.image.ImageService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/**
 * 把本地长期保存的图片素材上传为企微临时媒体，并立即发送微信客服图片消息。
 * media_id 不落库、不长期复用；每次发送重新上传，避免临时媒体过期影响后续发送。
 */
@Service
public class WeComImageSender {

    private static final Logger log = LoggerFactory.getLogger(WeComImageSender.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final WeComKfTokenService tokenService;
    private final ImageService imageService;
    private final HttpClient http;

    @Autowired
    public WeComImageSender(WeComKfTokenService tokenService, ImageService imageService) {
        this(tokenService, imageService, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5)).build());
    }

    WeComImageSender(WeComKfTokenService tokenService, ImageService imageService, HttpClient http) {
        this.tokenService = tokenService;
        this.imageService = imageService;
        this.http = http;
    }

    /** @return 指定企业的企微接口已接受图片消息时返回 true；失败时记录日志并返回 false。 */
    public boolean send(WeComTenantService.TenantCredentials tenant, String externalUserId,
                        String openKfId, long imageId) {
        if (!tokenService.isConfigured(tenant)) {
            log.warn("[Kf图片] 企业凭证未配置或企业已停用，跳过图片素材 {}", imageId);
            return false;
        }
        try {
            if (externalUserId == null || externalUserId.isBlank()
                    || openKfId == null || openKfId.isBlank()) {
                throw new IllegalArgumentException("微信客服用户或客服账号标识为空");
            }
            return sendUploaded(tenant, externalUserId, openKfId,
                    imageService.loadForExternalSend(imageId));
        } catch (Exception e) {
            log.error("[Kf图片] 图片素材 {} 发送失败: {}", imageId, e.getMessage());
            return false;
        }
    }

    /** 发送一张本地已上传的图片（聊天上传目录中的 c_/a_ 文件），供坐席图片直达企微客户。 */
    public boolean sendUploaded(WeComTenantService.TenantCredentials tenant, String externalUserId,
                                String openKfId, ImageService.OutboundImage image) {
        if (!tokenService.isConfigured(tenant)) {
            log.warn("[Kf图片] 企业凭证未配置或企业已停用，跳过聊天图片 {}", image.filename());
            return false;
        }
        try {
            if (externalUserId == null || externalUserId.isBlank()
                    || openKfId == null || openKfId.isBlank()) {
                throw new IllegalArgumentException("微信客服用户或客服账号标识为空");
            }
            String mediaId = uploadImage(tenant, image);
            Map<String, Object> payload = imageMessagePayload(externalUserId, openKfId, mediaId);
            postJson("https://qyapi.weixin.qq.com/cgi-bin/kf/send_msg?access_token="
                    + encodedToken(tenant), payload);
            return true;
        } catch (Exception e) {
            log.error("[Kf图片] 聊天图片 {} 发送失败: {}", image.filename(), e.getMessage());
            return false;
        }
    }

    private String uploadImage(WeComTenantService.TenantCredentials tenant,
                               ImageService.OutboundImage image) throws Exception {
        String boundary = "----AicsWeCom" + UUID.randomUUID().toString().replace("-", "");
        byte[] body = multipartBody(image, boundary);
        HttpRequest request = HttpRequest.newBuilder(URI.create(
                        "https://qyapi.weixin.qq.com/cgi-bin/media/upload?access_token="
                                + encodedToken(tenant) + "&type=image"))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        JsonNode result = sendAndValidate(request);
        String mediaId = result.path("media_id").asText("");
        if (mediaId.isBlank()) throw new IllegalStateException("企微上传图片未返回 media_id");
        return mediaId;
    }

    private void postJson(String url, Map<String, Object> payload) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json; charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(payload),
                        StandardCharsets.UTF_8))
                .build();
        sendAndValidate(request);
    }

    private JsonNode sendAndValidate(HttpRequest request) throws Exception {
        HttpResponse<String> response = http.send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new IllegalStateException("企微接口 HTTP " + response.statusCode());
        }
        JsonNode result = JSON.readTree(response.body());
        if (result.has("errcode") && result.path("errcode").asInt(-1) != 0) {
            throw new IllegalStateException("企微接口错误 " + result.path("errcode").asInt()
                    + ": " + result.path("errmsg").asText("未知错误"));
        }
        return result;
    }

    private String encodedToken(WeComTenantService.TenantCredentials tenant) {
        return URLEncoder.encode(tokenService.getToken(tenant),
                StandardCharsets.UTF_8);
    }

    static Map<String, Object> imageMessagePayload(String externalUserId, String openKfId,
                                                   String mediaId) {
        return Map.of(
                "touser", externalUserId,
                "open_kfid", openKfId,
                "msgtype", "image",
                "image", Map.of("media_id", mediaId));
    }

    static byte[] multipartBody(ImageService.OutboundImage image, String boundary) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Disposition: form-data; name=\"media\"; filename=\""
                + image.filename() + "\"\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Type: " + image.contentType() + "\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8));
        out.write(image.bytes());
        out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }
}
