package com.example.aics.report;

import com.example.aics.chat.ConversationService;
import com.example.aics.chat.ResponseTiming;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Map;
import java.util.Set;

/** REST 在响应成功写出后采样，不把控制器返回之前的生成耗时当作首响。 */
@Configuration
public class ChatResponseTimingConfig implements WebMvcConfigurer, HandlerInterceptor {
    private static final String TIMING = ChatResponseTimingConfig.class.getName() + ".timing";
    private static final String CONVERSATION = ChatResponseTimingConfig.class.getName() + ".conversation";
    private static final Set<String> ANSWER_TYPES = Set.of(
            "SAFETY", "INTRO", "FACT", "FAQ", "AGENT", "CLARIFY", "REFUSAL", "AI");
    private final ConversationService conversations;

    public ChatResponseTimingConfig(ConversationService conversations) {
        this.conversations = conversations;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns("/api/chat", "/api/chat/rag");
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        request.setAttribute(TIMING, ResponseTiming.start());
        return true;
    }

    public static Map<String, Object> answered(HttpServletRequest request, Map<String, Object> result) {
        if (ANSWER_TYPES.contains(String.valueOf(result.get("type")))
                && result.get("answer") instanceof String answer && !answer.isBlank()) {
            request.setAttribute(CONVERSATION, result.get("conversationId"));
        }
        return result;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        if (ex == null && response.getStatus() >= 200 && response.getStatus() < 300
                && request.getAttribute(CONVERSATION) instanceof String conversationId
                && request.getAttribute(TIMING) instanceof ResponseTiming timing) {
            conversations.recordFirstResponse(conversationId, timing);
        }
    }
}
