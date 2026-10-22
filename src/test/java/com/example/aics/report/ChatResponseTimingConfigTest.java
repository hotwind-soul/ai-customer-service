package com.example.aics.report;

import com.example.aics.chat.ConversationService;
import com.example.aics.chat.ResponseTiming;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ChatResponseTimingConfigTest {
    @Test
    void onlySuccessfulBusinessResponseIsRecordedAfterHttpDelivery() {
        ConversationService conversations = mock(ConversationService.class);
        ChatResponseTimingConfig interceptor = new ChatResponseTimingConfig(conversations);
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        interceptor.preHandle(request, response, this);
        ChatResponseTimingConfig.answered(request, Map.of("type", "FAQ", "answer", "业务回复", "conversationId", "c"));
        verifyNoInteractions(conversations);
        interceptor.afterCompletion(request, response, this, null);
        verify(conversations).recordFirstResponse(eq("c"), any(ResponseTiming.class));
    }

    @Test
    void queueAcknowledgementsErrorsAndFailedResponsesAreNotSamples() {
        ConversationService conversations = mock(ConversationService.class);
        ChatResponseTimingConfig interceptor = new ChatResponseTimingConfig(conversations);
        for (String type : new String[]{"QUEUED", "HUMAN", "ERROR"}) {
            MockHttpServletRequest request = new MockHttpServletRequest();
            MockHttpServletResponse response = new MockHttpServletResponse();
            interceptor.preHandle(request, response, this);
            ChatResponseTimingConfig.answered(request, Map.of("type", type, "answer", "状态提示", "conversationId", "c"));
            interceptor.afterCompletion(request, response, this, null);
        }
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        interceptor.preHandle(request, response, this);
        ChatResponseTimingConfig.answered(request, Map.of("type", "AI", "answer", "答复", "conversationId", "c"));
        response.setStatus(500);
        interceptor.afterCompletion(request, response, this, null);
        response.setStatus(200);
        interceptor.afterCompletion(request, response, this, new java.io.IOException("connection closed"));
        verifyNoInteractions(conversations);
    }
}
