package com.example.aics.config;

import com.example.aics.agent.AgentWebSocketHandler;
import com.example.aics.chat.ChatWebSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * WebSocket 配置：
 * /ws/chat  客户聊天（AI 流式 + 转人工后直通坐席）
 * /ws/agent 坐席工作台通道
 *
 * W25-B2：AllowedOrigin 从环境变量 AICS_WS_ALLOWED_ORIGINS 读取（逗号分隔），
 * 未配置时默认放开（开发期），生产部署时在 .env 配成真实域名收紧 CSWSH 攻击面。
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final ChatWebSocketHandler chatWebSocketHandler;
    private final AgentWebSocketHandler agentWebSocketHandler;
    private final CustomerIdentityHandshakeInterceptor customerIdentityInterceptor;
    private final AgentWebSocketHandshakeInterceptor agentHandshakeInterceptor;
    private final String[] allowedOrigins;

    public WebSocketConfig(ChatWebSocketHandler chatWebSocketHandler,
                           AgentWebSocketHandler agentWebSocketHandler,
                           CustomerIdentityHandshakeInterceptor customerIdentityInterceptor,
                           AgentWebSocketHandshakeInterceptor agentHandshakeInterceptor,
                           @Value("${spring.profiles.active:}") String activeProfiles) {
        this.chatWebSocketHandler = chatWebSocketHandler;
        this.agentWebSocketHandler = agentWebSocketHandler;
        this.customerIdentityInterceptor = customerIdentityInterceptor;
        this.agentHandshakeInterceptor = agentHandshakeInterceptor;
        String origins = System.getenv("AICS_WS_ALLOWED_ORIGINS");
        boolean prod = java.util.Arrays.stream(activeProfiles.split(","))
                .map(String::trim).anyMatch("prod"::equals);
        if (origins == null || origins.isBlank()) {
            if (prod) {
                throw new IllegalStateException(
                        "生产环境必须配置 AICS_WS_ALLOWED_ORIGINS（逗号分隔的 https:// 来源）");
            }
            org.slf4j.LoggerFactory.getLogger(WebSocketConfig.class).info(
                    "未配置 AICS_WS_ALLOWED_ORIGINS，开发环境仅允许 localhost/127.0.0.1");
            this.allowedOrigins = new String[]{
                    "http://localhost:8080", "http://127.0.0.1:8080",
                    "https://localhost:8080", "https://127.0.0.1:8080"};
        } else {
            this.allowedOrigins = java.util.Arrays.stream(origins.split(","))
                    .map(String::trim).filter(s -> !s.isEmpty()).toArray(String[]::new);
        }
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(chatWebSocketHandler, "/ws/chat")
                .addInterceptors(customerIdentityInterceptor)
                .setAllowedOrigins(allowedOrigins);
        registry.addHandler(agentWebSocketHandler, "/ws/agent")
                .addInterceptors(agentHandshakeInterceptor)
                .setAllowedOrigins(allowedOrigins);
    }
}
