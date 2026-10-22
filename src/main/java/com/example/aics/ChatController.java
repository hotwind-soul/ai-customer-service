package com.example.aics;

import com.example.aics.ai.ModelGateway;
import com.example.aics.agent.AgentAccountService;
import com.example.aics.audit.AuditService;
import com.example.aics.chat.ChatEngine;
import com.example.aics.chat.ConversationService;
import com.example.aics.chat.ConversationTurnGuard;
import com.example.aics.chat.HarnessAssertions;
import com.example.aics.chat.HarnessCaseRunner;
import com.example.aics.config.AiConfigCatalog;
import com.example.aics.config.CustomerIdentityService;
import com.example.aics.config.ConfigService;
import com.example.aics.image.ImageService;
import com.example.aics.knowledge.OcrService;
import com.example.aics.config.WebAuthCookieService;
import com.example.aics.project.ProjectCatalogService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 聊天接口
 * /api/chat            完整问答流水线的简化入口（匿名新会话）
 * /api/chat/rag        完整问答流水线 + 多轮对话 + 会话落库（非流式）
 * /api/chat-image      W26-U1：客户发送图片（存文件 + 视觉模型转文字描述）
 * /api/messages        某会话的全部消息（刷新恢复用）
 * 流式版本走 WebSocket /ws/chat（见 ChatWebSocketHandler），与这里共用 ChatEngine 流水线
 */
@RestController
public class ChatController {

    private static final int MAX_MESSAGE_CHARS = 4000;

    private final ModelGateway modelGateway;
    private final ChatEngine chatEngine;
    private final ConversationService conversationService;
    private final ImageService imageService;
    private final OcrService ocrService;
    private final AuditService auditService;
    private final CustomerIdentityService customerIdentities;
    private final ConversationTurnGuard turnGuard;
    private final ConfigService configService;
    private final AgentAccountService agentAccountService;
    private final WebAuthCookieService authCookies;
    private final ProjectCatalogService projectCatalog;

    public ChatController(ModelGateway modelGateway, ChatEngine chatEngine,
                          ConversationService conversationService,
                          ImageService imageService, OcrService ocrService,
                           AuditService auditService,
                           CustomerIdentityService customerIdentities,
                           ConversationTurnGuard turnGuard,
                           ConfigService configService,
                           AgentAccountService agentAccountService,
                           WebAuthCookieService authCookies,
                           ProjectCatalogService projectCatalog) {
        this.modelGateway = modelGateway;
        this.chatEngine = chatEngine;
        this.conversationService = conversationService;
        this.imageService = imageService;
        this.ocrService = ocrService;
        this.auditService = auditService;
        this.customerIdentities = customerIdentities;
        this.turnGuard = turnGuard;
        this.configService = configService;
        this.agentAccountService = agentAccountService;
        this.authCookies = authCookies;
        this.projectCatalog = projectCatalog;
    }

    @PostMapping("/api/chat")
    public Map<String, Object> chat(@RequestParam String message,
                                    HttpServletRequest request,
                                    HttpServletResponse response) {
        // 与 WebSocket、企微、RAG REST 共用同一套路由，避免此入口绕过知识库和医疗红线。
        return com.example.aics.report.ChatResponseTimingConfig.answered(request,
                executeChat(message, null, customerIdentities.resolve(request, response)));
    }

    /** 初始化网页客户的服务端身份 Cookie；不向 JavaScript 暴露实际身份值。 */
    @GetMapping("/api/customer/session")
    public Map<String, Object> customerSession(HttpServletRequest request,
                                                HttpServletResponse response) {
        customerIdentities.resolve(request, response);
        return Map.of("ready", true);
    }

    /**
     * 客户首页展示配置（公开接口，供 index.html 渲染「项目」与「快捷问题」）。
     * 后台在「AI 配置中心 → 首页展示」维护，修改后经 ConfigService 缓存立即生效。
     */
    @GetMapping("/api/home-config")
    public Map<String, Object> homeConfig() {
        List<String> projects = projectCatalog.publishedNames();
        if (projects.isEmpty()) {
            String rawProjects = configService.get("home.projectList", "抗衰\n眼袋\n淡斑\n胡子");
            projects = Arrays.stream(rawProjects.split("\\R"))
                    .map(String::trim).filter(s -> !s.isBlank()).collect(Collectors.toList());
        }
        Map<String, String> qMap = AiConfigCatalog.stringMap(configService, "home.quickQuestions");
        List<Map<String, String>> quickQuestions = new ArrayList<>();
        qMap.forEach((k, v) -> quickQuestions.add(Map.of("key", k, "label", v)));
        String brandTitle = configService.get("home.brandTitle", "昇媛智能客服");
        String rawSubtitle = configService.get("home.brandSubtitle", "为您解答 {项目} 等问题");
        String brandSubtitle = rawSubtitle.replace("{项目}", String.join(" / ", projects));
        String serviceTime = configService.get("home.serviceTime", "AI 全天在线 · 人工客服 09:00-22:00 · 门店营业 10:00-18:00");
        String projectPanelTitle = configService.get("home.projectPanelTitle", "选择想咨询的项目");
        return Map.of("projects", projects, "quickQuestions", quickQuestions,
                "brandTitle", brandTitle, "brandSubtitle", brandSubtitle,
                "serviceTime", serviceTime, "projectPanelTitle", projectPanelTitle);
    }

    /**
     * AI 调试接口（受管理鉴权保护，WebConfig 已挂 /api/chat/debug）：
     * 返回完整应答路由的中间状态，供「AI 调试台」页面调参/排查用。
     * 只调 ChatEngine.prepare（不调大模型），快速且免费；
     * hits 返回检索命中明细（docName/distance/内容截断），前端据此渲染"为什么走/不走某层"。
     */
    @GetMapping("/api/chat/debug")
    public Map<String, Object> debug(@RequestParam String message,
                                     @RequestParam(required = false) Double lat,
                                     @RequestParam(required = false) Double lng) {
        long started = System.nanoTime();
        ChatEngine.Prepared p = chatEngine.prepareDebug(
                message, "debug-conv", "debug-user", lat, lng);
        return buildDebugResponse(p, false, false, null, started, null, null);
    }

    /** 完整 AI 实验台：支持临时历史/画像/参数草稿，可选实际调用生成模型。 */
    @PostMapping("/api/chat/debug/run")
    public Map<String, Object> debugRun(@RequestBody AiDebugRequest req) {
        long started = System.nanoTime();
        if (req.message() == null || req.message().isBlank()) {
            return Map.of("error", "请输入待调试的客户消息");
        }
        ChatEngine.DebugOptions options = new ChatEngine.DebugOptions(
                req.history(), req.profile(), req.relevanceThreshold(), req.faqDirectThreshold(),
                req.tone(), req.refusalAnswer(), req.systemPrompt(), req.ragRules(),
                req.moduleCharBudgets(), req.classifierPrompt(),
                req.projectIntroAiEnabled(),
                req.projectIntroRequireBothImages(), req.projectIntroDecisionPrompt());
        ChatEngine.Prepared p = chatEngine.prepareDebug(
                req.message(), "debug-conv", "debug-user", req.lat(), req.lng(), options);

        HarnessAssertions.Spec assertionSpec = new HarnessAssertions.Spec(
                req.expectedReplyType(), req.expectedAnswerPoints(), req.forbiddenAnswerPoints(),
                req.expectedAuthorityDomain(), req.expectedAuthoritySource(), req.expectedModelInvoked());
        HarnessCaseRunner.Outcome outcome = HarnessCaseRunner.run(p, assertionSpec, req.runModel(), chatEngine, modelGateway);
        Map<String, Object> result = buildDebugResponse(p, outcome.modelRequested(), outcome.modelInvoked(),
                outcome.error(), started, req.relevanceThreshold(), req.faqDirectThreshold(), outcome.answer());
        result.put("assertion", outcome.assertion().toMap());
        return result;
    }

    public record AiDebugRequest(
            String message,
            String history,
            String profile,
            Double lat,
            Double lng,
            Double relevanceThreshold,
            Double faqDirectThreshold,
            String tone,
            String refusalAnswer,
            String systemPrompt,
            String ragRules,
            String moduleCharBudgets,
            String classifierPrompt,
            Boolean projectIntroAiEnabled,
            Boolean projectIntroRequireBothImages,
            String projectIntroDecisionPrompt,
            boolean runModel,
            String expectedReplyType,
            String expectedAnswerPoints,
            String forbiddenAnswerPoints,
            String expectedAuthorityDomain,
            String expectedAuthoritySource,
            Boolean expectedModelInvoked
    ) {}

    private Map<String, Object> buildDebugResponse(ChatEngine.Prepared p,
                                                    boolean modelRequested,
                                                    boolean modelInvoked,
                                                    String modelError,
                                                    long started,
                                                    Double relevanceThreshold,
                                                    Double faqDirectThreshold) {
        return buildDebugResponse(p, modelRequested, modelInvoked, modelError, started,
                relevanceThreshold, faqDirectThreshold, p.agentAnswer() != null
                        ? p.agentAnswer() : p.directAnswer());
    }

    private Map<String, Object> buildDebugResponse(ChatEngine.Prepared p,
                                                    boolean modelRequested,
                                                    boolean modelInvoked,
                                                    String modelError,
                                                    long started,
                                                    Double relevanceThreshold,
                                                    Double faqDirectThreshold,
                                                    String answer) {
        java.util.Set<Long> acceptedIds = p.relevant().stream()
                .map(com.example.aics.knowledge.VectorStore.Match::id)
                .collect(java.util.stream.Collectors.toSet());
        List<Map<String, Object>> hits = new java.util.ArrayList<>();
        for (int i = 0; i < p.candidates().size(); i++) {
            var m = p.candidates().get(i);
            Map<String, Object> hit = new java.util.LinkedHashMap<>();
            hit.put("rank", i + 1);
            hit.put("id", m.id());
            hit.put("docName", m.docName());
            hit.put("content", m.content().length() > 500 ? m.content().substring(0, 500) + "…" : m.content());
            hit.put("distance", m.distance());
            hit.put("project", m.project());
            hit.put("category", m.category());
            hit.put("knowledgeType", m.knowledgeType());
            hit.put("riskLevel", m.riskLevel());
            hit.put("safeDirect", m.safeDirect());
            hit.put("retrievalMethod", m.retrievalMethod());
            hit.put("retrievalScore", m.retrievalScore());
            hit.put("accepted", acceptedIds.contains(m.id()));
            hit.put("faqDirect", "FAQ".equals(p.replyType()) && i == 0);
            hits.add(hit);
        }

        Map<String, Object> r = new java.util.LinkedHashMap<>();
        r.put("conversationId", p.conversationId());
        r.put("replyType", p.replyType());
        r.put("routeSummary", routeSummary(p.replyType()));
        r.put("understanding", p.understanding().toMap());
        r.put("searchQuery", p.understanding().retrievalQuery());
        r.put("refused", p.refused());
        r.put("directAnswer", p.directAnswer());
        r.put("agentAnswer", p.agentAnswer());
        r.put("answer", answer);
        r.put("prompt", p.prompt());
        r.put("promptLayers", p.chatPrompt() == null ? null : p.chatPrompt().layers());
        r.put("promptMeta", p.chatPrompt() == null ? null : p.chatPrompt().metadata());
        r.put("promptChars", p.prompt() == null ? 0 : p.prompt().length());
        r.put("estimatedPromptTokens", p.prompt() == null ? 0 : Math.max(1, p.prompt().length() / 2));
        r.put("hits", hits);
        r.put("acceptedCount", p.relevant().size());
        r.put("sources", p.displaySources().stream()
                .map(m -> Map.<String, Object>of("docName", m.docName(), "distance", m.distance()))
                .toList());
        r.put("images", p.images());
        r.put("linkCards", p.linkCards());
        r.put("introducedProject", p.introducedProject());
        r.put("projectIntroDecision", p.projectIntroDecision());
        r.put("turnTrace", p.trace() == null ? null : p.trace().toMap());
        r.put("pipeline", buildDebugPipeline(p, modelRequested, modelInvoked && modelError == null));
        r.put("modelRequested", modelRequested);
        r.put("modelInvoked", modelInvoked);
        r.put("modelError", modelError);
        r.put("durationMs", Math.round((System.nanoTime() - started) / 100_000.0) / 10.0);
        r.put("relevanceThreshold", relevanceThreshold);
        r.put("faqDirectThreshold", faqDirectThreshold);
        return r;
    }

    private List<Map<String, Object>> buildDebugPipeline(ChatEngine.Prepared p,
                                                          boolean modelRequested,
                                                          boolean modelInvoked) {
        String type = p.replyType();
        boolean safety = "SAFETY".equals(type);
        boolean beforeIntroEarly = safety || "AGENT".equals(type) || "FACT".equals(type);
        boolean early = beforeIntroEarly || "INTRO".equals(type) || "CLARIFY".equals(type);
        List<Map<String, Object>> stages = new java.util.ArrayList<>();
        stages.add(stage("emotionSafety", "情绪安全", safety ? "matched" : "passed",
                safety ? "已命中辱骂或暴力威胁规则，未调用模型与知识检索" : "未命中辱骂或威胁规则"));
        stages.add(stage("business", "业务意图", "AGENT".equals(type) ? "matched" : safety ? "skipped" : "passed",
                "AGENT".equals(type) ? "预约、门店或转人工已由业务工具处理"
                        : safety ? "情绪安全路由已结束" : "未命中业务动作"));
        stages.add(stage("fact", "结构化事实", "FACT".equals(type) ? "matched" : safety || "AGENT".equals(type) ? "skipped" : "passed",
                "FACT".equals(type) ? "价格、营业时间或动态政策由唯一事实源直接回答"
                        : safety ? "情绪安全路由已结束"
                        : "AGENT".equals(type) ? "业务动作已结束" : "未命中结构化事实"));
        String introStatus = "INTRO".equals(type) ? "matched"
                : p.projectIntroDecision() != null ? "executed"
                : beforeIntroEarly ? "skipped" : "passed";
        String introDetail = "INTRO".equals(type)
                ? "AI 判断适合首次介绍；已采用 price.xlsx 标准话术，并选择效果图和有效优惠海报"
                : "CLARIFY".equals(p.projectIntroDecision())
                ? "AI 判断当前不适合直接介绍活动，改为自然追问客户关注点"
                : beforeIntroEarly ? "前置专用路由已结束" : "本轮无需首次项目介绍或该项目已经介绍过";
        stages.add(stage("projectIntro", "首次项目介绍判断", introStatus, introDetail));
        stages.add(stage("clarify", "问法完整性", "CLARIFY".equals(type) ? "matched" : early ? "skipped" : "passed",
                "CLARIFY".equals(type) ? p.understanding().reason()
                        : early ? "上一阶段已结束" : understandingDetail(p.understanding())));
        String retrievalStatus = early ? "skipped"
                : p.candidates().isEmpty() ? "empty"
                : p.relevant().isEmpty() ? "rejected" : "passed";
        stages.add(stage("retrieval", "知识检索", retrievalStatus,
                early ? "前置路由已结束，未调 embedding"
                        : "候选 " + p.candidates().size() + " 条，采用 " + p.relevant().size() + " 条"));
        stages.add(stage("faq", "FAQ 直回", "FAQ".equals(type) ? "matched" : early ? "skipped" : "passed",
                "FAQ".equals(type) ? "最佳候选达到 FAQ 阈值，零 token 直回" : early ? "未进入检索" : "未达到 FAQ 直回条件"));
        stages.add(stage("safety", "知识安全", "REFUSAL".equals(type) ? "matched" : early || "FAQ".equals(type) ? "skipped" : "passed",
                "REFUSAL".equals(type) ? "无可靠资料，安全拒答" : "无需触发知识不足拒答"));
        String llmStatus = !"AI".equals(type) ? "skipped" : modelInvoked ? "executed" : modelRequested ? "failed" : "ready";
        stages.add(stage("generation", "RAG + LLM", llmStatus,
                !"AI".equals(type) ? "前置阶段已给出结果" : modelInvoked ? "已调用生成模型" : modelRequested ? "模型未成功返回" : "Prompt 已就绪，本次未调模型"));
        return stages;
    }

    private String understandingDetail(com.example.aics.chat.MessageUnderstanding.Result understanding) {
        if ("未分析".equals(understanding.reason())) return "问法可进入检索";
        String project = understanding.project() == null ? "未指定项目" : "项目=" + understanding.project();
        String aspect = understanding.aspect() == null ? "未识别维度" : "维度=" + understanding.aspect();
        String inherited = understanding.inheritedProject() ? "，已继承上轮项目" : "";
        return project + "，" + aspect + inherited;
    }

    private Map<String, Object> stage(String key, String name, String status, String detail) {
        return Map.of("key", key, "name", name, "status", status, "detail", detail);
    }

    private String routeSummary(String type) {
        return switch (type) {
            case "AGENT" -> "业务工具直达";
            case "INTRO" -> "AI 首次项目介绍";
            case "FACT" -> "结构化事实直回";
            case "CLARIFY" -> "信息不足，追问澄清";
            case "FAQ" -> "高置信 FAQ 直回";
            case "SAFETY" -> "情绪安全响应";
            case "REFUSAL" -> "知识不足，安全拒答";
            case "AI" -> "检索增强生成";
            default -> type;
        };
    }

    /**
     * RAG + 多轮对话主接口（非流式，评测脚本和简单接入用）
     * @param message        用户本轮问题
     * @param conversationId 会话 ID；首次对话不传，用返回值里的 conversationId 续聊
     * 网页客户身份由服务端 HttpOnly Cookie 确定，请求中的 userId 仅为旧客户端兼容参数且会被忽略。
     */
    @PostMapping("/api/chat/rag")
    public Map<String, Object> ragChat(@RequestParam String message,
                                       @RequestParam(required = false) String conversationId,
                                       @RequestParam(required = false) String userId,
                                       HttpServletRequest request,
                                       HttpServletResponse response) {
        return com.example.aics.report.ChatResponseTimingConfig.answered(request,
                executeChat(message, conversationId, customerIdentities.resolve(request, response)));
    }

    private Map<String, Object> executeChat(String message, String conversationId, String userId) {
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("消息不能为空");
        }
        if (message.length() > MAX_MESSAGE_CHARS) {
            throw new IllegalArgumentException("单条消息不能超过 " + MAX_MESSAGE_CHARS + " 个字符");
        }
        try (ConversationTurnGuard.Lease ignored = turnGuard.acquire(conversationId, userId)) {
            String convId = conversationId == null || conversationId.isBlank()
                    ? null : conversationService.ensureConversation(conversationId, userId);
            Map<String, Object> route = convId == null
                    ? Map.of("status", "active") : conversationService.getRoute(convId);
            String status = String.valueOf(route.getOrDefault("status", "active"));
            if ("human".equals(status) || "need_human".equals(status)) {
                conversationService.saveMessage(convId, "user", message, null);
                return Map.of(
                        "conversationId", convId,
                        "answer", "human".equals(status)
                                ? "消息已记录，人工客服会继续为您处理"
                                : "消息已记录，您仍在人工客服排队中",
                        "type", "human".equals(status) ? "HUMAN" : "QUEUED",
                        "conversationStatus", status,
                        "sources", List.of(),
                        "images", List.of(),
                        "linkCards", List.of());
            }
            ChatEngine.Prepared prepared = chatEngine.prepare(message, convId, userId);

            // 应答路由：Agent 业务工具 > FAQ 直回 > 澄清/库外拒答 > RAG + LLM 生成
            String answer;
            String matchType;
            if (prepared.agentAnswer() != null) {
                answer = prepared.agentAnswer();
                matchType = prepared.replyType();      // AGENT
            } else if (prepared.directAnswer() != null) {
                answer = prepared.directAnswer();
                matchType = prepared.replyType();      // FAQ
            } else if (prepared.refused()) {
                answer = chatEngine.refusalAnswer();
                matchType = "REFUSAL";  // 库外问题拒答
            } else {
                answer = modelGateway.chat(prepared.modelPrompt());
                matchType = "AI";       // 大模型生成
            }

            answer = chatEngine.finalizeAnswer(prepared, answer);

            chatEngine.persist(prepared, message, answer, matchType);
            chatEngine.markImagesSent(prepared.conversationId(), prepared.images());

            List<Map<String, Object>> sources = prepared.displaySources().stream()
                    .map(m -> Map.<String, Object>of("docName", m.docName(), "distance", m.distance()))
                    .toList();
            return Map.of(
                    "conversationId", prepared.conversationId(),
                    "answer", answer,
                    "type", matchType,
                    "sources", sources,
                    "images", prepared.images(),
                    "linkCards", prepared.linkCards());
        }
    }

    /**
     * W26-U1：客户发送图片（医美场景"拍照给客服看"刚需）。
     * 流程：存文件到 uploads/images/（不入素材库）→ 视觉模型 describeImage 转文字描述 →
     * 返回 {url, description}；前端把描述作为消息内容发 WS，ChatEngine 零改动。
     * 视觉模型异常时 description 给兜底文案，不阻塞发图。
     */
    @PostMapping("/api/chat-image")
    public Map<String, Object> chatImage(@RequestParam("file") MultipartFile file,
                                         @RequestParam(required = false) String userId,
                                         HttpServletRequest request,
                                         HttpServletResponse response) throws IOException {
        String effectiveUserId = customerIdentities.resolve(request, response);
        Map<String, Object> saved = imageService.saveCustomerImage(file, effectiveUserId);
        String description;
        try {
            description = ocrService.describeImage(file.getBytes(), file.getContentType());
        } catch (Exception e) {
            description = "客户发来一张图片";
        }
        saved.put("description", description);
        saved.put("userId", effectiveUserId);
        return saved;
    }

    /**
     * 管理端全量会话浏览（会话质检用）：可选内容关键词/最近 N 天过滤，按更新时间倒序，支持分页。
     * 注意：该接口受管理鉴权保护（见 WebConfig）；客户端会话列表只按 HttpOnly Cookie 身份查询。
     * W26-M2：新增 page/pageSize 分页（返回 {total, rows}）。
     */
    @GetMapping("/api/conversations/query")
    public Map<String, Object> queryConversations(@RequestParam(required = false) String keyword,
                                                  @RequestParam(required = false) Integer days,
                                                  @RequestParam(defaultValue = "1") Integer page,
                                                  @RequestParam(defaultValue = "20") Integer pageSize) {
        return conversationService.queryConversations(keyword, days, page, pageSize);
    }

    /** 管理端会话质检详情：由权限拦截器保护，不复用客户 Cookie 归属接口。 */
    @GetMapping("/api/conversations/{id}/messages")
    public List<Map<String, Object>> adminConversationMessages(@PathVariable String id) {
        return conversationService.listMessages(id);
    }

    /** 某会话的全部消息（刷新页面后恢复聊天记录）；必须带 userId 且校验会话归属，否则 404 */
    @GetMapping("/api/messages")
    public org.springframework.http.ResponseEntity<?> messages(@RequestParam String conversationId,
                                                                @RequestParam(required = false) String userId,
                                                                HttpServletRequest request,
                                                                HttpServletResponse response) {
        String effectiveUserId = customerIdentities.resolve(request, response);
        List<Map<String, Object>> rows = conversationService.listMessagesOwned(conversationId, effectiveUserId);
        if (rows == null) {
            return org.springframework.http.ResponseEntity.status(404)
                    .body(Map.of("error", "会话不存在或无权访问"));
        }
        return org.springframework.http.ResponseEntity.ok(rows);
    }

    /**
     * 会话转人工状态（带归属校验）：切换会话/刷新页面后恢复"人工服务中/排队中"横幅。
     * 返回 {"status":"active|human|need_human|closed", "agent_name":"客服小美"}
     */
    @GetMapping("/api/conversation-status")
    public org.springframework.http.ResponseEntity<?> conversationStatus(
            @RequestParam String conversationId,
            @RequestParam(required = false) String userId,
            HttpServletRequest request,
            HttpServletResponse response) {
        String effectiveUserId = customerIdentities.resolve(request, response);
        Map<String, Object> st = conversationService.getStatusOwned(conversationId, effectiveUserId);
        if (st == null) {
            return org.springframework.http.ResponseEntity.status(404)
                    .body(Map.of("error", "会话不存在或无权访问"));
        }
        return org.springframework.http.ResponseEntity.ok(st);
    }

    /**
     * 我的历史会话列表（网页渠道，P1-3）：身份只来自 HttpOnly Cookie，
     * 前端传参一律忽略；归属校验在 SQL 里强制 user_id 绑定，防止串号。
     */
    @GetMapping("/api/my-conversations")
    public Map<String, Object> myConversations(@RequestParam(defaultValue = "1") Integer page,
                                               @RequestParam(defaultValue = "10") Integer pageSize,
                                               HttpServletRequest request,
                                               HttpServletResponse response) {
        String effectiveUserId = customerIdentities.resolve(request, response);
        return conversationService.listConversationsOwned(effectiveUserId, page, pageSize);
    }

    /** 清除我的全部记忆：会话、聊天记录、用户画像、短期缓存（客户侧自服务） */
    @DeleteMapping("/api/memory")
    public Map<String, Object> clearMemory(@RequestParam(required = false) String userId,
                                           HttpServletRequest request,
                                           HttpServletResponse response) {
        String effectiveUserId = customerIdentities.resolve(request, response);
        int deleted = conversationService.deleteUserData(effectiveUserId);
        String nextUserId = customerIdentities.newUserId();
        response.addHeader(HttpHeaders.SET_COOKIE,
                customerIdentities.cookie(nextUserId, customerIdentities.isSecure(request)).toString());
        return Map.of("cleared", true, "conversations", deleted);
    }

    /**
     * 后台「会话质检」删除指定会话（级联删除其消息与短期记忆缓存）。
     * 破坏性操作：需 conversations.delete 权限（见 AdminAuthInterceptor），与只读查看的
     * conversations.view 分开授权；操作记入审计日志（操作人由 AuditService 取当前管理员）。
     */
    @DeleteMapping("/api/conversations/{id}")
    public Map<String, Object> deleteConversation(@PathVariable String id) {
        int deleted = conversationService.deleteConversation(id);
        auditService.log("DELETE_CONVERSATION", "会话ID=" + id, "admin");
        return Map.of("deleted", deleted);
    }

    /**
     * 后台「会话质检」清空全部会话：极度危险操作，需超级管理员密码二次验证。
     * 物理删除全部会话（外键级联删除全部消息与短期记忆缓存），保留用户画像/预约/留言等业务数据。
     * 路径用字面量 /clear 以优先于 /{id} 模式匹配，避免与单条删除路由冲突。
     */
    @DeleteMapping("/api/conversations/clear")
    public Map<String, Object> clearAllConversations(@RequestBody(required = false) Map<String, String> body,
                                                      HttpServletRequest request) {
        String currentPassword = body == null ? null : body.get("currentPassword");
        String token = authCookies.adminToken(request);
        if (!agentAccountService.verifyCurrentPassword(token, currentPassword)) {
            throw new IllegalArgumentException("超级管理员密码错误，已拒绝清空操作");
        }
        if (!agentAccountService.isSuperAdminToken(token)) {
            throw new IllegalArgumentException("仅超级管理员可执行清空全部会话操作");
        }
        int deleted = conversationService.deleteAllConversations();
        auditService.log("DELETE_ALL_CONVERSATIONS", "清空全部会话，共 " + deleted + " 条", "admin");
        return Map.of("ok", true, "deleted", deleted);
    }

    /**
     * 网页端欢迎语接口：前端页面加载时主动调用，让 AI 在用户开口前先发欢迎语。
     * - 欢迎语未配置（welcome.message 为空）→ 返回 welcome=""，不建会话；
     * - 该用户已有 active 会话（老用户回访）→ 不重复欢迎，返回 conversationId 供前端恢复；
     * - 真正的新用户 → 建会话 + 欢迎语落库（assistant 消息），返回 welcome + conversationId。
     * 欢迎语落库后，用户第一条消息时 ChatEngine.isFirstMessage=false，不会二次欢迎。
     */
    @PostMapping("/api/welcome")
    public Map<String, Object> welcome(@RequestParam(required = false) String userId,
                                       HttpServletRequest request,
                                       HttpServletResponse response) {
        String effectiveUserId = customerIdentities.resolve(request, response);
        String welcome = chatEngine.welcomeMessage();
        if (welcome == null || welcome.isBlank()) {
            return Map.of("welcome", "", "conversationId", "");
        }
        String existing = conversationService.findActiveConversation(effectiveUserId);
        if (existing != null) {
            return Map.of("welcome", "", "conversationId", existing);
        }
        String convId = conversationService.ensureConversation(null, effectiveUserId);
        // 欢迎语作为 assistant 消息落库（不参与画像统计，只做会话记录）
        conversationService.saveMessage(convId, "assistant", welcome, null);
        return Map.of("welcome", welcome, "conversationId", convId);
    }
}
