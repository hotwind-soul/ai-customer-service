package com.example.aics.config;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

/** 在 WebSocket 握手阶段固定网页客户身份，后续消息不能自行替换 userId。 */
@Component
public class CustomerIdentityHandshakeInterceptor implements HandshakeInterceptor {

    public static final String WS_ATTRIBUTE = "customerUserId";
    private final CustomerIdentityService identities;

    public CustomerIdentityHandshakeInterceptor(CustomerIdentityService identities) {
        this.identities = identities;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        String userId = null;
        boolean secure = "wss".equalsIgnoreCase(request.getURI().getScheme());
        if (request instanceof ServletServerHttpRequest servletRequest) {
            HttpServletRequest http = servletRequest.getServletRequest();
            userId = identities.readCookie(http.getCookies());
            secure = identities.isSecure(http);
        }
        if (userId == null) {
            userId = identities.newUserId();
            response.getHeaders().add(HttpHeaders.SET_COOKIE, identities.cookie(userId, secure).toString());
        }
        attributes.put(WS_ATTRIBUTE, userId);
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
    }
}
