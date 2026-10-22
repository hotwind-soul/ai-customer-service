package com.example.aics.chat;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebSocket 会话注册中心（转人工的通道基础）：
 * - customers：conversationId → 客户的 WS 连接（坐席发消息要推给客户）
 * - agents：agentId → 坐席的全部 WS 连接（支持同账号多标签页/多终端）
 *
 * 单机内存实现；多实例部署时换 Redis Pub/Sub。
 */
@Component
public class SessionRegistry {

    private final Map<String, WebSocketSession> customers = new ConcurrentHashMap<>();
    private final Map<Long, Set<WebSocketSession>> agents = new ConcurrentHashMap<>();

    public void registerCustomer(String conversationId, WebSocketSession session) {
        customers.put(conversationId, session);
    }

    public void removeCustomer(String conversationId) {
        customers.remove(conversationId);
    }

    /**
     * 仅当注册表中该会话的连接仍是传入的 session 时才移除。
     * 修复 W25-B2 连带：客户多标签页/重连场景，旧连接断开时不能误删新连接的注册，
     * 否则坐席消息会推给已断开的旧连接（新连接反而收不到）。
     * @return true = 本次移除生效（说明没有更新的连接在服务）
     */
    public boolean removeCustomerIfCurrent(String conversationId, WebSocketSession session) {
        return customers.remove(conversationId, session);
    }

    public void registerAgent(Long agentId, WebSocketSession session) {
        agents.compute(agentId, (ignored, sessions) -> {
            Set<WebSocketSession> active = sessions == null ? ConcurrentHashMap.newKeySet() : sessions;
            active.add(session);
            return active;
        });
    }

    public void removeAgent(Long agentId) {
        agents.remove(agentId);
    }

    /**
     * 登出一个登录令牌时，只关闭由该令牌建立的连接；同一账号的其他浏览器/令牌不受影响。
     * 会先从注册表移除再关闭，避免 afterConnectionClosed 回调把仍在线的其他连接误判为离线。
     */
    public void closeAgentSessionsForToken(Long agentId, String token) {
        if (agentId == null || token == null || token.isBlank()) return;
        List<WebSocketSession> closing = new ArrayList<>();
        agents.computeIfPresent(agentId, (ignored, sessions) -> {
            for (WebSocketSession session : sessions) {
                if (token.equals(session.getAttributes().get("authToken"))) closing.add(session);
            }
            sessions.removeAll(closing);
            return sessions.isEmpty() ? null : sessions;
        });
        closing.forEach(this::closeQuietly);
    }

    /** 改密或账号强制失效时关闭该坐席的全部实时连接。 */
    public void closeAllAgentSessions(Long agentId) {
        if (agentId == null) return;
        Set<WebSocketSession> removed = agents.remove(agentId);
        if (removed != null) removed.forEach(this::closeQuietly);
    }

    /**
     * 只移除当前断开的 session；其他标签页仍在线时保留坐席在线状态。
     * @return true = 移除后该坐席已没有任何连接
     */
    public boolean removeAgentIfCurrent(Long agentId, WebSocketSession session) {
        AtomicBoolean becameOffline = new AtomicBoolean(false);
        agents.computeIfPresent(agentId, (ignored, sessions) -> {
            if (!sessions.remove(session)) return sessions;
            if (sessions.isEmpty()) {
                becameOffline.set(true);
                return null;
            }
            return sessions;
        });
        return becameOffline.get();
    }

    /** 坐席 WS 是否真的在线（轮询分配前校验，防止"幽灵坐席"——数据库 online 但 WS 未连接） */
    public boolean isAgentOnline(Long agentId) {
        Set<WebSocketSession> sessions = agents.get(agentId);
        return sessions != null && sessions.stream().anyMatch(WebSocketSession::isOpen);
    }

    /** 推消息给客户（坐席说话 → 客户聊天框） */
    public boolean sendToCustomer(String conversationId, String json) {
        return send(customers.get(conversationId), json);
    }

    /** 推消息给坐席（客户说话 → 坐席工作台） */
    public boolean sendToAgent(Long agentId, String json) {
        Set<WebSocketSession> sessions = agents.get(agentId);
        if (sessions == null || sessions.isEmpty()) return false;
        boolean delivered = false;
        for (WebSocketSession session : sessions) {
            delivered |= send(session, json);
        }
        return delivered;
    }

    private boolean send(WebSocketSession session, String json) {
        try {
            if (session != null && session.isOpen()) {
                synchronized (session) {
                    session.sendMessage(new TextMessage(json));
                }
                return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private void closeQuietly(WebSocketSession session) {
        try {
            if (session != null && session.isOpen()) session.close();
        } catch (Exception ignored) {
        }
    }
}
