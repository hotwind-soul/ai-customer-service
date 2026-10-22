package com.example.aics.channel;

import com.example.aics.message.LinkCard;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 微信客服 HTTP API 客户端。
 *
 * 统一负责 access_token、HTTP 状态码和企微 errcode 校验，避免业务层把
 * “HTTP 200、业务失败”误判为发送成功。
 */
@Component
public class WeComKfApiClient {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final URI DEFAULT_BASE_URI = URI.create("https://qyapi.weixin.qq.com");
    private static final int SYNC_LIMIT = 1000;
    private static final int ACCOUNT_PAGE_SIZE = 100;
    private static final int MAX_ACCOUNT_PAGES = 100;
    private static final int MAX_MEDIA_BYTES = 20 * 1024 * 1024;

    private final WeComKfTokenService tokenService;
    private final HttpClient http;
    private final URI baseUri;

    @Autowired
    public WeComKfApiClient(WeComKfTokenService tokenService) {
        this(tokenService, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5)).build(), DEFAULT_BASE_URI);
    }

    WeComKfApiClient(WeComKfTokenService tokenService, HttpClient http, URI baseUri) {
        this.tokenService = tokenService;
        this.http = http;
        this.baseUri = baseUri;
    }

    record SyncPage(List<JsonNode> messages, String nextCursor, boolean hasMore) {}

    /** 企微客服会话状态查询/变更的统一结果。 */
    record ServiceStateResult(int serviceState, String servicerUserId, String msgCode) {}

    /** 企微后台中的微信客服账号；用于权限诊断和免手填同步。 */
    record KfAccountProfile(String openKfId, String name, String avatar) {}

    /**
     * 读取当前凭证有权管理的全部微信客服账号。
     * 该接口比单纯 gettoken 更适合接入诊断：既验证凭证，也验证微信客服接口权限。
     */
    List<KfAccountProfile> listAccounts(WeComTenantService.TenantCredentials tenant) throws Exception {
        Map<String, KfAccountProfile> accounts = new LinkedHashMap<>();
        int offset = 0;
        for (int page = 0; page < MAX_ACCOUNT_PAGES; page++) {
            JsonNode result = postJson(tenant, "/cgi-bin/kf/account/list", Map.of(
                    "offset", offset,
                    "limit", ACCOUNT_PAGE_SIZE));
            JsonNode list = result.path("account_list");
            if (!list.isArray()) {
                throw new IllegalStateException("企微客服账号列表返回格式错误");
            }
            int received = 0;
            for (JsonNode item : list) {
                received++;
                String openKfId = item.path("open_kfid").asText("").trim();
                if (openKfId.isBlank()) {
                    throw new IllegalStateException("企微客服账号列表包含空 open_kfid");
                }
                String name = item.path("name").asText("").trim();
                accounts.putIfAbsent(openKfId, new KfAccountProfile(
                        openKfId,
                        name.isBlank() ? openKfId : name,
                        item.path("avatar").asText("")));
            }
            if (received < ACCOUNT_PAGE_SIZE) return List.copyOf(accounts.values());
            offset += received;
        }
        throw new IllegalStateException("企微客服账号分页超过安全上限 "
                + (ACCOUNT_PAGE_SIZE * MAX_ACCOUNT_PAGES));
    }

    /** 根据回调 Token 拉取一页真实消息；回调本身不携带完整客户消息。 */
    SyncPage syncMessages(WeComTenantService.TenantCredentials tenant, String callbackToken,
                          String openKfId, String cursor) throws Exception {
        if (callbackToken == null || callbackToken.isBlank()) {
            throw new IllegalArgumentException("微信客服回调 Token 为空");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        if (cursor != null && !cursor.isBlank()) payload.put("cursor", cursor);
        payload.put("token", callbackToken);
        payload.put("limit", SYNC_LIMIT);
        payload.put("voice_format", 0);
        if (openKfId != null && !openKfId.isBlank()) payload.put("open_kfid", openKfId);

        JsonNode result = postJson(tenant, "/cgi-bin/kf/sync_msg", payload);
        List<JsonNode> messages = new ArrayList<>();
        JsonNode list = result.path("msg_list");
        if (list.isArray()) list.forEach(messages::add);
        return new SyncPage(List.copyOf(messages), result.path("next_cursor").asText(""),
                result.path("has_more").asInt(0) == 1);
    }

    void sendText(WeComTenantService.TenantCredentials tenant, String externalUserId,
                  String openKfId, String content) throws Exception {
        if (externalUserId == null || externalUserId.isBlank()
                || openKfId == null || openKfId.isBlank()) {
            throw new IllegalArgumentException("微信客服用户或客服账号标识为空");
        }
        postJson(tenant, "/cgi-bin/kf/send_msg", Map.of(
                "touser", externalUserId,
                "open_kfid", openKfId,
                "msgtype", "text",
                "text", Map.of("content", content == null ? "" : content)));
    }

    /** 发送企微原生图文链接卡片；点击后打开腾讯地图地址页。 */
    void sendLink(WeComTenantService.TenantCredentials tenant, String externalUserId,
                  String openKfId, LinkCard card) throws Exception {
        if (externalUserId == null || externalUserId.isBlank()
                || openKfId == null || openKfId.isBlank()) {
            throw new IllegalArgumentException("微信客服用户或客服账号标识为空");
        }
        if (card == null || !card.isDeliverable()) {
            throw new IllegalArgumentException("微信客服图文链接卡片不完整");
        }
        if (!card.thumbnailUrl().startsWith("https://")
                && !card.thumbnailUrl().startsWith("http://")) {
            throw new IllegalArgumentException("微信客服图文卡片缩略图必须是公网绝对地址");
        }
        postJson(tenant, "/cgi-bin/kf/send_msg", Map.of(
                "touser", externalUserId,
                "open_kfid", openKfId,
                "msgtype", "link",
                "link", Map.of(
                        "title", card.title(),
                        "desc", card.description(),
                        "url", card.url(),
                        "thumb_url", card.thumbnailUrl())));
    }

    /** enter_session 等事件必须使用事件响应 code，而不是普通 touser 发送入口。 */
    void sendTextOnEvent(WeComTenantService.TenantCredentials tenant,
                         String eventCode, String content) throws Exception {
        if (eventCode == null || eventCode.isBlank()) {
            throw new IllegalArgumentException("微信客服事件响应 code 为空");
        }
        postJson(tenant, "/cgi-bin/kf/send_msg_on_event", Map.of(
                "code", eventCode,
                "msgtype", "text",
                "text", Map.of("content", content == null ? "" : content)));
    }

    ServiceStateResult getServiceState(WeComTenantService.TenantCredentials tenant,
                                       String openKfId, String externalUserId) throws Exception {
        requireConversationIdentity(openKfId, externalUserId);
        JsonNode result = postJson(tenant, "/cgi-bin/kf/service_state/get", Map.of(
                "open_kfid", openKfId,
                "external_userid", externalUserId));
        int serviceState = result.path("service_state").asInt(-1);
        if (serviceState < 0 || serviceState > 4) {
            throw new IllegalStateException("企微会话状态返回值无效: " + serviceState);
        }
        return new ServiceStateResult(serviceState,
                result.path("servicer_userid").asText(""), "");
    }

    /** 应用消息文本推送（把客户画像摘要等推给接待成员；需 agentid 具备对该成员的推送权限）。 */
    String pushAppTextMessage(WeComTenantService.TenantCredentials tenant,
                              String toUserId, String agentId, String content) throws Exception {
        if (toUserId == null || toUserId.isBlank()) {
            throw new IllegalArgumentException("应用消息 touser 为空");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("touser", toUserId);
        payload.put("msgtype", "text");
        try {
            // 企微接口要求 agentid 为整型；配置错误时抛出由调用方记录并跳过
            payload.put("agentid", Integer.parseInt(agentId.trim()));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("wecom.appAgentId 必须是纯数字的企微应用 AgentId");
        }
        payload.put("text", Map.of("content", content == null ? "" : content));
        JsonNode result = postJson(tenant, "/cgi-bin/message/send", payload);
        return result.path("msgid").asText("");
    }

    ServiceStateResult transferServiceState(WeComTenantService.TenantCredentials tenant,
                                             String openKfId, String externalUserId,
                                             int targetState, String servicerUserId) throws Exception {
        requireConversationIdentity(openKfId, externalUserId);
        if (targetState < 1 || targetState > 4) {
            throw new IllegalArgumentException("企微目标会话状态必须在 1-4 之间");
        }
        if (targetState == 3 && (servicerUserId == null || servicerUserId.isBlank())) {
            throw new IllegalArgumentException("转给指定接待人员时必须提供 servicer_userid");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("open_kfid", openKfId);
        payload.put("external_userid", externalUserId);
        payload.put("service_state", targetState);
        if (servicerUserId != null && !servicerUserId.isBlank()) {
            payload.put("servicer_userid", servicerUserId);
        }
        JsonNode result = postJson(tenant, "/cgi-bin/kf/service_state/trans", payload);
        return new ServiceStateResult(targetState,
                servicerUserId == null ? "" : servicerUserId,
                result.path("msg_code").asText(""));
    }

    byte[] downloadMedia(WeComTenantService.TenantCredentials tenant, String mediaId) throws Exception {
        if (mediaId == null || mediaId.isBlank()) {
            throw new IllegalArgumentException("微信客服媒体 ID 为空");
        }
        HttpRequest request = HttpRequest.newBuilder(apiUri(
                        "/cgi-bin/media/get?access_token=" + encodedToken(tenant)
                                + "&media_id=" + URLEncoder.encode(mediaId, StandardCharsets.UTF_8)))
                .timeout(Duration.ofSeconds(15)).GET().build();
        HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("企微媒体接口 HTTP " + response.statusCode());
        }
        byte[] body = response.body() == null ? new byte[0] : response.body();
        String contentType = response.headers().firstValue("Content-Type").orElse("");
        if (contentType.toLowerCase().contains("json") || looksLikeJson(body)) {
            JsonNode error = validateJsonResponse(response.statusCode(),
                    new String(body, StandardCharsets.UTF_8));
            throw new IllegalStateException("企微媒体接口返回了非媒体 JSON: " + error);
        }
        if (body.length == 0 || body.length > MAX_MEDIA_BYTES) {
            throw new IllegalStateException("企微媒体文件必须大于 0 且不超过 20MB");
        }
        return body;
    }

    private JsonNode postJson(WeComTenantService.TenantCredentials tenant, String path,
                              Map<String, Object> payload) throws Exception {
        if (!tokenService.isConfigured(tenant)) {
            throw new IllegalStateException("企业微信客服 corpid/secret 未配置或企业已停用");
        }
        HttpRequest request = HttpRequest.newBuilder(apiUri(path + "?access_token=" + encodedToken(tenant)))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json; charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(payload),
                        StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = http.send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return validateJsonResponse(response.statusCode(), response.body());
    }

    static JsonNode validateJsonResponse(int statusCode, String body) throws Exception {
        if (statusCode != 200) {
            throw new IllegalStateException("企微接口 HTTP " + statusCode);
        }
        if (body == null || body.isBlank()) {
            throw new IllegalStateException("企微接口返回空响应");
        }
        JsonNode result = JSON.readTree(body);
        if (result == null || !result.isObject()) {
            throw new IllegalStateException("企微接口返回格式错误");
        }
        if (result.has("errcode") && result.path("errcode").asInt(-1) != 0) {
            throw new IllegalStateException("企微接口错误 " + result.path("errcode").asInt()
                    + ": " + result.path("errmsg").asText("未知错误"));
        }
        return result;
    }

    private URI apiUri(String pathAndQuery) {
        return baseUri.resolve(pathAndQuery);
    }

    private static void requireConversationIdentity(String openKfId, String externalUserId) {
        if (openKfId == null || openKfId.isBlank()
                || externalUserId == null || externalUserId.isBlank()) {
            throw new IllegalArgumentException("微信客服用户或客服账号标识为空");
        }
    }

    private String encodedToken(WeComTenantService.TenantCredentials tenant) {
        return URLEncoder.encode(tokenService.getToken(tenant),
                StandardCharsets.UTF_8);
    }

    private static boolean looksLikeJson(byte[] body) {
        for (byte b : body) {
            char c = (char) (b & 0xff);
            if (Character.isWhitespace(c)) continue;
            return c == '{' || c == '[';
        }
        return false;
    }
}
