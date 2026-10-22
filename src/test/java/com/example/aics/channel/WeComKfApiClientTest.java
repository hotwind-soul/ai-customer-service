package com.example.aics.channel;

import com.example.aics.message.LinkCard;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WeComKfApiClientTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String AES_KEY = "jWmYm7qr5nMoAUwZRjGtBxmz3KA1tkAj3ykkR6q2B2C";
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void syncMsgSendsCallbackTokenAndParsesPagination() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        startServer("/cgi-bin/kf/sync_msg", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, """
                    {"errcode":0,"errmsg":"ok","next_cursor":"cursor-2","has_more":1,
                     "msg_list":[{"msgid":"msg-1","origin":3,"msgtype":"text",
                                  "text":{"content":"你好"}}]}
                    """);
        });

        WeComKfApiClient.SyncPage page = client().syncMessages(tenant(),
                "callback-token", "wk-customer-service", "cursor-1");

        JsonNode sent = JSON.readTree(requestBody.get());
        assertThat(sent.path("token").asText()).isEqualTo("callback-token");
        assertThat(sent.path("open_kfid").asText()).isEqualTo("wk-customer-service");
        assertThat(sent.path("cursor").asText()).isEqualTo("cursor-1");
        assertThat(sent.path("limit").asInt()).isEqualTo(1000);
        assertThat(page.hasMore()).isTrue();
        assertThat(page.nextCursor()).isEqualTo("cursor-2");
        assertThat(page.messages()).hasSize(1);
    }

    @Test
    void listsKfAccountsForPermissionCheckAndSynchronization() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        startServer("/cgi-bin/kf/account/list", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, """
                    {"errcode":0,"errmsg":"ok","account_list":[
                      {"open_kfid":"wk-one","name":"客服一","avatar":"https://example.com/a.png"},
                      {"open_kfid":"wk-two","name":"客服二","avatar":"https://example.com/b.png"}
                    ]}
                    """);
        });

        List<WeComKfApiClient.KfAccountProfile> accounts = client().listAccounts(tenant());

        JsonNode sent = JSON.readTree(requestBody.get());
        assertThat(sent.path("offset").asInt()).isZero();
        assertThat(sent.path("limit").asInt()).isEqualTo(100);
        assertThat(accounts).extracting(WeComKfApiClient.KfAccountProfile::openKfId)
                .containsExactly("wk-one", "wk-two");
        assertThat(accounts.get(0).name()).isEqualTo("客服一");
    }

    @Test
    void sendMsgRejectsHttp200BusinessError() throws Exception {
        startServer("/cgi-bin/kf/send_msg", exchange ->
                respond(exchange, 200, "{\"errcode\":40001,\"errmsg\":\"invalid credential\"}"));

        assertThatThrownBy(() -> client().sendText(tenant(), "external-user", "wk-id", "测试"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("40001")
                .hasMessageContaining("invalid credential");
    }

    @Test
    void sendsTencentAddressAsNativeLinkMessage() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        startServer("/cgi-bin/kf/send_msg", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, "{\"errcode\":0,\"errmsg\":\"ok\"}");
        });

        client().sendLink(tenant(), "external-user", "wk-id", new LinkCard(
                "昆明首渡店", "云南省昆明市官渡区星耀路99号",
                "https://apis.map.qq.com/uri/v1/marker?marker=test",
                "https://ai.zhenmeiqq.online/assets/tencent-map-card.svg"));

        JsonNode sent = JSON.readTree(requestBody.get());
        assertThat(sent.path("msgtype").asText()).isEqualTo("link");
        assertThat(sent.path("touser").asText()).isEqualTo("external-user");
        assertThat(sent.path("open_kfid").asText()).isEqualTo("wk-id");
        assertThat(sent.path("link").path("title").asText()).isEqualTo("昆明首渡店");
        assertThat(sent.path("link").path("desc").asText()).contains("官渡区");
        assertThat(sent.path("link").path("url").asText()).startsWith("https://apis.map.qq.com/");
        assertThat(sent.path("link").path("thumb_url").asText())
                .isEqualTo("https://ai.zhenmeiqq.online/assets/tencent-map-card.svg");
    }

    @Test
    void getsServiceStateAndServicerFromOfficialEndpoint() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        startServer("/cgi-bin/kf/service_state/get", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200,
                    "{\"errcode\":0,\"errmsg\":\"ok\",\"service_state\":3,\"servicer_userid\":\"zhangsan\"}");
        });

        WeComKfApiClient.ServiceStateResult result =
                client().getServiceState(tenant(), "wk-id", "external-user");

        JsonNode sent = JSON.readTree(requestBody.get());
        assertThat(sent.path("open_kfid").asText()).isEqualTo("wk-id");
        assertThat(sent.path("external_userid").asText()).isEqualTo("external-user");
        assertThat(result.serviceState()).isEqualTo(3);
        assertThat(result.servicerUserId()).isEqualTo("zhangsan");
    }

    @Test
    void transfersToWaitingPoolWithoutWebServicerAndParsesMessageCode() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        startServer("/cgi-bin/kf/service_state/trans", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200,
                    "{\"errcode\":0,\"errmsg\":\"ok\",\"msg_code\":\"handoff-code\"}");
        });

        WeComKfApiClient.ServiceStateResult result =
                client().transferServiceState(tenant(), "wk-id", "external-user", 2, null);

        JsonNode sent = JSON.readTree(requestBody.get());
        assertThat(sent.path("service_state").asInt()).isEqualTo(2);
        assertThat(sent.has("servicer_userid")).isFalse();
        assertThat(result.msgCode()).isEqualTo("handoff-code");
    }

    @Test
    void non200ResponseIsRejectedBeforeBusinessParsing() {
        assertThatThrownBy(() -> WeComKfApiClient.validateJsonResponse(502, "gateway error"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("HTTP 502");
    }

    private WeComKfApiClient client() {
        WeComKfTokenService tokenService = mock(WeComKfTokenService.class);
        when(tokenService.isConfigured(org.mockito.ArgumentMatchers.any())).thenReturn(true);
        when(tokenService.getToken(org.mockito.ArgumentMatchers.any())).thenReturn("access-token");
        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        return new WeComKfApiClient(tokenService, HttpClient.newHttpClient(), base);
    }

    private WeComTenantService.TenantCredentials tenant() {
        return new WeComTenantService.TenantCredentials(
                1, "tenant-a", "A 企业", "corp-a", "secret-a",
                "callback-token", AES_KEY, true);
    }

    private void startServer(String path, com.sun.net.httpserver.HttpHandler handler) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(path, handler);
        server.start();
    }

    private static void respond(HttpExchange exchange, int status, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
