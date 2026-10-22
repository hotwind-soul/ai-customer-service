package com.example.aics.chat;

import com.example.aics.ai.ModelGateway;
import com.example.aics.config.CustomerIdentityHandshakeInterceptor;
import com.example.aics.knowledge.VectorStore;
import com.example.aics.message.LinkCard;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * WebSocket 流式聊天：AI 回答边生成边推给前端（逐字蹦的效果）。
 *
 * 协议（都是 JSON 文本）：
 *   前端发：{"message":"问题", "conversationId":"可选"}（身份取服务端 HttpOnly Cookie）
 *   后端推：{"type":"chunk", "content":"片段"}                          —— 流式片段，多条
 *          {"type":"done", "conversationId":"...", "sources":[...]}   —— 结束
 *          {"type":"error", "content":"原因"}
 *          {"type":"forwarded"}                                        —— 已转人工，消息直通坐席
 *
 * 第 16~17 周：会话处于 human 状态时，AI 退出，客户消息直接转给坐席工作台。
 * 模型调用统一走 ModelGateway（主备切换 + 统一超时）。
 *
 * W25-S1：WebSocket 走 Redis 按 IP 限流（REST 限流只覆盖 /api/chat/**，这里补上主力通道）。
 * W25-B3：异常只记日志，不回传内部信息给客户端。
 */
@Component
public class ChatWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(ChatWebSocketHandler.class);

    /** WebSocket 限流：每个 IP 每分钟最多 60 条（流式是主力通道，比 REST 阈值放宽） */
    private static final int WS_LIMIT_PER_MINUTE = 60;
    private static final int MAX_MESSAGE_CHARS = 4000;

    private static final ObjectMapper JSON = new ObjectMapper();

    private final ChatEngine chatEngine;
    private final ConversationService conversationService;
    private final SessionRegistry sessionRegistry;
    private final ModelGateway modelGateway;
    private final StringRedisTemplate redis;
    private final ConversationTurnGuard turnGuard;

    public ChatWebSocketHandler(ChatEngine chatEngine,
                                ConversationService conversationService,
                                SessionRegistry sessionRegistry,
                                ModelGateway modelGateway,
                                StringRedisTemplate redis,
                                ConversationTurnGuard turnGuard) {
        this.chatEngine = chatEngine;
        this.conversationService = conversationService;
        this.sessionRegistry = sessionRegistry;
        this.modelGateway = modelGateway;
        this.redis = redis;
        this.turnGuard = turnGuard;
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage textMessage) {
        ResponseTiming timing = ResponseTiming.start();
        ConversationTurnGuard.Lease turn = null;
        try {
            // W25-S1：按 IP 限流（Redis 故障自动放行，不阻塞主流程）
            String ip = session.getRemoteAddress() == null ? null
                    : session.getRemoteAddress().getAddress().getHostAddress();
            if (rateLimited(ip)) {
                sendQuietly(session, error("您提问太频繁啦，请稍等一分钟再试"));
                return;
            }

            JsonNode req = JSON.readTree(textMessage.getPayload());
            String type = req.path("type").asText("").trim();
            if ("ping".equals(type)) {
                ObjectNode pong = JSON.createObjectNode();
                pong.put("type", "pong");
                sendQuietly(session, pong);
                return;
            }
            String message = req.path("message").asText("").trim();
            if (message.isEmpty()) {
                sendQuietly(session, error("消息不能为空"));
                return;
            }
            if (message.length() > MAX_MESSAGE_CHARS) {
                sendQuietly(session, error("单条消息不能超过 " + MAX_MESSAGE_CHARS + " 个字符"));
                return;
            }
            String conversationId = req.path("conversationId").asText(null);
            List<Map<String, Object>> userImages = customerImages(req);
            String userId = (String) session.getAttributes()
                    .get(CustomerIdentityHandshakeInterceptor.WS_ATTRIBUTE);
            if (userId == null || userId.isBlank()) {
                sendQuietly(session, error("客户身份无效，请刷新页面后重试"));
                return;
            }
            // 定位消息（客户点"发位置"按钮时携带经纬度）
            Double lat = req.hasNonNull("lat") ? req.get("lat").asDouble() : null;
            Double lng = req.hasNonNull("lng") ? req.get("lng").asDouble() : null;

            turn = turnGuard.acquire(conversationId, userId);
            // 先确保会话存在并注册客户连接（坐席发消息要能推过来）
            String convId = conversationService.ensureConversation(conversationId, userId);
            sessionRegistry.registerCustomer(convId, session);
            session.getAttributes().put("conversationId", convId);

            // 会话已转人工：AI 退出，消息直通坐席
            Map<String, Object> route = conversationService.getRoute(convId);
            String status = String.valueOf(route.getOrDefault("status", "active"));
            Long agentId = route.get("agent_id") instanceof Number number ? number.longValue() : null;
            if ("human".equals(status) && agentId != null && !sessionRegistry.isAgentOnline(agentId)) {
                // 幽灵坐席：会话仍挂在某坐席名下，但该坐席的 WS 已断（关浏览器/掉线/崩溃）。
                // 此时原逻辑会把消息存库后直接"转发"到一个不存在的连接（返回值被忽略），
                // 客户却收到"已转发"，消息实际无人接收、AI 也永久退出——会话就此悬空。
                // 这里当场把会话退回排队队列，让客户拿到真实的"排队中"反馈，并可被其他坐席接入。
                if (conversationService.releaseFromUnavailableAgent(convId, agentId)) {
                    log.warn("坐席 {} 已离线，会话 {} 退回排队队列", agentId, convId);
                    status = "need_human";
                }
            }
            if ("human".equals(status) && agentId != null) {
                long messageId = conversationService.saveMessage(convId, "user", message, null);
                conversationService.saveImageAttachments(messageId, userImages);
                ObjectNode fwd = JSON.createObjectNode();
                fwd.put("type", "customer");
                fwd.put("conversationId", convId);
                fwd.put("content", message);
                if (!userImages.isEmpty()) {
                    fwd.set("images", JSON.valueToTree(userImages));
                }
                sessionRegistry.sendToAgent(agentId, JSON.writeValueAsString(fwd));
                ObjectNode ack = JSON.createObjectNode();
                ack.put("type", "forwarded");
                ack.put("conversationId", convId);
                sendQuietly(session, ack);
                return;
            }
            if ("human".equals(status) || "need_human".equals(status)) {
                long messageId = conversationService.saveMessage(convId, "user", message, null);
                conversationService.saveImageAttachments(messageId, userImages);
                ObjectNode queued = JSON.createObjectNode();
                queued.put("type", "queued");
                queued.put("conversationId", convId);
                queued.put("status", status);
                queued.put("content", "human".equals(status)
                        ? "消息已记录，人工客服会继续为您处理"
                        : "消息已记录，您仍在人工客服排队中");
                sendQuietly(session, queued);
                return;
            }

            // 网页端入口：fromWeb=true → 不发送欢迎语，初始进入选项选择页（欢迎语仅微信端发送）
            ChatEngine.Prepared prepared = chatEngine.prepare(message, convId, userId, lat, lng, true);

            // 新用户接入欢迎语：会话首条消息时先发欢迎语（独立消息类型，前端单独渲染成一个 AI 气泡，
            // 不能走 chunk——chunk 会追加到当前流式气泡，导致欢迎语和正常回复拼成一条消息）。
            // 不入库，避免污染会话记录。
            if (prepared.welcome() != null) {
                ObjectNode w = JSON.createObjectNode();
                w.put("type", "welcome");
                w.put("content", prepared.welcome());
                sendQuietly(session, w);
            }

            // FAQ 高置信直回：标准答案秒回，不调模型
            if (prepared.directAnswer() != null) {
                String answer = chatEngine.finalizeAnswer(prepared, prepared.directAnswer());
                chatEngine.persist(prepared, message, answer, prepared.replyType(), userImages);
                sendAnswer(session, prepared.conversationId(), answer, timing);
                chatEngine.markImagesSent(prepared.conversationId(), prepared.images());
                sendQuietly(session, done(prepared.conversationId(), prepared.displaySources(),
                        prepared.images(), prepared.linkCards()));
                return;
            }

            // Agent 业务工具、澄清追问或安全拒答都是直回：直接回复，不调模型。
            if (prepared.agentAnswer() != null) {
                String answer = chatEngine.finalizeAnswer(prepared, prepared.agentAnswer());
                chatEngine.persist(prepared, message, answer, prepared.replyType(), userImages);
                sendAnswer(session, prepared.conversationId(), answer, timing);
                chatEngine.markImagesSent(prepared.conversationId(), prepared.images());
                sendQuietly(session, done(prepared.conversationId(), prepared.displaySources(),
                        prepared.images(), prepared.linkCards()));
                return;
            }

            // 库外问题：纯兜底（正常情况下已在上面的 agentAnswer 分支返回）
            if (prepared.refused()) {
                String answer = chatEngine.finalizeAnswer(prepared, chatEngine.refusalAnswer());
                chatEngine.persist(prepared, message, answer, "REFUSAL", userImages);
                sendAnswer(session, prepared.conversationId(), answer, timing);
                chatEngine.markImagesSent(prepared.conversationId(), prepared.images());
                sendQuietly(session, done(prepared.conversationId(), List.of(),
                        prepared.images(), prepared.linkCards()));
                return;
            }

            // 流式调大模型（走网关，主备切换）：每收到一个片段就推给前端
            ConversationTurnGuard.Lease asyncTurn = turn;
            turn = null;
            try {
                modelGateway.stream(prepared.modelPrompt(), new StreamingChatResponseHandler() {
                private final StringBuilder full = new StringBuilder();

                @Override
                public void onPartialResponse(String partial) {
                    full.append(partial);
                }

                @Override
                public void onCompleteResponse(ChatResponse response) {
                    try {
                        String guarded = chatEngine.finalizeAnswer(prepared, full.toString());
                        // 完整结果先过后置合规校验再送达，避免违规片段在流式过程中先暴露给客户。
                        sendAnswer(session, prepared.conversationId(), guarded, timing);
                        chatEngine.persist(prepared, message, guarded, "AI", userImages);
                        chatEngine.markImagesSent(prepared.conversationId(), prepared.images());
                        sendQuietly(session, done(prepared.conversationId(), prepared.displaySources(),
                                prepared.images(), prepared.linkCards()));
                    } finally {
                        asyncTurn.close();
                    }
                }

                @Override
                public void onError(Throwable error) {
                    try {
                        // 落库兜底（P1-17）：用户消息与失败原因都入库，保证质检/报表数据完整
                        String fallback = "AI 服务暂时不可用，请稍后再试";
                        chatEngine.persist(prepared, message, fallback, "ERROR", userImages);
                        sendQuietly(session, error(fallback));
                    } finally {
                        asyncTurn.close();
                    }
                }
                });
            } catch (RuntimeException e) {
                asyncTurn.close();
                throw e;
            }
        } catch (Exception e) {
            // W25-B3：异常只记日志，不回传内部细节（SQL/类名等泄露面）
            log.error("处理 WS 聊天消息失败", e);
            sendQuietly(session, error("请求处理失败，请稍后再试"));
        } finally {
            if (turn != null) turn.close();
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String convId = (String) session.getAttributes().get("conversationId");
        if (convId != null) {
            // 只删当前连接的注册，防多标签页/重连时旧连接误删新连接（W25-B2 连带）
            sessionRegistry.removeCustomerIfCurrent(convId, session);
        }
    }

    /** 每个 IP 每分钟最多 60 条消息；Redis 异常放行（限流是锦上添花，不能因它挂了影响聊天） */
    private boolean rateLimited(String ip) {
        if (ip == null) return false;
        try {
            String key = "rl:ws:" + ip;
            Long count = redis.opsForValue().increment(key);
            if (count != null && count == 1L) {
                redis.expire(key, Duration.ofMinutes(1));
            }
            return count != null && count > WS_LIMIT_PER_MINUTE;
        } catch (Exception e) {
            return false;
        }
    }

    private void sendAnswer(WebSocketSession session, String conversationId, String content, ResponseTiming timing) {
        ObjectNode chunk = JSON.createObjectNode();
        chunk.put("type", "chunk");
        chunk.put("content", content);
        if (sendQuietly(session, chunk) && content != null && !content.isBlank()) {
            conversationService.recordFirstResponse(conversationId, timing);
        }
    }

    private ObjectNode done(String convId, List<VectorStore.Match> relevant,
                            List<Map<String, Object>> images, List<LinkCard> linkCards) {
        ObjectNode node = JSON.createObjectNode();
        node.put("type", "done");
        node.put("conversationId", convId);
        ArrayNode sources = node.putArray("sources");
        for (VectorStore.Match m : relevant) {
            ObjectNode s = sources.addObject();
            s.put("docName", m.docName());
            s.put("distance", m.distance());
        }
        ArrayNode imageArr = node.putArray("images");
        for (Map<String, Object> img : images) {
            ObjectNode s = imageArr.addObject();
            s.put("name", String.valueOf(img.get("name")));
            s.put("url", String.valueOf(img.get("url")));
        }
        ArrayNode cardArr = node.putArray("linkCards");
        for (LinkCard card : linkCards) {
            ObjectNode value = cardArr.addObject();
            value.put("title", card.title());
            value.put("description", card.description());
            value.put("url", card.url());
            value.put("thumbnailUrl", card.thumbnailUrl());
        }
        return node;
    }

    private ObjectNode error(String msg) {
        ObjectNode node = JSON.createObjectNode();
        node.put("type", "error");
        node.put("content", msg);
        return node;
    }

    private List<Map<String, Object>> customerImages(JsonNode request) {
        String url = request.path("imageUrl").asText("");
        if (!url.matches("^/uploads/images/c_[A-Za-z0-9._-]+$")) return List.of();
        return List.of(Map.of("name", "客户上传图片", "url", url, "type", "image"));
    }

    private boolean sendQuietly(WebSocketSession session, ObjectNode json) {
        try {
            if (session.isOpen()) {
                synchronized (session) {
                    session.sendMessage(new TextMessage(JSON.writeValueAsString(json)));
                    return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }
}
