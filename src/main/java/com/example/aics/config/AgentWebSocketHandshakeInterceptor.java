package com.example.aics.config;

import com.example.aics.agent.AgentAccountService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

/**
 * 坐席 WebSocket 握手鉴权：在握手阶段校验 HttpOnly Cookie 中的坐席 token，
 * 未登录/非坐席角色直接拒绝握手（401），避免先建连后关闭的无效连接。
 * 消息处理层的 agentId 校验（AgentWebSocketHandler）仍保留，作为双保险。
 */
@Component
public class AgentWebSocketHandshakeInterceptor implements HandshakeInterceptor {

    private final AgentAccountService agentAccountService;
    private final WebAuthCookieService authCookies;

    public AgentWebSocketHandshakeInterceptor(AgentAccountService agentAccountService,
                                              WebAuthCookieService authCookies) {
        this.agentAccountService = agentAccountService;
        this.authCookies = authCookies;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        String token = null;
        if (request instanceof ServletServerHttpRequest servletRequest) {
            HttpServletRequest http = servletRequest.getServletRequest();
            token = authCookies.agentToken(http);
        }
        Map<String, Object> agent = token == null ? null : agentAccountService.validate(token);
        if (agent != null && AgentAccountService.ROLE_AGENT.equals(String.valueOf(agent.get("role")))) {
            attributes.put("agentId", ((Number) agent.get("id")).longValue());
            attributes.put("agentSession", agent);
            return true;
        }
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        return false;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
    }
}
