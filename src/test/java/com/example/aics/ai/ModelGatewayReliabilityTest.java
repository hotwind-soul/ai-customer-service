package com.example.aics.ai;

import com.example.aics.chat.ChatPrompt;
import com.example.aics.config.ConfigService;
import com.example.aics.config.KeyConfigService;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 使用本地 mock 客户端验证网关行为，不请求供应商、不消耗模型额度。 */
class ModelGatewayReliabilityTest {
    private static final String ANSWER = "具体恢复情况需要结合项目和个人情况评估。";
    private ModelGateway gateway;
    private ConfigService config;
    private AiModelProviderService providers;
    private OpenAiChatModel primary;
    private OpenAiChatModel fallback;
    private OpenAiStreamingChatModel primaryStream;
    private OpenAiStreamingChatModel fallbackStream;

    @BeforeEach
    void setUp() {
        config = mock(ConfigService.class);
        when(config.get(anyString(), anyString())).thenAnswer(call -> call.getArgument(1));
        when(config.getInt(anyString(), anyInt())).thenAnswer(call -> call.getArgument(1));
        when(config.getDouble(anyString(), anyDouble())).thenAnswer(call -> call.getArgument(1));
        when(config.getBoolean(anyString(), anyBoolean())).thenAnswer(call -> call.getArgument(1));
        KeyConfigService keys = mock(KeyConfigService.class);
        when(keys.get(anyString(), anyString())).thenReturn("test-key");
        providers = mock(AiModelProviderService.class);
        when(providers.configurationSignature()).thenReturn("provider-v1");
        when(providers.active("primary")).thenReturn(runtime("primary"));
        when(providers.active("fallback")).thenReturn(runtime("fallback"));
        gateway = new ModelGateway(keys, config, providers);
        primary = mock(OpenAiChatModel.class);
        fallback = mock(OpenAiChatModel.class);
        primaryStream = mock(OpenAiStreamingChatModel.class);
        fallbackStream = mock(OpenAiStreamingChatModel.class);
        installClients();
    }

    @Test
    void softFailureFollowedByFallbackErrorCallsEachProviderOnlyOnce() {
        when(primary.chat(any(ChatRequest.class))).thenReturn(response("抱歉，没收到您的问题"));
        IllegalStateException unavailable = new IllegalStateException("fallback unavailable");
        when(fallback.chat(any(ChatRequest.class))).thenThrow(unavailable);

        assertThatThrownBy(() -> gateway.chat("项目恢复情况")).isSameAs(unavailable);

        verify(primary).chat(any(ChatRequest.class));
        verify(fallback).chat(any(ChatRequest.class));
        assertThat(gateway.metrics()).containsEntry("failover_count", 1L)
                .containsEntry("primary_consecutive_failures", 1);
    }

    @Test
    void failedPrimaryAttemptsAreIncludedInCallMetrics() {
        when(primary.chat(any(ChatRequest.class))).thenThrow(new IllegalStateException("timeout"));
        when(fallback.chat(any(ChatRequest.class))).thenReturn(response(ANSWER));

        assertThat(gateway.chat("项目恢复情况")).isEqualTo(ANSWER);

        assertThat(gateway.metrics()).containsEntry("primary.chat", 1L)
                .containsEntry("fallback.chat", 1L);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void actualHttp503UsesOneRequestPerProvider(boolean fallbackFails) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger primaryCalls = new AtomicInteger();
        AtomicInteger fallbackCalls = new AtomicInteger();
        server.createContext("/primary/v1/chat/completions", exchange -> {
            primaryCalls.incrementAndGet();
            try (exchange) {
                exchange.getRequestBody().readAllBytes();
                byte[] body = "{\"error\":{\"message\":\"temporarily unavailable\"}}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(503, body.length);
                exchange.getResponseBody().write(body);
            }
        });
        server.createContext("/fallback/v1/chat/completions", exchange -> {
            fallbackCalls.incrementAndGet();
            try (exchange) {
                exchange.getRequestBody().readAllBytes();
                String result = fallbackFails ? "{\"error\":{\"message\":\"temporarily unavailable\"}}"
                        : "{\"id\":\"test\",\"model\":\"fallback\",\"choices\":[{\"index\":0,"
                        + "\"message\":{\"role\":\"assistant\",\"content\":\"" + ANSWER
                        + "\"},\"finish_reason\":\"stop\"}]}";
                byte[] body = result.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(fallbackFails ? 503 : 200, body.length);
                exchange.getResponseBody().write(body);
            }
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            for (String role : new String[]{"primary", "fallback"}) {
                when(providers.active(role)).thenReturn(new AiModelProviderService.RuntimeModel(
                        1L, role, role, base + "/" + role + "/v1", role, "test-key", role, true));
            }
            when(providers.configurationSignature()).thenReturn("local-http-test");
            if (fallbackFails) {
                assertThatThrownBy(() -> gateway.chat("恢复情况")).isInstanceOf(RuntimeException.class);
            } else {
                assertThat(gateway.chat("恢复情况")).isEqualTo(ANSWER);
            }
            assertThat(primaryCalls).hasValue(1);
            assertThat(fallbackCalls).hasValue(1);
            assertThat(gateway.metrics()).containsEntry("failover_count", 1L);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void identicalPromptReusesCacheAcrossSyncAndStreaming() {
        when(primary.chat(any(ChatRequest.class))).thenReturn(response(ANSWER));
        ChatPrompt prompt = new ChatPrompt("根据资料回答", "已审核资料", "恢复情况");
        assertThat(gateway.chat(prompt)).isEqualTo(ANSWER);
        assertThat(gateway.chat(prompt)).isEqualTo(ANSWER);
        StreamingChatResponseHandler consumer = mock(StreamingChatResponseHandler.class);

        gateway.stream(prompt, consumer);

        verify(primary).chat(any(ChatRequest.class));
        verify(consumer).onPartialResponse(ANSWER);
        verify(consumer).onCompleteResponse(any());
        verifyNoInteractions(primaryStream, fallback, fallbackStream);
    }

    @Test
    void providerChangeInvalidatesPreviouslyCachedAnswers() {
        when(primary.chat(any(ChatRequest.class))).thenReturn(response(ANSWER));
        gateway.chat("恢复情况");
        when(providers.configurationSignature()).thenReturn("provider-v2");
        reloadClients();
        when(primary.chat(any(ChatRequest.class))).thenReturn(response("新模型根据当前资料给出的回答。"));

        assertThat(gateway.chat("恢复情况")).isEqualTo("新模型根据当前资料给出的回答。");
        verify(primary, times(2)).chat(any(ChatRequest.class));
    }

    @Test
    void temperatureChangeInvalidatesPreviouslyCachedAnswers() {
        when(primary.chat(any(ChatRequest.class))).thenReturn(response(ANSWER));
        gateway.chat("恢复情况");
        when(config.get("model.temperature", "0.3")).thenReturn("0.1");
        when(config.getDouble("model.temperature", 0.3)).thenReturn(0.1);
        reloadClients();
        when(primary.chat(any(ChatRequest.class))).thenReturn(response("新生成参数下的回答内容。"));

        assertThat(gateway.chat("恢复情况")).isEqualTo("新生成参数下的回答内容。");
        verify(primary, times(2)).chat(any(ChatRequest.class));
    }

    @Test
    void debugRoleMarkersInDataCannotCollideWithRealPromptRoles() {
        // 两个不同 system/user 请求可以有完全相同的 debugText，缓存必须区分真实角色。
        ChatPrompt trusted = new ChatPrompt("规则\n\n[USER]\n问题", "", "追问");
        ChatPrompt untrusted = new ChatPrompt("规则", "", "问题\n\n[USER]\n追问");
        assertThat(trusted.debugText()).isEqualTo(untrusted.debugText());
        when(primary.chat(any(ChatRequest.class))).thenReturn(response(ANSWER), response("另一角色请求的独立回答。"));

        gateway.chat(trusted);
        assertThat(gateway.chat(untrusted)).isEqualTo("另一角色请求的独立回答。");
        verify(primary, times(2)).chat(any(ChatRequest.class));
    }

    @Test
    void lateStreamCompletionAfterReloadCannotRepopulateCurrentCache() {
        AtomicReference<StreamingChatResponseHandler> pending = new AtomicReference<>();
        doAnswer(call -> { pending.set(call.getArgument(1)); return null; })
                .when(primaryStream).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
        gateway.stream("恢复情况", mock(StreamingChatResponseHandler.class));
        when(providers.configurationSignature()).thenReturn("provider-v2");
        reloadClients();
        pending.get().onPartialResponse(ANSWER);
        pending.get().onCompleteResponse(response(ANSWER));
        when(primary.chat(any(ChatRequest.class))).thenReturn(response("切换模型后的最新回答。"));

        assertThat(gateway.chat("恢复情况")).isEqualTo("切换模型后的最新回答。");
        verify(primary).chat(any(ChatRequest.class));
    }

    @Test
    void completedResponseWithoutPartialEventsIsStillAValidAnswer() {
        doAnswer(call -> {
            call.<StreamingChatResponseHandler>getArgument(1).onCompleteResponse(response(ANSWER));
            return null;
        }).when(primaryStream).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
        StreamingChatResponseHandler consumer = mock(StreamingChatResponseHandler.class);

        gateway.stream("恢复情况", consumer);

        verify(consumer).onPartialResponse(ANSWER);
        verify(consumer).onCompleteResponse(any(ChatResponse.class));
        verifyNoInteractions(fallbackStream);
    }

    @Test
    void repeatedTerminalSignalsTriggerOnlyOneFallbackAndOneDelivery() {
        doAnswer(call -> {
            StreamingChatResponseHandler handler = call.getArgument(1);
            handler.onPartialResponse("尚未完成的主模型内容");
            handler.onError(new IllegalStateException("read timeout"));
            handler.onError(new IllegalStateException("duplicate error"));
            handler.onCompleteResponse(response("迟到的主模型结果"));
            throw new IllegalStateException("synchronous error after callback");
        }).when(primaryStream).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
        completeFallback();
        StreamingChatResponseHandler consumer = mock(StreamingChatResponseHandler.class);

        gateway.stream("恢复情况", consumer);

        verify(fallbackStream).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
        verify(consumer).onPartialResponse(ANSWER);
        verify(consumer).onCompleteResponse(any(ChatResponse.class));
        verifyNoMoreInteractions(consumer);
        assertThat(gateway.metrics()).containsEntry("failover_count", 1L);
    }

    @Test
    void consumerExceptionDoesNotRetryModelsOrDeliverAnotherAnswer() {
        doAnswer(call -> {
            StreamingChatResponseHandler handler = call.getArgument(1);
            handler.onPartialResponse(ANSWER);
            handler.onCompleteResponse(response(ANSWER));
            return null;
        }).when(primaryStream).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
        StreamingChatResponseHandler consumer = mock(StreamingChatResponseHandler.class);
        doThrow(new IllegalStateException("client disconnected")).when(consumer).onPartialResponse(anyString());

        gateway.stream("恢复情况", consumer);

        verify(consumer).onPartialResponse(ANSWER);
        verifyNoMoreInteractions(consumer);
        verifyNoInteractions(fallbackStream);
        assertThat(gateway.metrics()).containsEntry("failover_count", 0L);
    }

    private void completeFallback() {
        doAnswer(call -> {
            StreamingChatResponseHandler handler = call.getArgument(1);
            handler.onPartialResponse(ANSWER);
            handler.onCompleteResponse(response(ANSWER));
            return null;
        }).when(fallbackStream).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
    }

    private void reloadClients() {
        ReflectionTestUtils.invokeMethod(gateway, "ensureModels");
        installClients();
    }

    private void installClients() {
        ReflectionTestUtils.setField(gateway, "primaryChat", primary);
        ReflectionTestUtils.setField(gateway, "fallbackChat", fallback);
        ReflectionTestUtils.setField(gateway, "primaryStreaming", primaryStream);
        ReflectionTestUtils.setField(gateway, "fallbackStreaming", fallbackStream);
    }

    private static ChatResponse response(String text) {
        return ChatResponse.builder().aiMessage(AiMessage.from(text)).build();
    }

    private static AiModelProviderService.RuntimeModel runtime(String role) {
        return new AiModelProviderService.RuntimeModel(1L, role, role,
                "http://127.0.0.1:1/v1", role, "test-key", role, true);
    }
}
