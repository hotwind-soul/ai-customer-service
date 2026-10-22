package com.example.aics.channel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 微信客服 access_token 管理（channel 包骨架，2026-08-07）。
 *
 * 注意：新接入应使用已在“微信客服 → 可调用接口的应用”中授权的自建应用 Secret。
 * 地址：GET https://qyapi.weixin.qq.com/cgi-bin/gettoken?corpid=&corpsecret=
 *
 * 每个企业使用独立缓存，并在过期前 5 分钟自动刷新。系统不再读取旧版全局凭证。
 */
@Component
public class WeComKfTokenService {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final HttpClient http;
    private final URI apiBaseUri;

    private final Map<String, TenantToken> tenantTokens = new ConcurrentHashMap<>();

    private record TenantToken(String token, long expiresAtMs) {}

    public WeComKfTokenService() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
                URI.create("https://qyapi.weixin.qq.com"));
    }

    WeComKfTokenService(HttpClient http, URI apiBaseUri) {
        this.http = http;
        this.apiBaseUri = apiBaseUri;
    }

    public boolean isConfigured(WeComTenantService.TenantCredentials tenant) {
        return tenant != null && tenant.enabled() && tenant.apiConfigured();
    }

    /** 每个企业独立缓存 access_token，凭证变更后通过指纹自动切换缓存。 */
    public String getToken(WeComTenantService.TenantCredentials tenant) {
        if (!isConfigured(tenant)) {
            throw new IllegalStateException("企业微信客服 corpid/secret 未配置或企业已停用");
        }
        String cacheKey = tenantCacheKey(tenant);
        TenantToken cached = tenantTokens.get(cacheKey);
        if (cached != null && Instant.now().toEpochMilli() < cached.expiresAtMs()) {
            return cached.token();
        }
        synchronized (tenantTokens) {
            cached = tenantTokens.get(cacheKey);
            if (cached != null && Instant.now().toEpochMilli() < cached.expiresAtMs()) {
                return cached.token();
            }
            TenantToken fetched = fetchTenantToken(tenant);
            tenantTokens.entrySet().removeIf(entry -> entry.getKey().startsWith(tenant.id() + ":"));
            tenantTokens.put(cacheKey, fetched);
            return fetched.token();
        }
    }

    public void refreshAndVerify(WeComTenantService.TenantCredentials tenant) {
        if (!isConfigured(tenant)) {
            throw new IllegalStateException("企业微信客服 corpid/secret 未配置或企业已停用");
        }
        synchronized (tenantTokens) {
            tenantTokens.entrySet().removeIf(entry -> entry.getKey().startsWith(tenant.id() + ":"));
            TenantToken fetched = fetchTenantToken(tenant);
            tenantTokens.put(tenantCacheKey(tenant), fetched);
        }
    }

    private TenantToken fetchTenantToken(WeComTenantService.TenantCredentials tenant) {
        try {
            String url = tokenUrl(tenant.corpId(), tenant.secret());
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(TIMEOUT).GET().build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode node = JSON.readTree(response.body());
            int errcode = node.path("errcode").asInt(-1);
            if (response.statusCode() != 200 || errcode != 0) {
                throw new IllegalStateException("获取企业 access_token 失败 tenant=" + tenant.code()
                        + " errcode=" + errcode + " errmsg=" + node.path("errmsg").asText());
            }
            String token = node.path("access_token").asText("");
            if (token.isBlank()) throw new IllegalStateException("企业 access_token 返回为空");
            int expiresIn = node.path("expires_in").asInt(7200);
            return new TenantToken(token,
                    Instant.now().plusSeconds(Math.max(60, expiresIn - 300)).toEpochMilli());
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("获取企业 access_token 异常: " + e.getMessage(), e);
        }
    }

    private String credentialFingerprint(String corpid, String secret) {
        return Integer.toHexString((String.valueOf(corpid) + "\u0000" + String.valueOf(secret)).hashCode());
    }

    private String tenantCacheKey(WeComTenantService.TenantCredentials tenant) {
        return tenant.id() + ":" + credentialFingerprint(tenant.corpId(), tenant.secret());
    }

    private String tokenUrl(String corpId, String secret) {
        String base = apiBaseUri.toString();
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return base + "/cgi-bin/gettoken?corpid="
                + URLEncoder.encode(corpId == null ? "" : corpId, StandardCharsets.UTF_8)
                + "&corpsecret="
                + URLEncoder.encode(secret == null ? "" : secret, StandardCharsets.UTF_8);
    }

}
