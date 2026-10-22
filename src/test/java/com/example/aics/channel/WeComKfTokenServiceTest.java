package com.example.aics.channel;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class WeComKfTokenServiceTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void tokenCacheIsIndependentForEveryTenant() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/cgi-bin/gettoken", exchange -> {
            requests.incrementAndGet();
            String query = exchange.getRequestURI().getRawQuery();
            String token = query != null && query.contains("corp-a") ? "access-a" : "access-b";
            respond(exchange, "{\"errcode\":0,\"access_token\":\"" + token + "\",\"expires_in\":7200}");
        });
        server.start();
        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        WeComKfTokenService service = new WeComKfTokenService(
                HttpClient.newHttpClient(), base);
        WeComTenantService.TenantCredentials tenantA = tenant(1, "a");
        WeComTenantService.TenantCredentials tenantB = tenant(2, "b");

        assertThat(service.getToken(tenantA)).isEqualTo("access-a");
        assertThat(service.getToken(tenantA)).isEqualTo("access-a");
        assertThat(service.getToken(tenantB)).isEqualTo("access-b");
        assertThat(service.getToken(tenantB)).isEqualTo("access-b");
        assertThat(requests.get()).isEqualTo(2);
    }

    private WeComTenantService.TenantCredentials tenant(long id, String suffix) {
        return new WeComTenantService.TenantCredentials(id, "tenant-" + suffix, suffix,
                "corp-" + suffix, "secret-" + suffix, "token-" + suffix,
                "jWmYm7qr5nMoAUwZRjGtBxmz3KA1tkAj3ykkR6q2B2C", true);
    }

    private void respond(HttpExchange exchange, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
