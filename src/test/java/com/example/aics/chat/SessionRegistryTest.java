package com.example.aics.chat;

import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SessionRegistryTest {

    @Test
    void multipleAgentSessionsStayOnlineUntilLastConnectionCloses() {
        SessionRegistry registry = new SessionRegistry();
        WebSocketSession first = mock(WebSocketSession.class);
        WebSocketSession second = mock(WebSocketSession.class);
        when(first.isOpen()).thenReturn(true);
        when(second.isOpen()).thenReturn(true);

        registry.registerAgent(7L, first);
        registry.registerAgent(7L, second);

        assertThat(registry.removeAgentIfCurrent(7L, first)).isFalse();
        assertThat(registry.isAgentOnline(7L)).isTrue();
        assertThat(registry.removeAgentIfCurrent(7L, second)).isTrue();
        assertThat(registry.isAgentOnline(7L)).isFalse();
    }

    @Test
    void customerMessageIsDeliveredToEveryOpenAgentSession() throws Exception {
        SessionRegistry registry = new SessionRegistry();
        WebSocketSession first = mock(WebSocketSession.class);
        WebSocketSession second = mock(WebSocketSession.class);
        when(first.isOpen()).thenReturn(true);
        when(second.isOpen()).thenReturn(true);
        registry.registerAgent(7L, first);
        registry.registerAgent(7L, second);

        assertThat(registry.sendToAgent(7L, "{\"type\":\"customer\"}")).isTrue();

        verify(first).sendMessage(any(TextMessage.class));
        verify(second).sendMessage(any(TextMessage.class));
    }

    @Test
    void logoutClosesOnlySessionsCreatedByThatToken() throws Exception {
        SessionRegistry registry = new SessionRegistry();
        WebSocketSession first = mock(WebSocketSession.class);
        WebSocketSession second = mock(WebSocketSession.class);
        when(first.isOpen()).thenReturn(true);
        when(second.isOpen()).thenReturn(true);
        when(first.getAttributes()).thenReturn(new java.util.HashMap<>(Map.of("authToken", "token-1")));
        when(second.getAttributes()).thenReturn(new java.util.HashMap<>(Map.of("authToken", "token-2")));
        registry.registerAgent(7L, first);
        registry.registerAgent(7L, second);

        registry.closeAgentSessionsForToken(7L, "token-1");

        verify(first).close();
        verify(second, never()).close();
        assertThat(registry.isAgentOnline(7L)).isTrue();
    }

    @Test
    void passwordResetClosesEveryAgentSession() throws Exception {
        SessionRegistry registry = new SessionRegistry();
        WebSocketSession first = mock(WebSocketSession.class);
        WebSocketSession second = mock(WebSocketSession.class);
        when(first.isOpen()).thenReturn(true);
        when(second.isOpen()).thenReturn(true);
        registry.registerAgent(7L, first);
        registry.registerAgent(7L, second);

        registry.closeAllAgentSessions(7L);

        verify(first).close();
        verify(second).close();
        assertThat(registry.isAgentOnline(7L)).isFalse();
    }
}
