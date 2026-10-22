package com.example.aics.agent;

import com.example.aics.chat.SessionRegistry;
import com.example.aics.config.WebAuthCookieService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.List;
import java.util.Map;

/**
 * 坐席 WebSocket 通道 /ws/agent，身份令牌由 HttpOnly Cookie 提供。
 *
 * 坐席发：{"conversationId":"...", "content":"...", "imageUrl":"/uploads/images/a_..."} → 存为 agent 消息（可带图）并实时推给客户
 * 坐席收：{"type":"customer", "conversationId":"...", "content":"..."} ← 客户说话实时推来
 *         {"type":"system", "content":"..."} ← 系统提示
 */
@Component
public class AgentWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(AgentWebSocketHandler.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final AgentAccountService agentAccountService;
    private final SessionRegistry sessionRegistry;
    private final WebAuthCookieService authCookies;
    private final HumanReplyService humanReplies;

    public AgentWebSocketHandler(AgentAccountService agentAccountService,
                                 SessionRegistry sessionRegistry,
                                 WebAuthCookieService authCookies,
                                 HumanReplyService humanReplies) {
        this.agentAccountService = agentAccountService;
        this.sessionRegistry = sessionRegistry;
        this.authCookies = authCookies;
        this.humanReplies = humanReplies;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String token = authCookies.websocketAgentToken(session);
        Map<String, Object> agent = agentAccountService.validate(token);
        if (agent == null || !AgentAccountService.ROLE_AGENT.equals(String.valueOf(agent.get("role")))) {
            session.close(CloseStatus.POLICY_VIOLATION.withReason("坐席登录已失效"));
            return;
        }
        long agentId = ((Number) agent.get("id")).longValue();
        session.getAttributes().put("agentId", agentId);
        session.getAttributes().put("authToken", token);
        sessionRegistry.registerAgent(agentId, session);
        agentAccountService.markOnline(agentId);
        sendJson(session, "system", "工作台已连接");
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage textMessage) {
        try {
            Long agentId = (Long) session.getAttributes().get("agentId");
            if (agentId == null) return;
            String token = String.valueOf(session.getAttributes().getOrDefault("authToken", ""));
            Map<String, Object> current = agentAccountService.validate(token);
            if (current == null || !AgentAccountService.ROLE_AGENT.equals(String.valueOf(current.get("role")))
                    || ((Number) current.get("id")).longValue() != agentId) {
                session.close(CloseStatus.POLICY_VIOLATION.withReason("坐席登录已失效"));
                return;
            }

            JsonNode req = JSON.readTree(textMessage.getPayload());
            String conversationId = req.path("conversationId").asText("");
            String content = req.path("content").asText("").trim();
            // 坐席可附带一张聊天图片（URL 必须来自本系统上传目录，防 SSRF/外链注入）
            String imageUrl = req.path("imageUrl").asText("");
            List<Map<String, Object>> images = List.of();
            if (!imageUrl.isBlank()) {
                if (!imageUrl.matches("^/uploads/images/a_[A-Za-z0-9._-]+$")) {
                    sendJson(session, "error", "图片地址无效，请重新上传后再发送");
                    return;
                }
                images = List.of(Map.of("name", "坐席发送图片", "url", imageUrl, "type", "image"));
                if (content.isBlank()) {
                    content = HumanReplyService.IMAGE_PLACEHOLDER;
                }
            }
            if (conversationId.isBlank() || (content.isBlank() && images.isEmpty())) return;
            if (content.length() > 4000) {
                sendJson(session, "error", "单条消息不能超过 4000 个字符");
                return;
            }

            // 越权拦截（P0-4）：坐席只能给自己正在服务的会话发消息
            if (!agentAccountService.isServing(agentId, conversationId)) {
                sendJson(session, "error", "该会话不属于您（可能已结束或已转给其他坐席）");
                return;
            }

            HumanReplyService.Delivery delivery = humanReplies.send(agentId, conversationId, content, images);
            if (!delivery.accepted()) {
                sendJson(session, "error", delivery.error());
            }
        } catch (Exception e) {
            // W28：记录消息处理异常（含 JSON 解析/越权校验外的偶发错误），避免静默吞掉难排查
            log.warn("坐席 WS 消息处理失败: {}", e.getMessage());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Long agentId = (Long) session.getAttributes().get("agentId");
        if (agentId != null && sessionRegistry.removeAgentIfCurrent(agentId, session)) {
            // 没有更新的连接在服务 → 坐席下线（P1-7），避免掉线坐席仍被轮询分配
            agentAccountService.markOffline(agentId);
        }
    }

    private void sendJson(WebSocketSession session, String type, String content) {
        try {
            ObjectNode msg = JSON.createObjectNode();
            msg.put("type", type);
            msg.put("content", content);
            session.sendMessage(new TextMessage(JSON.writeValueAsString(msg)));
        } catch (Exception ignored) {
        }
    }
}
