package com.example.aics.chat;

import com.example.aics.ai.ModelGateway;
import com.example.aics.config.CustomerIdentityHandshakeInterceptor;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ChatResponseTimingTest {
    @Test
    void closedOrFailedWebSocketSendDoesNotProduceSuccessfulResponseSample() throws Exception {
        verifyDelivery(false, false, false);
        verifyDelivery(true, true, false);
    }

    @Test
    void firstAcceptedAnswerIsRecordedOnceWithoutCountingWelcomeOrDone() throws Exception {
        verifyDelivery(true, false, true);
    }

    private void verifyDelivery(boolean open, boolean sendFails, boolean expectSample) throws Exception {
        ChatEngine engine = mock(ChatEngine.class);
        ConversationService conversations = mock(ConversationService.class);
        ConversationTurnGuard guard = mock(ConversationTurnGuard.class);
        when(guard.acquire(any(), any())).thenReturn(mock(ConversationTurnGuard.Lease.class));
        when(conversations.ensureConversation("c", "u")).thenReturn("c");
        when(conversations.getRoute("c")).thenReturn(Map.of("status", "active"));
        ChatEngine.Prepared prepared = new ChatEngine.Prepared("c", false, null, List.of(), List.of(),
                "业务回复", List.of(), List.of(), null, "FAQ", "欢迎咨询");
        when(engine.prepare(anyString(), eq("c"), eq("u"), isNull(), isNull(), eq(true))).thenReturn(prepared);
        when(engine.finalizeAnswer(prepared, "业务回复")).thenReturn("业务回复");
        WebSocketSession session = mock(WebSocketSession.class);
        Map<String, Object> attributes = new HashMap<>();
        attributes.put(CustomerIdentityHandshakeInterceptor.WS_ATTRIBUTE, "u");
        when(session.getAttributes()).thenReturn(attributes);
        when(session.isOpen()).thenReturn(open);
        if (sendFails) doThrow(new java.io.IOException("closed")).when(session).sendMessage(any());
        ChatWebSocketHandler handler = new ChatWebSocketHandler(engine, conversations,
                mock(SessionRegistry.class), mock(ModelGateway.class), mock(StringRedisTemplate.class), guard);
        handler.handleTextMessage(session, new TextMessage("{\"message\":\"咨询项目\",\"conversationId\":\"c\"}"));
        verify(conversations, times(expectSample ? 1 : 0)).recordFirstResponse(eq("c"), any(ResponseTiming.class));
    }
}
