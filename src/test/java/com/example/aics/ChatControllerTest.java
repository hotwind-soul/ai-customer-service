package com.example.aics;

import com.example.aics.ai.ModelGateway;
import com.example.aics.agent.AgentAccountService;
import com.example.aics.audit.AuditService;
import com.example.aics.chat.ChatEngine;
import com.example.aics.chat.ChatPrompt;
import com.example.aics.chat.ConversationService;
import com.example.aics.chat.ConversationTurnGuard;
import com.example.aics.config.AdminAuthInterceptor;
import com.example.aics.config.CustomerIdentityService;
import com.example.aics.config.WebAuthCookieService;
import com.example.aics.config.RateLimitInterceptor;
import com.example.aics.config.ConfigService;
import com.example.aics.image.ImageService;
import com.example.aics.knowledge.OcrService;
import com.example.aics.knowledge.VectorStore;
import com.example.aics.project.ProjectCatalogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * ChatController 接口层测试（第二批 #7，MockMvc）。
 * 验证 /api/chat/rag 应答的 HTTP 响应结构（type/answer/sources/images），
 * 以及 /api/chat 简化入口复用完整路由。不联网、不连库（全部 mock）。
 */
@WebMvcTest(ChatController.class)
class ChatControllerTest {

    @Test
    void harness模型异常时禁止内容断言不能通过() throws Exception {
        ChatEngine.Prepared p = new ChatEngine.Prepared("debug-conv", false, "test prompt", List.of(), List.of(),
                null, List.of(), List.of(), null, "AI", null);
        when(chatEngine.prepareDebug(anyString(), anyString(), anyString(), isNull(), isNull(), any(ChatEngine.DebugOptions.class)))
                .thenReturn(p);
        when(modelGateway.chat(any(ChatPrompt.class))).thenThrow(new IllegalStateException("test timeout"));
        mockMvc.perform(post("/api/chat/debug/run").contentType(MediaType.APPLICATION_JSON).content("""
                {"message":"恢复情况", "runModel":false, "expectedReplyType":"AI", "forbiddenAnswerPoints":"保证"}
                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.modelRequested").value(true))
                .andExpect(jsonPath("$.modelInvoked").value(true))
                .andExpect(jsonPath("$.modelError").isNotEmpty())
                .andExpect(jsonPath("$.assertion.passed").value(false))
                .andExpect(jsonPath("$.pipeline[8].status").value("failed"));
    }

    @Test
    void harness仅要求调用模型时服务端仍执行生成() throws Exception {
        ChatEngine.Prepared p = new ChatEngine.Prepared("debug-conv", false, "test prompt", List.of(), List.of(),
                null, List.of(), List.of(), null, "AI", null);
        when(chatEngine.prepareDebug(anyString(), anyString(), anyString(), isNull(), isNull(), any(ChatEngine.DebugOptions.class)))
                .thenReturn(p);
        when(modelGateway.chat(any(ChatPrompt.class))).thenReturn("恢复情况因人而异。");
        mockMvc.perform(post("/api/chat/debug/run").contentType(MediaType.APPLICATION_JSON).content("""
                {"message":"恢复情况", "runModel":false, "expectedReplyType":"AI", "expectedModelInvoked":true}
                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.modelRequested").value(true))
                .andExpect(jsonPath("$.modelInvoked").value(true))
                .andExpect(jsonPath("$.assertion.passed").value(true));
    }

    @Autowired private MockMvc mockMvc;

    @MockitoBean private ChatEngine chatEngine;
    @MockitoBean private ConversationService conversationService;
    @MockitoBean private ModelGateway modelGateway;
    // WebConfig 拦截器依赖（mock 放行，避免请求被 401 拦掉）
    @MockitoBean private AdminAuthInterceptor adminAuthInterceptor;
    @MockitoBean private RateLimitInterceptor rateLimitInterceptor;
    @MockitoBean private AgentAccountService agentAccountService;
    // W26-U1：ChatController 新增依赖（发图接口用），同样 mock 掉
    @MockitoBean private ImageService imageService;
    @MockitoBean private OcrService ocrService;
    @MockitoBean private AuditService auditService;
    @MockitoBean private CustomerIdentityService customerIdentityService;
    @MockitoBean private WebAuthCookieService webAuthCookieService;
    @MockitoBean private ConversationTurnGuard conversationTurnGuard;
    @MockitoBean private ConfigService configService;
    @MockitoBean private ProjectCatalogService projectCatalogService;

    @BeforeEach
    void setUp() throws Exception {
        when(adminAuthInterceptor.preHandle(any(), any(), any())).thenReturn(true);
        when(rateLimitInterceptor.preHandle(any(), any(), any())).thenReturn(true);
        when(customerIdentityService.resolve(any(), any())).thenReturn("u_web_test");
        when(chatEngine.guardGeneratedAnswer(anyString())).thenAnswer(invocation -> invocation.getArgument(0));
        when(chatEngine.finalizeAnswer(any(), anyString())).thenAnswer(invocation -> invocation.getArgument(1));
        when(conversationTurnGuard.acquire(any(), any())).thenReturn(mock(ConversationTurnGuard.Lease.class));
        when(conversationService.ensureConversation(any(), anyString())).thenAnswer(invocation -> {
            String id = invocation.getArgument(0);
            return id == null ? "conv-1" : id;
        });
        when(conversationService.getRoute(anyString())).thenReturn(java.util.Map.of("status", "active"));
    }

    @Test
    void rag接口_FAQ直回_返回标准答案和类型() throws Exception {
        ChatEngine.Prepared p = new ChatEngine.Prepared(
                "conv-1", false, null, List.of(), List.of(), "活动价298元（原价1980元）",
                List.of(), List.of(), null, "FAQ", null);
        when(chatEngine.prepare(anyString(), isNull(), anyString())).thenReturn(p);

        mockMvc.perform(post("/api/chat/rag").param("message", "抗衰单部位多少钱？"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("FAQ"))
                .andExpect(jsonPath("$.answer").value("活动价298元（原价1980元）"))
                .andExpect(jsonPath("$.conversationId").value("conv-1"));
    }

    @Test
    void rag接口_AI生成_返回sources数组() throws Exception {
        VectorStore.Match m = new VectorStore.Match(1L, "术后恢复.md", "不红不肿无恢复期", 0.35);
        ChatEngine.Prepared p = new ChatEngine.Prepared(
                "conv-1", false, "prompt", List.of(m), List.of(m), null,
                List.of(), List.of(m), null, "AI", null);
        when(chatEngine.prepare(anyString(), isNull(), anyString())).thenReturn(p);
        when(modelGateway.chat(any(ChatPrompt.class))).thenReturn("不红不肿、无恢复期，可以正常上班。");

        mockMvc.perform(post("/api/chat/rag").param("message", "做完能上班吗"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("AI"))
                .andExpect(jsonPath("$.answer").value("不红不肿、无恢复期，可以正常上班。"))
                .andExpect(jsonPath("$.sources[0].docName").value("术后恢复.md"))
                .andExpect(jsonPath("$.sources[0].distance").value(0.35));
    }

    @Test
    void 排队会话保存客户消息但不再进入AI() throws Exception {
        when(conversationService.ensureConversation("conv-queued", "u_web_test"))
                .thenReturn("conv-queued");
        when(conversationService.getRoute("conv-queued"))
                .thenReturn(java.util.Map.of("status", "need_human"));

        mockMvc.perform(post("/api/chat/rag")
                        .param("message", "我再补充一下")
                        .param("conversationId", "conv-queued"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("QUEUED"))
                .andExpect(jsonPath("$.conversationStatus").value("need_human"));

        verify(conversationService).saveMessage("conv-queued", "user", "我再补充一下", null);
        verify(chatEngine, never()).prepare(eq("我再补充一下"), eq("conv-queued"), anyString());
        verifyNoInteractions(modelGateway);
    }

    @Test
    void rag接口_拒答_返回REFUSAL() throws Exception {
        ChatEngine.Prepared p = new ChatEngine.Prepared(
                "conv-1", true, null, List.of(), List.of(), null,
                List.of(), List.of(), "已为您转接人工客服。", "REFUSAL", null);
        when(chatEngine.prepare(anyString(), isNull(), anyString())).thenReturn(p);

        mockMvc.perform(post("/api/chat/rag").param("message", "割双眼皮多少钱"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("REFUSAL"))
                .andExpect(jsonPath("$.answer").value("已为您转接人工客服。"));
    }

    @Test
    void rag接口_Agent处理_返回AGENT() throws Exception {
        ChatEngine.Prepared p = new ChatEngine.Prepared(
                "conv-1", false, null, List.of(), List.of(), null,
                List.of(), List.of(), "好的，我帮您登记预约。", "AGENT", null);
        when(chatEngine.prepare(anyString(), isNull(), anyString())).thenReturn(p);

        mockMvc.perform(post("/api/chat/rag").param("message", "我要预约"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("AGENT"))
                .andExpect(jsonPath("$.answer").value("好的，我帮您登记预约。"));
    }

    @Test
    void chat简化接口_复用完整FAQ路由() throws Exception {
        ChatEngine.Prepared p = new ChatEngine.Prepared(
                "conv-1", false, null, List.of(), List.of(), "您好，请问想了解哪个项目？",
                List.of(), List.of(), null, "FAQ", null);
        when(chatEngine.prepare(eq("你好"), isNull(), eq("u_web_test"))).thenReturn(p);

        mockMvc.perform(post("/api/chat").param("message", "你好"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("FAQ"))
                .andExpect(jsonPath("$.answer").value("您好，请问想了解哪个项目？"));
        verify(modelGateway, never()).chat(any(ChatPrompt.class));
    }

    @Test
    void ai实验台_可以使用草稿上下文调用真实模型() throws Exception {
        VectorStore.Match m = new VectorStore.Match(8L, "术后恢复.md", "不红不肿无恢复期", 0.35);
        ChatEngine.Prepared p = new ChatEngine.Prepared(
                "debug-conv", false, "debug prompt", List.of(m), List.of(m), null,
                List.of(), List.of(m), null, "AI", null)
                .withChatPrompt(new ChatPrompt("只根据资料回答", "参考资料", "做完能上班吗"));
        when(chatEngine.prepareDebug(eq("做完能上班吗"), eq("debug-conv"), eq("debug-user"),
                isNull(), isNull(), any(ChatEngine.DebugOptions.class))).thenReturn(p);
        when(modelGateway.chat(any(ChatPrompt.class))).thenReturn("可以正常上班。");

        mockMvc.perform(post("/api/chat/debug/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"message":"做完能上班吗","history":"用户：我想做抗衰", "profile":"55岁",
                                 "relevanceThreshold":0.5,"faqDirectThreshold":0.15,"runModel":true}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replyType").value("AI"))
                .andExpect(jsonPath("$.answer").value("可以正常上班。"))
                .andExpect(jsonPath("$.modelInvoked").value(true))
                .andExpect(jsonPath("$.hits[0].accepted").value(true))
                .andExpect(jsonPath("$.promptMeta.version").value("direct-v1"))
                .andExpect(jsonPath("$.promptMeta.fingerprint").isNotEmpty())
                .andExpect(jsonPath("$.promptMeta.modules[0].id").value("direct.system"))
                .andExpect(jsonPath("$.pipeline[8].status").value("executed"));
    }

    @Test
    void ai实验台_情绪安全路由显示前置命中且不调用模型() throws Exception {
        ChatEngine.Prepared p = new ChatEngine.Prepared(
                "debug-conv", false, null, List.of(), List.of(), null,
                List.of(), List.of(), "我理解您现在很生气，请告诉我具体问题。", "SAFETY", null);
        when(chatEngine.prepareDebug(eq("你这个垃圾客服"), eq("debug-conv"), eq("debug-user"),
                isNull(), isNull(), any(ChatEngine.DebugOptions.class))).thenReturn(p);

        mockMvc.perform(post("/api/chat/debug/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"message":"你这个垃圾客服","runModel":true}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replyType").value("SAFETY"))
                .andExpect(jsonPath("$.routeSummary").value("情绪安全响应"))
                .andExpect(jsonPath("$.pipeline[0].status").value("matched"))
                .andExpect(jsonPath("$.pipeline[0].key").value("emotionSafety"))
                .andExpect(jsonPath("$.modelInvoked").value(false));
        verify(modelGateway, never()).chat(any(ChatPrompt.class));
    }

    @Test
    void ai实验台_路由正确但回答要点缺失时回归断言失败() throws Exception {
        ChatEngine.Prepared p = new ChatEngine.Prepared(
                "debug-conv", false, null, List.of(), List.of(), "活动价298元。",
                List.of(), List.of(), null, "FACT", null);
        when(chatEngine.prepareDebug(eq("抗衰多少钱"), eq("debug-conv"), eq("debug-user"),
                isNull(), isNull(), any(ChatEngine.DebugOptions.class))).thenReturn(p);

        mockMvc.perform(post("/api/chat/debug/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"message":"抗衰多少钱","runModel":true,
                                 "expectedReplyType":"FACT","expectedAnswerPoints":"298元；8元"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replyType").value("FACT"))
                .andExpect(jsonPath("$.assertion.passed").value(false))
                .andExpect(jsonPath("$.assertion.failures[0]").value("回答要点：缺少：8元"));
    }

    @Test
    void messages接口_归属校验_越权返回404() throws Exception {
        when(conversationService.listMessagesOwned(anyString(), anyString())).thenReturn(null);

        mockMvc.perform(get("/api/messages")
                        .param("conversationId", "conv-x")
                        .param("userId", "user1"))
                .andExpect(status().isNotFound());
    }
}
