package com.example.aics.channel;

import com.example.aics.ai.ModelGateway;
import com.example.aics.chat.ChatEngine;
import com.example.aics.chat.ConversationService;
import com.example.aics.chat.ResponseTiming;
import com.example.aics.chat.ConversationTurnGuard;
import com.example.aics.config.ConfigService;
import com.example.aics.knowledge.OcrService;
import com.example.aics.message.LinkCard;
import com.example.aics.state.StateStore;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

/**
 * 企微微信客服消息处理（channel 包骨架，2026-08-07，按实施方案 v2.0 4.2/4.3）。
 *
 * 事件回调 → XML 取通知 Token → kf/sync_msg 拉取真实消息 → 归一化 MessageInbound → 幂等去重 → 身份映射
 * → ChatEngine 完整应答路由（Agent工具/FAQ直回/澄清拒答/AI+RAG）
 * → kf/send_msg 发回客户微信。
 *
 * 已实现：text 全链路真实可跑；image → media 下载 → 视觉模型描述（复用 OcrService）；
 * voice → media 下载 → 腾讯云 ASR 方言转文字（WeComAsrService）；video → 兜底话术；
 * location → 经纬度 → 就近门店（复用 ChatEngine 定位分支）。
 * 真实账号联调待密钥到位（corpid/微信客服 secret/Token/AESKey；语音另需腾讯云 ASR 凭证）。
 *
 * W27 修复（2026-08-08）：
 * - B1：answer() 第四层接入 ModelGateway（AI+RAG 生成），不再返回占位文案；
 * - B2：会话复用 findOrCreateKfConversation（回调无 conversationId，按用户复用 active 会话，
 *       修复预约流程/多轮上下文断裂）；
 * - B3：线程池改有界队列；队列满时拒绝回调入队，由企微重试，绝不阻塞 5 秒回调线程；
 * - S1：media 下载校验 HTTP 状态码 + 20MB 上限；
 * - S2：external_user 映射 ON CONFLICT DO NOTHING（并发回调主键冲突吞消息）；
 * - S4：msg_send_fail 落 audit_log（send_msg 成功≠送达，失败事件必须留痕）。
 */
@Service
public class WeComKfService {

    private static final Logger log = LoggerFactory.getLogger(WeComKfService.class);
    private static final int MAX_SYNC_PAGES = 20;
    private static final int MAX_OUTBOUND_MESSAGES_PER_TURN = 5;
    private static final long MESSAGE_DEDUP_SECONDS = 3 * 24 * 3600L;
    private static final String CURSOR_PREFIX = "kf:cursor:";
    private static final String PENDING_HANDOFF_PREFIX = "kf:handoff:pending:";

    private final ChatEngine chatEngine;
    private final ConversationService conversationService;
    private final StateStore stateStore;
    private final JdbcTemplate jdbc;
    private final WeComKfTokenService tokenService;
    private final OcrService ocrService;
    private final WeComAsrService asrService;
    private final ModelGateway modelGateway;
    private final ConfigService config;
    private final WeComImageSender imageSender;
    private final WeComKfApiClient apiClient;
    private final ConversationTurnGuard turnGuard;
    private final WeComRuntimeMonitor monitor;
    private final WeComTenantService tenants;
    private final ThreadLocal<WeComTenantService.TenantCredentials> activeTenant = new ThreadLocal<>();
    private final Map<String, Object> syncLocks = new ConcurrentHashMap<>();

    /** 企微图文卡片缩略图的公网绝对地址；生产必须显式配置（@PostConstruct fail-fast），不再硬编码兜底域名。 */
    @Value("${aics.public-base-url:}")
    String publicBaseUrl;
    @Value("${spring.profiles.active:}")
    private String activeProfiles;

    /** 生产环境 fail-fast：公网入口缺失时企微图文卡片无法生成缩略图（2026-09-04 评审 P1-2）。 */
    @PostConstruct
    void requirePublicBaseUrlInProd() {
        boolean prod = Arrays.stream(activeProfiles.split(","))
                .map(String::trim).anyMatch("prod"::equals);
        if (prod && (publicBaseUrl == null || publicBaseUrl.isBlank())) {
            throw new IllegalStateException(
                    "生产环境必须配置 AICS_PUBLIC_BASE_URL（对外 HTTPS 域名，企微图文卡片缩略图依赖它）");
        }
        if (publicBaseUrl == null || publicBaseUrl.isBlank()) {
            log.warn("未配置 aics.public-base-url（AICS_PUBLIC_BASE_URL）：企微图文卡片将无法生成公网缩略图地址");
        }
    }

    /** 一次企微 AI 应答：文字、图文卡片、图片按顺序作为真正的微信消息发送。 */
    record KfAnswer(String text, String conversationId, List<Map<String, Object>> images,
                    List<LinkCard> linkCards, String eventCode, ResponseTiming timing) {
        KfAnswer(String text, String conversationId, List<Map<String, Object>> images,
                 List<LinkCard> linkCards, String eventCode) {
            this(text, conversationId, images, linkCards, eventCode, null);
        }

        KfAnswer(String text, String conversationId, List<Map<String, Object>> images) {
            this(text, conversationId, images, List.of(), "");
        }

        KfAnswer(String text, String conversationId, List<Map<String, Object>> images,
                 String eventCode) {
            this(text, conversationId, images, List.of(), eventCode);
        }
    }

    private record HandoffResult(int serviceState, String msgCode) {}

    /** 有界队列；满时直接拒绝，让回调端返回非 success 触发企微重试。 */
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(
            2, 4, 60L, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(500),
            new ThreadPoolExecutor.AbortPolicy());
    /** 欢迎语幂等窗口：同一客户 24h 内只主动欢迎一次（防重复骚扰） */
    private static final long WELCOME_TTL_SECONDS = 24 * 3600L;

    @Autowired
    public WeComKfService(ChatEngine chatEngine,
                          ConversationService conversationService,
                          StateStore stateStore,
                          JdbcTemplate jdbc,
                          WeComKfTokenService tokenService,
                          OcrService ocrService,
                          WeComAsrService asrService,
                          ModelGateway modelGateway,
                          ConfigService config,
                          WeComImageSender imageSender,
                          WeComKfApiClient apiClient,
                          ConversationTurnGuard turnGuard,
                          WeComRuntimeMonitor monitor,
                          WeComTenantService tenants) {
        this.chatEngine = chatEngine;
        this.conversationService = conversationService;
        this.stateStore = stateStore;
        this.jdbc = jdbc;
        this.tokenService = tokenService;
        this.ocrService = ocrService;
        this.asrService = asrService;
        this.modelGateway = modelGateway;
        this.config = config;
        this.imageSender = imageSender;
        this.apiClient = apiClient;
        this.turnGuard = turnGuard;
        this.monitor = monitor;
        this.tenants = tenants;
    }

    WeComKfService(ChatEngine chatEngine,
                   ConversationService conversationService,
                   StateStore stateStore,
                   JdbcTemplate jdbc,
                   WeComKfTokenService tokenService,
                   OcrService ocrService,
                   WeComAsrService asrService,
                   ModelGateway modelGateway,
                   ConfigService config,
                   WeComImageSender imageSender,
                   WeComKfApiClient apiClient,
                   ConversationTurnGuard turnGuard,
                   WeComRuntimeMonitor monitor) {
        this(chatEngine, conversationService, stateStore, jdbc, tokenService, ocrService,
                asrService, modelGateway, config, imageSender, apiClient, turnGuard, monitor, null);
    }

    /** 回调线程池异步处理：必须绑定企业，AI 回复在异步线程完成。 */
    public void handleEventAsync(WeComTenantService.TenantCredentials tenant, String plainXml) {
        requireTenant(tenant);
        executor.execute(() -> {
            try {
                handleEvent(tenant, plainXml);
            } catch (Exception e) {
                log.error("[Kf] 企业 {} 事件处理失败: {}", tenant.code(), e.getMessage());
                monitor.callbackProcessingFailed(e.getMessage());
            }
        });
    }

    /** 显式绑定企业上下文；同步入口也便于隔离逻辑做确定性测试。 */
    void handleEvent(WeComTenantService.TenantCredentials tenant, String plainXml) throws Exception {
        requireTenant(tenant);
        WeComTenantService.TenantCredentials previous = activeTenant.get();
        activeTenant.set(tenant);
        try {
            handleEvent(plainXml);
        } finally {
            if (previous == null) activeTenant.remove();
            else activeTenant.set(previous);
        }
    }

    Map<String, Object> executorStatus() {
        return Map.of(
                "active", executor.getActiveCount(),
                "poolSize", executor.getPoolSize(),
                "maxPoolSize", executor.getMaximumPoolSize(),
                "queued", executor.getQueue().size(),
                "queueCapacity", executor.getQueue().size() + executor.getQueue().remainingCapacity(),
                "completed", executor.getCompletedTaskCount());
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) executor.shutdownNow();
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    void handleEvent(String plainXml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        Document doc = factory.newDocumentBuilder()
                .parse(new ByteArrayInputStream(plainXml.getBytes(StandardCharsets.UTF_8)));
        Element root = doc.getDocumentElement();

        String event = text(root, "Event");
        String msgType = text(root, "MsgType");
        if (!"kf_msg_or_event".equals(event)
                || (!msgType.isBlank() && !"event".equals(msgType))) {
            log.info("[Kf] 忽略未识别回调: MsgType={} Event={}", msgType, event);
            return;
        }

        String callbackToken = text(root, "Token");
        String openKfId = text(root, "OpenKfId");
        if (callbackToken.isBlank()) {
            log.warn("[Kf] kf_msg_or_event 回调缺少 Token，无法拉取真实消息");
            monitor.callbackProcessingFailed("kf_msg_or_event 回调缺少拉取 Token");
            return;
        }

        String effectiveKfId = requireOpenKfId(openKfId);
        WeComTenantService.KfAccount account = accountContext(effectiveKfId);
        // 多企业模式必须先验证客服号确实属于回调企业；随后把范围传给知识、图片和门店服务。
        String scopedKfId = tenantScopeKey() + ":" + effectiveKfId;
        WeComTenantService.TenantCredentials tenant = tenantContext();
        try (TenantExecutionContext.Lease ignored = TenantExecutionContext.use(tenant.id(), account.id())) {
            synchronized (syncLocks.computeIfAbsent(scopedKfId, key -> new Object())) {
                syncCallbackMessages(callbackToken, effectiveKfId);
            }
        }
    }

    private void syncCallbackMessages(String callbackToken, String openKfId) throws Exception {
        monitor.syncStarted();
        String cursorKey = CURSOR_PREFIX + tenantScopeKey() + ":" + openKfId;
        String storedCursor = stateStore.getValue(cursorKey);
        String cursor = storedCursor == null ? "" : storedCursor;
        int syncedItemCount = 0;
        try {
            for (int pageNumber = 0; pageNumber < MAX_SYNC_PAGES; pageNumber++) {
                WeComTenantService.TenantCredentials tenant = requireTenantContext();
                WeComKfApiClient.SyncPage page = apiClient.syncMessages(
                        tenant, callbackToken, openKfId, cursor);
                syncedItemCount += page.messages().size();
                for (JsonNode item : page.messages()) {
                    if (!dispatchSyncedItem(item)) {
                        throw new IllegalStateException("企微消息处理失败，保留原游标等待重试");
                    }
                }
                String nextCursor = page.nextCursor();
                if (!nextCursor.isBlank()) {
                    stateStore.putValue(cursorKey, nextCursor);
                }
                if (!page.hasMore()) {
                    monitor.syncSucceeded(syncedItemCount);
                    return;
                }
                if (nextCursor.isBlank() || nextCursor.equals(cursor)) {
                    throw new IllegalStateException("企微 sync_msg 返回重复或空游标");
                }
                cursor = nextCursor;
            }
            throw new IllegalStateException("企微 sync_msg 分页超过安全上限 " + MAX_SYNC_PAGES);
        } catch (Exception e) {
            monitor.syncFailed(e.getMessage());
            throw e;
        }
    }

    // ==================== 客户消息 ====================

    /** sync_msg 条目分发：事件单独处理，只让客户来源消息进入 AI，避免回复自身形成消息循环。 */
    boolean dispatchSyncedItem(WeComTenantService.TenantCredentials tenant, JsonNode item) {
        return inTenant(tenant, () -> dispatchSyncedItem(item));
    }

    boolean dispatchSyncedItem(JsonNode item) {
        if (item == null || !item.isObject()) return true;
        String msgId = item.path("msgid").asText("");
        ResponseTiming timing = msgId.isBlank() ? ResponseTiming.start()
                : ResponseTiming.start("kf:" + tenantScopeKey() + ":" + msgId);
        String dedupKey = msgId.isBlank() ? null : "kf:msg:" + tenantScopeKey() + ":" + msgId;
        if (!msgId.isBlank()) {
            Boolean first = stateStore.setNx(dedupKey, "1", MESSAGE_DEDUP_SECONDS);
            if (first == null || !first) {
                log.info("[Kf] 重复消息 msgId={}，跳过", msgId);
                monitor.duplicateItem();
                return true;
            }
        }
        boolean completed = false;
        try {
            String msgType = item.path("msgtype").asText("");
            if ("event".equals(msgType)) {
                dispatchSyncedEvent(item);
                completed = true;
                return true;
            }
            if (item.path("origin").asInt(0) != 3) {
                log.debug("[Kf] 忽略非客户来源消息 msgId={} origin={}",
                        msgId, item.path("origin").asInt(0));
                completed = true;
                return true;
            }

            MessageInbound inbound = normalize(item);
            if (inbound == null) {
                log.info("[Kf] 暂不处理消息类型 {} msgId={}", msgType, msgId);
                completed = true;
                return true;
            }
            String openKfId = item.path("open_kfid").asText("");
            inbound = resolveMedia(inbound, openKfId);
            if (inbound == null) {
                completed = true; // 已发兜底话术，无需再走 AI
                return true;
            }
            KfAnswer answer = answer(inbound, openKfId, timing);
            completed = deliverAnswer(inbound.externalUserId(), openKfId, answer);
            return completed;
        } catch (Exception e) {
            log.error("[Kf] 消息处理失败 msgId={}: {}", msgId, e.getMessage());
            return false;
        } finally {
            if (!completed && dedupKey != null) {
                try { stateStore.removeValue(dedupKey); } catch (Exception ignored) { }
            }
        }
    }

    private void dispatchSyncedEvent(JsonNode item) {
        JsonNode event = item.path("event");
        String eventType = event.path("event_type").asText("");
        // 官方协议中 msgtype=event 时，open_kfid/external_userid 位于 event 对象内。
        String externalUserId = event.path("external_userid").asText("");
        String openKfId = event.path("open_kfid").asText("");
        if ("enter_session".equals(eventType)) {
            onEnterSession(externalUserId, openKfId, event.path("welcome_code").asText(""));
        } else if ("msg_send_fail".equals(eventType)) {
            onMsgSendFail(externalUserId, event.path("fail_type").asText(""),
                    event.path("fail_msgid").asText(item.path("msgid").asText("")));
        } else if ("session_status_change".equals(eventType)) {
            onSessionStatusChange(openKfId, externalUserId,
                    event.path("change_type").asInt(0),
                    event.path("old_servicer_userid").asText(""),
                    event.path("new_servicer_userid").asText(""));
        } else {
            log.info("[Kf] 忽略无需业务处理的同步事件 {}", eventType);
        }
    }

    /** 多媒体归一化：image/voice → media 下载 + 识别转文字（重建为 text）；video → 兜底话术返回 null */
    MessageInbound resolveMedia(MessageInbound in, String openKfId) {
        if ("image".equals(in.type())) {
            try {
                byte[] bytes = getMedia(in.content());
                String desc = ocrService.describeImage(bytes, "image/jpeg");
                if (desc == null || desc.isBlank()) {
                    throw new IllegalStateException("视觉理解结果为空");
                }
                // 图片识别透明化（W26 反馈：不要反问客户"我识别得对吗"）：
                // 1) 陈述式透明——AI 回答时自然带出"从图里看到的关键信息"，所见即所说，
                //    识别有误客户会自然纠正，不需要机器走确认流程；
                // 2) 不确定性降级——视觉模型自评不确定时（描述带【不确定】标记），
                //    回答改用如实说明+邀请文字补充，而不是基于模糊识别给出笃定结论。
                // 3) 组帧前缀使用 ChatEngine.IMAGE_DERIVED_PREFIX 常量：ChatEngine 据此排除
                //    索图意图误判（组帧与识别描述中不能出现"图片/海报"等索图触发词）。
                boolean uncertain = desc.startsWith("【不确定】") || desc.startsWith("【不确定：");
                String framing = uncertain
                        ? ChatEngine.IMAGE_DERIVED_PREFIX + "视觉识别不太确定：" + desc
                          + "。回答时请如实说明这张图看得不够准，用陈述语气请客户用文字补充关键信息；"
                          + "不要基于猜测给出价格、效果等任何确定性结论，也不要向客户提问确认。）"
                        : ChatEngine.IMAGE_DERIVED_PREFIX + "画面识别结果：" + desc
                          + "。回答时用一两句自然带出你从画面中看到的关键信息（陈述，不要向客户提问确认），"
                          + "再继续回答客户的问题。）";
                return new MessageInbound(in.externalUserId(), in.msgId(), "text", framing,
                        null, null, null, null, in.timestampMs());
            } catch (Exception e) {
                log.warn("[Kf] 客户图片理解失败: {}", e.getMessage());
                sendText(in.externalUserId(), openKfId, "图片我收到啦，不过暂时没识别出来，麻烦您用文字描述一下哦～");
                return null;
            }
        }
        if ("voice".equals(in.type())) {
            try {
                byte[] bytes = getMedia(in.content());
                String text = asrService.recognize(bytes, "amr", true);
                return new MessageInbound(in.externalUserId(), in.msgId(), "text", text,
                        null, null, null, null, in.timestampMs());
            } catch (Exception e) {
                log.warn("[Kf] 客户语音识别失败: {}", e.getMessage());
                sendText(in.externalUserId(), openKfId, "语音我收到啦，不过暂时没听清，麻烦您用文字再发一次哦～");
                return null;
            }
        }
        if ("video".equals(in.type())) {
            sendText(in.externalUserId(), openKfId, "视频我收到啦，麻烦您用文字描述一下想咨询的内容哦～");
            return null;
        }
        return in;
    }

    /** sync_msg JSON → 渠道中立消息；多媒体只保留 media_id，后续按需下载。 */
    MessageInbound normalize(JsonNode item) {
        String externalUserId = item.path("external_userid").asText("");
        String msgId = item.path("msgid").asText("");
        String type = item.path("msgtype").asText("");
        long sendTime = item.path("send_time").asLong(0) * 1000;
        if (externalUserId.isBlank()) {
            log.warn("[Kf] 同步消息缺 external_userid，msgId={}", msgId);
            return null;
        }
        return switch (type) {
            case "text" -> new MessageInbound(externalUserId, msgId, type,
                    item.path("text").path("content").asText(""),
                    null, null, null, null, sendTime);
            case "image", "voice", "video" -> new MessageInbound(externalUserId, msgId, type,
                    item.path(type).path("media_id").asText(""),
                    null, null, null, null, sendTime);
            case "location" -> new MessageInbound(externalUserId, msgId, type, "",
                    nullableDouble(item.path("location").get("latitude")),
                    nullableDouble(item.path("location").get("longitude")),
                    null, null, sendTime);
            default -> null;
        };
    }

    /** 问答全链路：身份映射 → 会话 → ChatEngine 完整应答路由 → 落库 */
    KfAnswer answer(WeComTenantService.TenantCredentials tenant, MessageInbound inbound,
                    String callbackOpenKfId) {
        return inTenant(tenant, () -> answer(inbound, callbackOpenKfId));
    }

    private KfAnswer answer(MessageInbound inbound, String callbackOpenKfId) {
        return answer(inbound, callbackOpenKfId, ResponseTiming.start());
    }

    private KfAnswer answer(MessageInbound inbound, String callbackOpenKfId, ResponseTiming timing) {
        // 1. 身份映射：external_userid → 系统 user_id（无则新建，格式 u_kf_xxx）
        WeComTenantService.TenantCredentials tenant = tenantContext();
        WeComTenantService.KfAccount account = accountContext(callbackOpenKfId);
        String userId = mapUserId(tenant, inbound.externalUserId());
        String text = inbound.answerText();
        if (text == null || text.isBlank()) {
            return new KfAnswer("您的消息我这边没识别出来，麻烦用文字描述一下哦～",
                    null, List.of());
        }
        // 2. 会话复用（W27-B2）：回调无 conversationId，按用户复用最近 active 会话，
        //    保证预约流程（Redis 进度按 convId 存）与多轮上下文不断裂
        try (ConversationTurnGuard.Lease ignored = turnGuard.acquire(null, userId)) {
            String convId = conversationService.findOrCreateKfConversation(
                    userId, tenant.id(), account.id());
            conversationService.bindKfChannel(convId, inbound.externalUserId(), callbackOpenKfId,
                    tenant.id(), account.id());
            Map<String, Object> route = conversationService.getRoute(convId);
            String status = String.valueOf(route.getOrDefault("status", "active"));
            if ("human".equals(status) || "need_human".equals(status)) {
                String pendingKey = PENDING_HANDOFF_PREFIX + convId;
                boolean retryingFailedHandoff = inbound.msgId() != null
                        && inbound.msgId().equals(stateStore.getValue(pendingKey));
                // 客户原消息会直接出现在企业微信客户端，本地仍落库供历史和质检使用。
                // 同一个回调 msgid 因转接失败重试时，避免把客户消息重复落库。
                if (!retryingFailedHandoff) {
                    conversationService.saveMessage(convId, "user", text, null);
                }
                HandoffResult handoff = null;
                if ("need_human".equals(status)) {
                    handoff = ensureQueuedInWeCom(convId, inbound.externalUserId(), callbackOpenKfId,
                            inbound.msgId());
                }
                // 已排队/已接待时 AI 不再插话；仅首次转池且有 msg_code 时发送一次事件响应语。
                String acknowledgement = handoff != null && !handoff.msgCode().isBlank()
                        ? "已为您转入人工客服待接入池，客服会按顺序接入，请稍候。" : null;
                return new KfAnswer(acknowledgement, convId, List.of(),
                        handoff == null ? "" : handoff.msgCode());
            }
            // 3. 完全复用 ChatEngine：Agent工具/FAQ直回/澄清或拒答/AI+RAG
            ChatEngine.Prepared prepared = chatEngine.prepareForWeCom(text, convId, userId,
                    inbound.lat(), inbound.lng());
        // 新用户接入欢迎语兜底：仅在"会话首条消息"且 24h 内未主动欢迎过时补发。
        // 正常情况下 enter_session 事件已主动推送（onEnterSession），首条消息不再重复。
            boolean prependFallbackWelcome = prepared.welcome() != null
                    && Boolean.TRUE.equals(stateStore.setNx(
                    "kf:welcome:" + tenantScopeKey() + ":" + account.openKfId() + ":"
                            + inbound.externalUserId(),
                    "1", WELCOME_TTL_SECONDS));
        // 第四层 AI 生成（W27-B1）：prepared.prompt() 非 null 时走模型网关主备切换，
        // 与 ChatController.ragChat 四层逻辑保持一致，不再返回占位文案
            String answer = prepared.agentAnswer() != null ? prepared.agentAnswer()
                    : prepared.directAnswer() != null ? prepared.directAnswer()
                    : prepared.refused() ? chatEngine.refusalAnswer()
                    : prepared.modelPrompt() != null
                    ? modelGateway.chat(scopedPrompt(account, prepared.modelPrompt()))
                    : "（AI 生成失败，请稍后再试）";
            answer = chatEngine.finalizeAnswer(prepared, answer);
            if (prependFallbackWelcome) {
                // 欢迎语与本轮回答合并为一条文本，避免一次客户消息触发超过 5 条回复；
                // setNx 同时保证 enter_session 缺失时也只补发一次。
                answer = prepared.welcome().trim() + "\n\n" + answer;
            }
            chatEngine.persist(prepared, text, answer, prepared.replyType());
            Map<String, Object> afterRoute = conversationService.getRoute(prepared.conversationId());
            String afterStatus = String.valueOf(afterRoute.getOrDefault("status", "active"));
            if ("need_human".equals(afterStatus)) {
                HandoffResult handoff = ensureQueuedInWeCom(prepared.conversationId(),
                        inbound.externalUserId(), callbackOpenKfId, inbound.msgId());
                return new KfAnswer(handoff.msgCode().isBlank() ? null : answer,
                        prepared.conversationId(), List.of(), handoff.msgCode());
            }
            return new KfAnswer(answer, prepared.conversationId(), prepared.images(),
                    prepared.linkCards(), "", timing);
        }
    }

    /**
     * 先发文字，再发企微原生 link 地址卡片，最后上传并发送图片。
     * 卡片失败时降级为普通腾讯地图链接；整轮始终遵守企微最多 5 条消息的限制。
     */
    boolean deliverAnswer(WeComTenantService.TenantCredentials tenant, String externalUserId,
                          String callbackOpenKfId, KfAnswer answer) {
        return inTenant(tenant, () -> deliverAnswer(externalUserId, callbackOpenKfId, answer));
    }

    private boolean deliverAnswer(String externalUserId, String callbackOpenKfId, KfAnswer answer) {
        String targetKfId = requireOpenKfId(callbackOpenKfId);
        if (answer.eventCode() != null && !answer.eventCode().isBlank()) {
            if (answer.text() == null || answer.text().isBlank()) return true;
            try {
                apiClient.sendTextOnEvent(requireTenantContext(), answer.eventCode(), answer.text());
                monitor.outboundAccepted();
            } catch (Exception e) {
                log.error("[Kf] 转人工事件响应语发送失败: {}", e.getMessage());
                monitor.outboundFailed(e.getMessage());
                auditHandoff("KF_HANDOFF_RESPONSE_FAIL", externalUserId, e.getMessage());
            }
            // 会话转接已经成功，事件响应语失败不能回滚或重复转接。
            return true;
        }
        int outboundAttempts = 0;
        if (answer.text() != null && !answer.text().isBlank()) {
            boolean textAccepted = sendText(externalUserId, targetKfId, answer.text());
            if (!textAccepted) return false;
            conversationService.recordFirstResponse(answer.conversationId(), answer.timing());
            outboundAttempts++;
        }

        List<LinkCard> cards = answer.linkCards() == null ? List.of() : answer.linkCards();
        int processedCards = 0;
        for (LinkCard card : cards) {
            if (outboundAttempts >= MAX_OUTBOUND_MESSAGES_PER_TURN) break;
            processedCards++;
            outboundAttempts++;
            LinkCard publicCard = card.withPublicBaseUrl(publicBaseUrl);
            boolean accepted = false;
            try {
                apiClient.sendLink(requireTenantContext(), externalUserId, targetKfId, publicCard);
                monitor.outboundAccepted();
                accepted = true;
            } catch (Exception e) {
                log.warn("[Kf] 腾讯地图地址卡片发送失败，准备降级为文本链接: {}", e.getMessage());
                monitor.outboundFailed(e.getMessage());
            }
            if (!accepted && outboundAttempts < MAX_OUTBOUND_MESSAGES_PER_TURN) {
                outboundAttempts++;
                sendText(externalUserId, targetKfId, card.fallbackText());
            }
        }

        List<Map<String, Object>> candidateImages = answer.images() == null ? List.of() : answer.images();
        List<Map<String, Object>> delivered = new java.util.ArrayList<>();
        int processedImages = 0;
        boolean imageFailed = false;
        for (Map<String, Object> image : candidateImages) {
            if (outboundAttempts >= MAX_OUTBOUND_MESSAGES_PER_TURN) break;
            processedImages++;
            long imageId = imageId(image);
            outboundAttempts++;
            boolean imageAccepted = imageId > 0 && imageSender.send(
                    requireTenantContext(), externalUserId, targetKfId, imageId);
            if (imageAccepted) {
                delivered.add(image);
                monitor.outboundAccepted();
            } else {
                imageFailed = true;
                monitor.outboundFailed("企微图片发送失败 image_id=" + imageId);
                logImageSendFailure(externalUserId, imageId);
            }
        }
        if (!delivered.isEmpty() && answer.conversationId() != null) {
            chatEngine.markImagesSent(answer.conversationId(), delivered);
        }
        if (imageFailed && outboundAttempts < MAX_OUTBOUND_MESSAGES_PER_TURN) {
            outboundAttempts++;
            sendText(externalUserId, targetKfId, "图片暂时发送失败了，请稍后再让我发一次哦～");
        }
        if (processedCards < cards.size() || processedImages < candidateImages.size()) {
            monitor.messageLimitTruncated();
            log.warn("[Kf] 单轮消息达到 {} 条上限，地址卡片 {}/{}、图片 {}/{}",
                    MAX_OUTBOUND_MESSAGES_PER_TURN, processedCards, cards.size(),
                    processedImages, candidateImages.size());
        }
        return true;
    }

    private long imageId(Map<String, Object> image) {
        Object value = image == null ? null : image.get("id");
        if (value instanceof Number number) return number.longValue();
        try {
            return value == null ? -1L : Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return -1L;
        }
    }

    private void logImageSendFailure(String externalUserId, long imageId) {
        try {
            jdbc.update("INSERT INTO audit_log(action, object, operator) VALUES (?, ?, ?)",
                    "KF_IMAGE_SEND_FAIL",
                    "external_user_hash=" + maskedExternalId(externalUserId) + " image_id=" + imageId,
                    "wecom");
        } catch (Exception e) {
            log.warn("记录企微图片发送失败日志异常: {}", e.getMessage());
        }
    }

    /** external_userid ↔ user_id 映射始终带企业范围。 */
    String mapUserId(WeComTenantService.TenantCredentials tenant, String externalUserId) {
        requireTenant(tenant);
        List<String> rows = jdbc.queryForList(
                "SELECT user_id FROM external_user WHERE tenant_id=? AND external_userid=?",
                String.class, tenant.id(), externalUserId);
        if (!rows.isEmpty()) return rows.get(0);
        // 内部 user_id 会被画像、预约和会话跨表复用，必须保持全局高熵；
        // 旧实现只保留 UUID 前 8 位（32 bit），客户量增长后存在生日碰撞并串画像的风险。
        String userId = "u_kf_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.update("INSERT INTO external_user(tenant_id, external_userid, user_id) VALUES (?,?,?) " +
                        "ON CONFLICT (tenant_id, external_userid) DO NOTHING",
                tenant.id(), externalUserId, userId);
        List<String> recheck = jdbc.queryForList(
                "SELECT user_id FROM external_user WHERE tenant_id=? AND external_userid=?",
                String.class, tenant.id(), externalUserId);
        return recheck.isEmpty() ? userId : recheck.get(0);
    }

    // ==================== 会话状态 / 发送失败 ====================

    /**
     * 客户进入会话（刚添加客服/点开聊天）→ AI 主动推送欢迎语，引导客户说出想咨询的项目。
     * 幂等：同一客户 24h 内只主动欢迎一次（Redis setNx），避免反复进入重复骚扰；
     * 内容来自 system_config 的 welcome.message（管理后台可热更新），清空则不推送。
     */
    void onEnterSession(String externalUserId, String kfId, String welcomeCode) {
        log.info("[Kf] 客户进入会话 客户标识={} OpenKfId={}", maskedExternalId(externalUserId), kfId);
        if (externalUserId.isBlank()) {
            return;
        }
        WeComTenantService.KfAccount account = accountContext(kfId);
        String welcome = account.welcomeMessage() == null || account.welcomeMessage().isBlank()
                ? config.get("welcome.message", "") : account.welcomeMessage();
        if (welcome == null || welcome.isBlank()) {
            return;   // 欢迎语未配置 → 不主动推送
        }
        // 幂等：24h 内已欢迎过则跳过（首次进入返回 true 才发送）
        String welcomeKey = "kf:welcome:" + tenantScopeKey() + ":" + kfId + ":" + externalUserId;
        Boolean first = stateStore.setNx(welcomeKey, "1", WELCOME_TTL_SECONDS);
        if (first == null || !first) {
            log.info("[Kf] 客户 {} 24h 内已欢迎过，跳过", maskedExternalId(externalUserId));
            return;
        }
        try {
            if (welcomeCode != null && !welcomeCode.isBlank()) {
                apiClient.sendTextOnEvent(requireTenantContext(), welcomeCode, welcome);
                monitor.outboundAccepted();
            } else {
                // 兼容极少数没有 welcome_code 的历史事件报文。
                sendText(externalUserId, kfId, welcome);
            }
        } catch (Exception e) {
            log.error("[Kf] 欢迎语发送失败: {}", e.getMessage());
            monitor.outboundFailed(e.getMessage());
        }
    }

    /** send_msg 发送失败事件：落库审计留痕（kf/send_msg 返回成功 ≠ 送达，必须盯失败事件） */
    void onMsgSendFail(String externalUserId, String failType, String msgId) {
        log.error("[Kf] 消息发送失败 客户标识={} FailType={} MsgId={}",
                maskedExternalId(externalUserId), failType, msgId);
        monitor.platformSendFailed("企微送达失败 fail_type=" + failType);
        // W27-S4：落 audit_log 留痕（"发送成功"≠"客户收到"，失败事件是唯一可靠信号，需可查可告警）
        try {
            jdbc.update("INSERT INTO audit_log(action, object, operator) VALUES (?, ?, ?)",
                    "KF_SEND_FAIL",
                    "external_user_hash=" + maskedExternalId(externalUserId) + " fail=" + failType + " msg=" + msgId,
                    "wecom");
        } catch (Exception e) {
            log.warn("写入发送失败审计日志失败: {}", e.getMessage());
        }
    }

    /**
     * 转人工分配到接待成员后，把系统沉淀的客户画像摘要通过企微应用消息推送给该成员。
     * 需要配置 wecom.appAgentId（自建应用）且接待成员在应用可见范围内；
     * 未启用/失败仅记录日志，绝不影响接待流程。
     */
    private void pushServicerProfile(String conversationId, String servicerUserid) {
        try {
            if (servicerUserid == null || servicerUserid.isBlank()) return;
            if (!config.getBoolean("agent.profilePushEnabled", false)) return;
            String agentId = config.get("wecom.appAgentId", "").trim();
            if (agentId.isBlank()) {
                log.info("[Kf] 未配置 wecom.appAgentId，跳过画像推送");
                return;
            }
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT c.user_id, p.profile FROM conversation c " +
                            "LEFT JOIN user_profile p ON p.user_id = c.user_id " +
                            "WHERE c.conversation_id = ?", conversationId);
            if (rows.isEmpty()) return;
            String userId = String.valueOf(rows.get(0).getOrDefault("user_id", ""));
            String profile = rows.get(0).get("profile") == null ? ""
                    : String.valueOf(rows.get(0).get("profile"));
            if (profile.isBlank()) {
                log.info("[Kf] 客户 {} 暂无画像，跳过推送", maskedExternalId(userId));
                return;
            }
            apiClient.pushAppTextMessage(requireTenantContext(), servicerUserid, agentId,
                    "【客户画像参考】客户 " + maskedExternalId(userId) + " 的会话已转人工接待。\n"
                            + profile + "\n（以上由系统从历史对话自动提炼，仅供接待参考）");
            log.info("[Kf] 客户画像已推送给接待成员 {}", maskedExternalId(servicerUserid));
        } catch (Exception e) {
            log.warn("[Kf] 画像推送失败（不影响接待）：{}", e.getMessage());
        }
    }

    /** 企业微信客户端接入、转接、结束或重新接入时，同步本站会话状态。 */
    void onSessionStatusChange(String openKfId, String externalUserId, int changeType,
                               String oldServicerUserId, String newServicerUserId) {
        monitor.serviceStateSyncStarted();
        try {
            if (externalUserId == null || externalUserId.isBlank()) {
                // 结构性缺失（协议变更/数据异常）：重试永远不会成功，跳过以避免同步游标永久卡死
                log.warn("[Kf] 会话状态事件缺少 external_userid，跳过该事件");
                monitor.serviceStateSyncFailed("missing external_userid");
                return;
            }
            WeComTenantService.TenantCredentials tenant = tenantContext();
            WeComTenantService.KfAccount account = accountContext(openKfId);
            String conversationId = conversationService.findOpenKfConversation(
                    externalUserId, openKfId, tenant.id(), account.id());
            if (conversationId == null) {
                log.info("[Kf] 会话状态事件没有匹配的本地未关闭会话 客户标识={} changeType={}",
                        maskedExternalId(externalUserId), changeType);
                monitor.serviceStateSyncSucceeded();
                return;
            }
            boolean hasNewServicer = newServicerUserId != null && !newServicerUserId.isBlank();
            boolean humanHold = config.getBoolean("agent.humanHoldAfterHandoff", true);
            // 客服主动交还 AI：接待人员在企微客户端退出会话/转回智能助手且未指定新接待成员。
            // 这是"客服主动同意 AI 接待"的唯一恢复信号（W26 决策：人工粘性）。
            if (changeType == 2 && !hasNewServicer) {
                conversationService.setStatus(conversationId, "active");
                stateStore.removeValue(PENDING_HANDOFF_PREFIX + conversationId);
                auditHandoff("KF_SESSION_AI_RESUME", externalUserId,
                        "change_type=2（接待成员交还 AI，恢复自动应答）");
                monitor.serviceStateSyncSucceeded();
                return;
            }
            if (changeType == 1 || changeType == 2 || changeType == 4) {
                conversationService.setStatus(conversationId, "human");
                // 企微成员为准：人工接待归属记企微接待成员 userid（W26 决策），质检/报表据此归属
                conversationService.setServicer(conversationId, newServicerUserId);
                stateStore.removeValue(PENDING_HANDOFF_PREFIX + conversationId);
                auditHandoff("KF_SESSION_HUMAN", externalUserId,
                        "change_type=" + changeType + " servicer_hash="
                                + maskedExternalId(newServicerUserId));
                pushServicerProfile(conversationId, newServicerUserId);
            } else if (changeType == 3) {
                if (humanHold) {
                    // 人工粘性：接待结束后 AI 不自动恢复，后续消息仍由该接待成员在企微里处理；
                    // 只有接待成员主动把会话转回智能助手（上面的 changeType=2 分支）才恢复自动应答。
                    auditHandoff("KF_SESSION_HOLD", externalUserId,
                            "change_type=3 人工粘性保持，AI 不自动恢复");
                } else {
                    conversationService.setStatus(conversationId, "closed");
                    auditHandoff("KF_SESSION_CLOSED", externalUserId,
                            "change_type=3 servicer_hash=" + maskedExternalId(oldServicerUserId));
                }
                stateStore.removeValue(PENDING_HANDOFF_PREFIX + conversationId);
            } else {
                // 未知变更类型（多为企微协议新增枚举）：跳过而不是抛错——抛错会让同步游标
                // 永久卡在该事件上，阻塞整个客服号的后续消息处理。
                log.warn("[Kf] 未知企微会话变更类型 {}，跳过该事件", changeType);
                monitor.serviceStateSyncFailed("unknown change_type=" + changeType);
            }
            monitor.serviceStateSyncSucceeded();
        } catch (Exception e) {
            log.error("[Kf] 同步会话状态失败 客户标识={}: {}",
                    maskedExternalId(externalUserId), e.getMessage());
            monitor.serviceStateSyncFailed(e.getMessage());
            throw new IllegalStateException("同步企业微信客户端会话状态失败", e);
        }
    }

    // ==================== 主动发送（kf/send_msg） ====================

    /** 当前企业回调内发送文本；禁止无企业上下文调用。 */
    private boolean sendText(String externalUserId, String openKfId, String content) {
        return sendText(requireTenantContext(), externalUserId, openKfId, content);
    }

    public boolean sendText(long tenantId, String externalUserId, String openKfId, String content) {
        if (tenantId <= 0 || tenants == null) {
            monitor.outboundFailed("企微会话缺少企业归属");
            return false;
        }
        WeComTenantService.TenantCredentials tenant = tenants.requireTenantById(tenantId, true);
        return sendText(tenant, externalUserId, openKfId, content);
    }

    private boolean sendText(WeComTenantService.TenantCredentials tenant, String externalUserId,
                             String openKfId, String content) {
        if (!tokenService.isConfigured(tenant)) {
            log.warn("[Kf] 企业凭证未配置或企业已停用，跳过发送，正文长度={}",
                    content == null ? 0 : content.length());
            monitor.outboundFailed("企业企微 corpid/secret 未配置或企业已停用");
            return false;
        }
        try {
            String targetKfId = requireOpenKfId(openKfId);
            apiClient.sendText(tenant, externalUserId, targetKfId, content);
            monitor.outboundAccepted();
            return true;
        } catch (Exception e) {
            log.error("[Kf] send_msg 失败: {}", e.getMessage());
            monitor.outboundFailed(e.getMessage());
            return false;
        }
    }

    // ==================== 媒体下载（图片/语音） ====================

    /** 下载企微媒体（media/get_media）：图片/语音识别前先取字节 */
    byte[] getMedia(String mediaId) throws Exception {
        return apiClient.downloadMedia(requireTenantContext(), mediaId);
    }

    // ==================== 转人工（客户画像摘要，实施方案 4.3） ====================

    /**
     * 把企微会话转入企业微信客户端的待接入池。先查询平台状态保证失败重试幂等；
     * 若成员已接入则只同步为 human，不重复调用 trans。
     */
    private HandoffResult ensureQueuedInWeCom(String conversationId, String externalUserId,
                                              String callbackOpenKfId, String sourceMsgId) {
        String targetKfId = requireOpenKfId(callbackOpenKfId);
        String pendingKey = PENDING_HANDOFF_PREFIX + conversationId;
        if (sourceMsgId != null && !sourceMsgId.isBlank()) {
            stateStore.putValue(pendingKey, sourceMsgId);
        }
        monitor.handoffRequested();
        try {
            WeComKfApiClient.ServiceStateResult current = null;
            monitor.serviceStateSyncStarted();
            try {
                current = apiClient.getServiceState(
                        requireTenantContext(), targetKfId, externalUserId);
                monitor.serviceStateSyncSucceeded();
            } catch (Exception e) {
                // 查询失败仍尝试 trans；平台会再次校验，避免短暂查询故障让客户永久卡住。
                monitor.serviceStateSyncFailed(e.getMessage());
                log.warn("[Kf] 转人工前查询会话状态失败，继续尝试转入待接入池: {}", e.getMessage());
            }
            if (current != null && current.serviceState() == 2) {
                conversationService.setStatus(conversationId, "need_human");
                stateStore.removeValue(pendingKey);
                monitor.handoffSucceeded();
                return new HandoffResult(2, "");
            }
            if (current != null && current.serviceState() == 3) {
                conversationService.setStatus(conversationId, "human");
                stateStore.removeValue(pendingKey);
                monitor.handoffSucceeded();
                return new HandoffResult(3, "");
            }

            WeComKfApiClient.ServiceStateResult transferred = apiClient.transferServiceState(
                    requireTenantContext(), targetKfId, externalUserId, 2, null);
            conversationService.setStatus(conversationId, "need_human");
            stateStore.removeValue(pendingKey);
            monitor.handoffSucceeded();
            auditHandoff("KF_HANDOFF_QUEUED", externalUserId, "service_state=2");
            return new HandoffResult(2, transferred.msgCode());
        } catch (Exception e) {
            conversationService.setStatus(conversationId, "need_human");
            monitor.handoffFailed(e.getMessage());
            auditHandoff("KF_HANDOFF_FAIL", externalUserId, e.getMessage());
            throw new IllegalStateException("转入企业微信客户端待接入池失败", e);
        }
    }

    private void auditHandoff(String action, String externalUserId, String detail) {
        try {
            String safeDetail = detail == null ? "" : detail.replaceAll("[\\r\\n\\t]+", " ");
            if (safeDetail.length() > 240) safeDetail = safeDetail.substring(0, 240) + "…";
            jdbc.update("INSERT INTO audit_log(action, object, operator) VALUES (?, ?, ?)",
                    action, "external_user_hash=" + maskedExternalId(externalUserId) + " " + safeDetail,
                    "wecom");
        } catch (Exception e) {
            log.warn("记录企微会话状态审计日志失败: {}", e.getMessage());
        }
    }

    private String maskedExternalId(String externalUserId) {
        if (externalUserId == null || externalUserId.isBlank()) return "unknown";
        return Integer.toHexString(externalUserId.hashCode());
    }

    // ==================== 工具 ====================

    private static String text(Element root, String path) {
        var nodes = root.getElementsByTagName(
                path.contains("/") ? path.substring(path.lastIndexOf('/') + 1) : path);
        if (nodes.getLength() == 0) return "";
        String v = nodes.item(0).getTextContent();
        return v == null ? "" : v.trim();
    }

    private WeComTenantService.TenantCredentials tenantContext() {
        return activeTenant.get();
    }

    private WeComTenantService.KfAccount accountContext(String openKfId) {
        if (tenants == null) throw new IllegalStateException("企业微信接入服务不可用");
        WeComTenantService.TenantCredentials tenant = requireTenantContext();
        return tenants.requireEnabledAccount(tenant.id(), requireOpenKfId(openKfId));
    }

    private String tenantScopeKey() {
        return String.valueOf(requireTenantContext().id());
    }

    private WeComTenantService.TenantCredentials requireTenantContext() {
        WeComTenantService.TenantCredentials tenant = tenantContext();
        requireTenant(tenant);
        return tenant;
    }

    private static void requireTenant(WeComTenantService.TenantCredentials tenant) {
        if (tenant == null || tenant.id() <= 0 || !tenant.enabled()) {
            throw new IllegalStateException("企微操作必须绑定已启用的企业");
        }
    }

    private static String requireOpenKfId(String openKfId) {
        if (openKfId == null || openKfId.isBlank()) {
            throw new IllegalArgumentException("企微操作必须指定客服号 open_kfid");
        }
        return openKfId.trim();
    }

    private <T> T inTenant(WeComTenantService.TenantCredentials tenant,
                           java.util.function.Supplier<T> action) {
        requireTenant(tenant);
        WeComTenantService.TenantCredentials previous = activeTenant.get();
        activeTenant.set(tenant);
        try {
            return action.get();
        } finally {
            if (previous == null) activeTenant.remove();
            else activeTenant.set(previous);
        }
    }

    private com.example.aics.chat.ChatPrompt scopedPrompt(
            WeComTenantService.KfAccount account, com.example.aics.chat.ChatPrompt prompt) {
        if (account == null || account.businessScope() == null || account.businessScope().isBlank()) {
            return prompt;
        }
        return prompt.prependSystem("【当前微信客服号业务范围】\n"
                + account.businessScope().trim()
                + "\n只能按该范围回答，不得混用其他企业或客服号的信息。");
    }

    private static Double nullableDouble(JsonNode value) {
        return value == null || value.isNull() || !value.isNumber() ? null : value.asDouble();
    }
}
