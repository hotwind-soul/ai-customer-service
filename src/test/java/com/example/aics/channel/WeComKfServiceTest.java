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
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

class WeComKfServiceTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private ChatEngine chatEngine;
    private JdbcTemplate jdbc;
    private WeComImageSender imageSender;
    private WeComKfApiClient apiClient;
    private StateStore stateStore;
    private ConfigService config;
    private ConversationService conversationService;
    private WeComKfTokenService tokenService;
    private WeComRuntimeMonitor monitor;
    private ConversationTurnGuard turnGuard;
    private WeComTenantService tenantService;
    private WeComTenantService.TenantCredentials defaultTenant;
    private WeComKfService service;

    @BeforeEach
    void setUp() {
        chatEngine = mock(ChatEngine.class);
        when(chatEngine.finalizeAnswer(any(), anyString())).thenAnswer(invocation -> invocation.getArgument(1));
        jdbc = mock(JdbcTemplate.class);
        imageSender = mock(WeComImageSender.class);
        apiClient = mock(WeComKfApiClient.class);
        stateStore = mock(StateStore.class);
        config = mock(ConfigService.class);
        conversationService = mock(ConversationService.class);
        tokenService = mock(WeComKfTokenService.class);
        monitor = mock(WeComRuntimeMonitor.class);
        tenantService = mock(WeComTenantService.class);
        defaultTenant = tenant(1L, "tenant-a");
        when(tenantService.requireEnabledAccount(1L, "kf-callback"))
                .thenReturn(new WeComTenantService.KfAccount(
                        11L, 1L, "kf-callback", "默认客服", "", "", true));
        turnGuard = mockTurnGuard();
        service = new WeComKfService(
                chatEngine,
                conversationService,
                stateStore,
                jdbc,
                tokenService,
                mock(OcrService.class),
                mock(WeComAsrService.class),
                mock(ModelGateway.class),
                config,
                imageSender,
                apiClient,
                turnGuard,
                monitor,
                tenantService);
        // 生产域名兜底已移除（P1-2 fail-fast），测试显式注入公网地址供图文卡片断言使用。
        service.publicBaseUrl = "https://ai.zhenmeiqq.online";
    }

    private ConversationTurnGuard mockTurnGuard() {
        ConversationTurnGuard guard = mock(ConversationTurnGuard.class);
        when(guard.acquire(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(mock(ConversationTurnGuard.Lease.class));
        return guard;
    }

    @Test
    void acceptedWeComTextRecordsTimingBeforeImages() throws Exception {
        when(tokenService.isConfigured(defaultTenant)).thenReturn(true);
        ResponseTiming timing = ResponseTiming.start();
        service.deliverAnswer(defaultTenant, "external-user", "kf-callback",
                new WeComKfService.KfAnswer("业务回复", "conv-1", List.of(), List.of(), "", timing));
        var order = inOrder(apiClient, conversationService);
        order.verify(apiClient).sendText(defaultTenant, "external-user", "kf-callback", "业务回复");
        order.verify(conversationService).recordFirstResponse("conv-1", timing);
    }

    @Test
    void rejectedWeComTextNeverProducesSuccessfulTiming() throws Exception {
        when(tokenService.isConfigured(defaultTenant)).thenReturn(true);
        doThrow(new IllegalStateException("send failed")).when(apiClient)
                .sendText(defaultTenant, "external-user", "kf-callback", "业务回复");
        assertThat(service.deliverAnswer(defaultTenant, "external-user", "kf-callback",
                new WeComKfService.KfAnswer("业务回复", "conv-1", List.of(), List.of(), "", ResponseTiming.start())))
                .isFalse();
        verify(conversationService, never()).recordFirstResponse(anyString(), any());
    }

    @Test
    void onlySuccessfullyAcceptedImagesAreMarkedAsSent() {
        Map<String, Object> delivered = Map.of("id", 11L, "name", "效果图");
        Map<String, Object> failed = Map.of("id", 12L, "name", "海报");
        when(imageSender.send(defaultTenant, "external-user", "kf-callback", 11L)).thenReturn(true);
        when(imageSender.send(defaultTenant, "external-user", "kf-callback", 12L)).thenReturn(false);
        when(tokenService.isConfigured(defaultTenant)).thenReturn(true);

        service.deliverAnswer(defaultTenant, "external-user", "kf-callback",
                new WeComKfService.KfAnswer("给您发图片", "conv-1", List.of(delivered, failed)));

        verify(chatEngine).markImagesSent("conv-1", List.of(delivered));
        verify(jdbc).update(contains("INSERT INTO audit_log"),
                eq("KF_IMAGE_SEND_FAIL"), contains("image_id=12"), eq("wecom"));
    }

    @Test
    void oneAiTurnNeverSendsMoreThanFiveWeComMessages() throws Exception {
        List<Map<String, Object>> images = java.util.stream.LongStream.rangeClosed(1, 7)
                .mapToObj(id -> Map.<String, Object>of("id", id, "name", "图片" + id)).toList();
        when(tokenService.isConfigured(defaultTenant)).thenReturn(true);
        when(imageSender.send(eq(defaultTenant), eq("external-user"), eq("kf-callback"),
                org.mockito.ArgumentMatchers.anyLong())).thenReturn(true);

        service.deliverAnswer(defaultTenant, "external-user", "kf-callback",
                new WeComKfService.KfAnswer("项目介绍", "conv-1", images));

        verify(apiClient).sendText(defaultTenant, "external-user", "kf-callback", "项目介绍");
        verify(imageSender, times(4)).send(eq(defaultTenant), eq("external-user"), eq("kf-callback"),
                org.mockito.ArgumentMatchers.anyLong());
        verify(chatEngine).markImagesSent(eq("conv-1"), org.mockito.ArgumentMatchers.argThat(sent -> sent.size() == 4));
        verify(monitor).messageLimitTruncated();
    }

    @Test
    void addressCardUsesPublicThumbnailAndNativeLinkMessage() throws Exception {
        LinkCard card = new LinkCard("昆明首渡店", "昆明市官渡区星耀路99号",
                "https://apis.map.qq.com/uri/v1/marker?marker=test",
                "/assets/tencent-map-card.svg");
        when(tokenService.isConfigured(defaultTenant)).thenReturn(true);

        service.deliverAnswer(defaultTenant, "external-user", "kf-callback",
                new WeComKfService.KfAnswer("给您找到门店了", "conv-1", List.of(),
                        List.of(card), ""));

        verify(apiClient).sendText(defaultTenant, "external-user", "kf-callback", "给您找到门店了");
        verify(apiClient).sendLink(eq(defaultTenant), eq("external-user"), eq("kf-callback"),
                org.mockito.ArgumentMatchers.argThat(sent -> sent.thumbnailUrl().equals(
                        "https://ai.zhenmeiqq.online/assets/tencent-map-card.svg")));
    }

    @Test
    void failedAddressCardFallsBackToClickableTextLink() throws Exception {
        LinkCard card = new LinkCard("昆明首渡店", "昆明市官渡区星耀路99号",
                "https://apis.map.qq.com/uri/v1/marker?marker=test",
                "/assets/tencent-map-card.svg");
        when(tokenService.isConfigured(defaultTenant)).thenReturn(true);
        org.mockito.Mockito.doThrow(new IllegalStateException("link unsupported"))
                .when(apiClient).sendLink(eq(defaultTenant), eq("external-user"),
                        eq("kf-callback"), org.mockito.ArgumentMatchers.any(LinkCard.class));

        service.deliverAnswer(defaultTenant, "external-user", "kf-callback",
                new WeComKfService.KfAnswer("给您找到门店了", "conv-1", List.of(),
                        List.of(card), ""));

        verify(apiClient).sendText(defaultTenant, "external-user", "kf-callback", "给您找到门店了");
        verify(apiClient).sendText(defaultTenant, "external-user", "kf-callback",
                "昆明首渡店（腾讯地图）：https://apis.map.qq.com/uri/v1/marker?marker=test");
        verify(monitor).outboundFailed("link unsupported");
    }

    @Test
    void callbackUsesSyncMsgAndWelcomeEventCode() throws Exception {
        JsonNode enterSession = JSON.readTree("""
                {
                  "msgid":"event-1","send_time":1720000000,"origin":4,"msgtype":"event",
                  "event":{"event_type":"enter_session","open_kfid":"kf-callback",
                           "external_userid":"external-user","welcome_code":"welcome-code"}
                }
                """);
        when(apiClient.syncMessages(defaultTenant, "callback-token", "kf-callback", ""))
                .thenReturn(new WeComKfApiClient.SyncPage(List.of(enterSession), "", false));
        when(config.get("welcome.message", "")).thenReturn("欢迎咨询");
        when(stateStore.setNx("kf:msg:1:event-1", "1", 3 * 24 * 3600L)).thenReturn(true);
        when(stateStore.setNx("kf:welcome:1:kf-callback:external-user", "1", 24 * 3600L)).thenReturn(true);

        service.handleEvent(defaultTenant, callbackXml("callback-token", "kf-callback"));

        verify(apiClient).syncMessages(defaultTenant, "callback-token", "kf-callback", "");
        verify(apiClient).sendTextOnEvent(defaultTenant, "welcome-code", "欢迎咨询");
    }

    @Test
    void syncMsgFollowsCursorUntilHasMoreIsFalse() throws Exception {
        when(apiClient.syncMessages(defaultTenant, "callback-token", "kf-callback", ""))
                .thenReturn(new WeComKfApiClient.SyncPage(List.of(), "cursor-2", true));
        when(apiClient.syncMessages(defaultTenant, "callback-token", "kf-callback", "cursor-2"))
                .thenReturn(new WeComKfApiClient.SyncPage(List.of(), "", false));

        service.handleEvent(defaultTenant, callbackXml("callback-token", "kf-callback"));

        verify(apiClient).syncMessages(defaultTenant, "callback-token", "kf-callback", "");
        verify(apiClient).syncMessages(defaultTenant, "callback-token", "kf-callback", "cursor-2");
        verify(stateStore).putValue("kf:cursor:1:kf-callback", "cursor-2");
    }

    @Test
    void syncMsgContinuesFromPersistedCursor() throws Exception {
        when(stateStore.getValue("kf:cursor:1:kf-callback")).thenReturn("saved-cursor");
        when(apiClient.syncMessages(defaultTenant, "callback-token", "kf-callback", "saved-cursor"))
                .thenReturn(new WeComKfApiClient.SyncPage(List.of(), "new-cursor", false));

        service.handleEvent(defaultTenant, callbackXml("callback-token", "kf-callback"));

        verify(apiClient).syncMessages(defaultTenant, "callback-token", "kf-callback", "saved-cursor");
        verify(stateStore).putValue("kf:cursor:1:kf-callback", "new-cursor");
    }

    @Test
    void nonCustomerMessagesDoNotEnterAiPipeline() throws Exception {
        JsonNode outgoing = JSON.readTree("""
                {"msgid":"out-1","open_kfid":"kf-callback","external_userid":"external-user",
                 "send_time":1720000000,"origin":4,"msgtype":"text","text":{"content":"客服回复"}}
                """);
        when(stateStore.setNx("kf:msg:1:out-1", "1", 3 * 24 * 3600L)).thenReturn(true);

        service.dispatchSyncedItem(defaultTenant, outgoing);

        verifyNoInteractions(chatEngine);
    }

    @Test
    void syncMessageJsonNormalizesToChannelMessage() throws Exception {
        JsonNode text = JSON.readTree("""
                {"msgid":"msg-1","external_userid":"external-user","send_time":1720000000,
                 "origin":3,"msgtype":"text","text":{"content":"想了解淡斑"}}
                """);

        MessageInbound inbound = service.normalize(text);

        org.junit.jupiter.api.Assertions.assertEquals("external-user", inbound.externalUserId());
        org.junit.jupiter.api.Assertions.assertEquals("想了解淡斑", inbound.content());
        org.junit.jupiter.api.Assertions.assertEquals(1720000000000L, inbound.timestampMs());
    }

    @Test
    void customerTextFromSyncMsgRunsSharedAiPipelineAndSendsReply() throws Exception {
        JsonNode text = JSON.readTree("""
                {"msgid":"msg-ai-1","open_kfid":"kf-callback","external_userid":"external-user",
                 "send_time":1720000000,"origin":3,"msgtype":"text","text":{"content":"想了解淡斑"}}
                """);
        when(stateStore.setNx("kf:msg:1:msg-ai-1", "1", 3 * 24 * 3600L)).thenReturn(true);
        when(jdbc.queryForList(contains("SELECT user_id FROM external_user"),
                eq(String.class), eq(1L), eq("external-user"))).thenReturn(List.of("user-1"));
        when(conversationService.findOrCreateKfConversation("user-1", 1L, 11L)).thenReturn("conv-1");
        when(conversationService.getRoute("conv-1")).thenReturn(Map.of("status", "active"));
        ChatEngine.Prepared prepared = new ChatEngine.Prepared(
                "conv-1", false, null, List.of(), List.of(), "这是淡斑介绍",
                List.of(), List.of(), null, "FAQ", null);
        when(chatEngine.prepareForWeCom("想了解淡斑", "conv-1", "user-1", null, null))
                .thenReturn(prepared);
        when(tokenService.isConfigured(defaultTenant)).thenReturn(true);

        service.dispatchSyncedItem(defaultTenant, text);

        verify(chatEngine).persist(prepared, "想了解淡斑", "这是淡斑介绍", "FAQ");
        verify(apiClient).sendText(defaultTenant, "external-user", "kf-callback", "这是淡斑介绍");
    }

    @Test
    void queuedKfConversationRecordsMessageWithoutInvokingAi() throws Exception {
        when(jdbc.queryForList(contains("SELECT user_id FROM external_user"),
                eq(String.class), eq(1L), eq("external-user"))).thenReturn(List.of("user-1"));
        when(conversationService.findOrCreateKfConversation("user-1", 1L, 11L)).thenReturn("conv-1");
        when(conversationService.getRoute("conv-1")).thenReturn(Map.of("status", "need_human"));
        when(apiClient.getServiceState(defaultTenant, "kf-callback", "external-user"))
                .thenReturn(new WeComKfApiClient.ServiceStateResult(2, "", ""));

        WeComKfService.KfAnswer answer = service.answer(defaultTenant, new MessageInbound(
                "external-user", "msg-1", "text", "补充：周五有时间",
                null, null, null, null, 0), "kf-callback");

        org.assertj.core.api.Assertions.assertThat(answer.text()).isNull();
        verify(conversationService).saveMessage("conv-1", "user", "补充：周五有时间", null);
        verifyNoInteractions(chatEngine);
    }

    @Test
    void aiHandoffTransfersToWeComWaitingPoolAndUsesEventResponseCode() throws Exception {
        when(jdbc.queryForList(contains("SELECT user_id FROM external_user"),
                eq(String.class), eq(1L), eq("external-user"))).thenReturn(List.of("user-1"));
        when(conversationService.findOrCreateKfConversation("user-1", 1L, 11L)).thenReturn("conv-1");
        when(conversationService.getRoute("conv-1"))
                .thenReturn(Map.of("status", "active")).thenReturn(Map.of("status", "need_human"));
        ChatEngine.Prepared prepared = new ChatEngine.Prepared(
                "conv-1", false, null, List.of(), List.of(), null,
                List.of(), List.of(), "正在转人工", "AGENT", null);
        when(chatEngine.prepareForWeCom("转人工", "conv-1", "user-1", null, null))
                .thenReturn(prepared);
        when(apiClient.getServiceState(defaultTenant, "kf-callback", "external-user"))
                .thenReturn(new WeComKfApiClient.ServiceStateResult(1, "", ""));
        when(apiClient.transferServiceState(defaultTenant, "kf-callback", "external-user", 2, null))
                .thenReturn(new WeComKfApiClient.ServiceStateResult(2, "", "handoff-code"));

        WeComKfService.KfAnswer answer = service.answer(defaultTenant, new MessageInbound(
                "external-user", "msg-handoff", "text", "转人工",
                null, null, null, null, 0), "kf-callback");
        service.deliverAnswer(defaultTenant, "external-user", "kf-callback", answer);

        verify(apiClient).transferServiceState(defaultTenant, "kf-callback", "external-user", 2, null);
        verify(apiClient).sendTextOnEvent(defaultTenant, "handoff-code", "正在转人工");
        verify(apiClient, org.mockito.Mockito.never())
                .sendText(eq(defaultTenant), eq("external-user"), eq("kf-callback"),
                        org.mockito.ArgumentMatchers.anyString());
        verify(monitor).handoffSucceeded();
    }

    @Test
    void sessionStatusChangeFromWeComClientSynchronizesHumanAndClosed() throws Exception {
        when(conversationService.findOpenKfConversation(
                "external-user", "kf-callback", 1L, 11L))
                .thenReturn("conv-1");
        JsonNode accepted = JSON.readTree("""
                {"msgid":"event-human","origin":4,"msgtype":"event",
                 "event":{"event_type":"session_status_change","open_kfid":"kf-callback",
                          "external_userid":"external-user","change_type":1,
                          "new_servicer_userid":"zhangsan"}}
                """);
        JsonNode closed = JSON.readTree("""
                {"msgid":"event-closed","origin":4,"msgtype":"event",
                 "event":{"event_type":"session_status_change","open_kfid":"kf-callback",
                          "external_userid":"external-user","change_type":3,
                          "old_servicer_userid":"zhangsan"}}
                """);
        when(stateStore.setNx("kf:msg:1:event-human", "1", 3 * 24 * 3600L)).thenReturn(true);
        when(stateStore.setNx("kf:msg:1:event-closed", "1", 3 * 24 * 3600L)).thenReturn(true);

        service.dispatchSyncedItem(defaultTenant, accepted);
        service.dispatchSyncedItem(defaultTenant, closed);

        verify(conversationService).setStatus("conv-1", "human");
        verify(conversationService).setStatus("conv-1", "closed");
        verify(stateStore, times(2)).removeValue("kf:handoff:pending:conv-1");
        verify(monitor, times(2)).serviceStateSyncSucceeded();
    }

    @Test
    void sessionStatusChangeStickyHumanKeepsConversationWithServicer() throws Exception {
        // 人工粘性（agent.humanHoldAfterHandoff=true）：接待结束事件不再把会话交回 AI，
        // 状态保持 human——后续客户消息仍由该接待成员在企微里处理。
        when(config.getBoolean("agent.humanHoldAfterHandoff", true)).thenReturn(true);
        when(conversationService.findOpenKfConversation(
                "external-user", "kf-callback", 1L, 11L))
                .thenReturn("conv-1");
        JsonNode ended = JSON.readTree("""
                {"msgid":"event-hold","origin":4,"msgtype":"event",
                 "event":{"event_type":"session_status_change","open_kfid":"kf-callback",
                          "external_userid":"external-user","change_type":3,
                          "old_servicer_userid":"zhangsan"}}
                """);
        when(stateStore.setNx("kf:msg:1:event-hold", "1", 3 * 24 * 3600L)).thenReturn(true);

        service.dispatchSyncedItem(defaultTenant, ended);

        verify(conversationService, never()).setStatus(anyString(), eq("closed"));
        verify(conversationService, never()).setStatus(anyString(), eq("active"));
        verify(jdbc).update(eq("INSERT INTO audit_log(action, object, operator) VALUES (?, ?, ?)"),
                eq("KF_SESSION_HOLD"), contains("人工粘性"), any());
        verify(stateStore).removeValue("kf:handoff:pending:conv-1");
        verify(monitor).serviceStateSyncSucceeded();
    }

    @Test
    void sessionStatusChangeServicerAssignedRecordsWeComAttribution() throws Exception {
        when(conversationService.findOpenKfConversation(
                "external-user", "kf-callback", 1L, 11L))
                .thenReturn("conv-1");
        JsonNode assigned = JSON.readTree("""
                {"msgid":"event-assign","origin":4,"msgtype":"event",
                 "event":{"event_type":"session_status_change","open_kfid":"kf-callback",
                          "external_userid":"external-user","change_type":2,
                          "new_servicer_userid":"lisi"}}
                """);
        when(stateStore.setNx("kf:msg:1:event-assign", "1", 3 * 24 * 3600L)).thenReturn(true);

        service.dispatchSyncedItem(defaultTenant, assigned);

        verify(conversationService).setStatus("conv-1", "human");
        // 企微成员为准：人工接待归属记企微接待成员 userid（W26 决策）
        verify(conversationService).setServicer("conv-1", "lisi");
        verify(monitor).serviceStateSyncSucceeded();
    }

    @Test
    void sessionStatusChangeServicerReturnsToAiResumesAutoAnswer() throws Exception {
        when(conversationService.findOpenKfConversation(
                "external-user", "kf-callback", 1L, 11L))
                .thenReturn("conv-1");
        JsonNode returned = JSON.readTree("""
                {"msgid":"event-resume","origin":4,"msgtype":"event",
                 "event":{"event_type":"session_status_change","open_kfid":"kf-callback",
                          "external_userid":"external-user","change_type":2}}
                """);
        when(stateStore.setNx("kf:msg:1:event-resume", "1", 3 * 24 * 3600L)).thenReturn(true);

        service.dispatchSyncedItem(defaultTenant, returned);

        // 客服主动交还 AI：恢复自动应答，且不再记录接待归属
        verify(conversationService).setStatus("conv-1", "active");
        verify(conversationService, never()).setStatus(anyString(), eq("human"));
        verify(conversationService, never()).setServicer(anyString(), anyString());
        verify(jdbc).update(eq("INSERT INTO audit_log(action, object, operator) VALUES (?, ?, ?)"),
                eq("KF_SESSION_AI_RESUME"), contains("接待成员交还"), any());
    }

    @Test
    void sameExternalUserIdInDifferentTenantsMapsToDifferentCustomers() {
        WeComTenantService.TenantCredentials first = tenant(1L, "tenant-a");
        WeComTenantService.TenantCredentials second = tenant(2L, "tenant-b");
        String select = "SELECT user_id FROM external_user WHERE tenant_id=? AND external_userid=?";
        when(jdbc.queryForList(select, String.class, 1L, "same-external"))
                .thenReturn(List.of()).thenReturn(List.of("user-a"));
        when(jdbc.queryForList(select, String.class, 2L, "same-external"))
                .thenReturn(List.of()).thenReturn(List.of("user-b"));

        String userA = service.mapUserId(first, "same-external");
        String userB = service.mapUserId(second, "same-external");

        assertThat(userA).isEqualTo("user-a");
        assertThat(userB).isEqualTo("user-b");
        verify(jdbc).update(contains("INSERT INTO external_user"), eq(1L), eq("same-external"),
                org.mockito.ArgumentMatchers.startsWith("u_kf_"));
        verify(jdbc).update(contains("INSERT INTO external_user"), eq(2L), eq("same-external"),
                org.mockito.ArgumentMatchers.startsWith("u_kf_"));
    }

    @Test
    void generatedKfCustomerIdKeepsFullUuidEntropy() {
        WeComTenantService.TenantCredentials tenant = tenant(1L, "tenant-a");
        String select = "SELECT user_id FROM external_user WHERE tenant_id=? AND external_userid=?";
        when(jdbc.queryForList(select, String.class, 1L, "new-external"))
                .thenReturn(List.of()).thenReturn(List.of());

        String userId = service.mapUserId(tenant, "new-external");

        assertThat(userId).matches("u_kf_[0-9a-f]{32}");
    }

    @Test
    void sameOpenKfIdInDifferentTenantsUsesIndependentCursors() throws Exception {
        WeComTenantService tenantService = mock(WeComTenantService.class);
        service = new WeComKfService(chatEngine, conversationService, stateStore, jdbc, tokenService,
                mock(OcrService.class), mock(WeComAsrService.class), mock(ModelGateway.class), config,
                imageSender, apiClient, turnGuard, monitor, tenantService);
        WeComTenantService.TenantCredentials first = tenant(1L, "tenant-a");
        WeComTenantService.TenantCredentials second = tenant(2L, "tenant-b");
        when(tenantService.requireEnabledAccount(1L, "shared-kf"))
                .thenReturn(new WeComTenantService.KfAccount(11L, 1L, "shared-kf", "A 客服", "", "", true));
        when(tenantService.requireEnabledAccount(2L, "shared-kf"))
                .thenReturn(new WeComTenantService.KfAccount(21L, 2L, "shared-kf", "B 客服", "", "", true));
        when(apiClient.syncMessages(first, "token-a", "shared-kf", ""))
                .thenReturn(new WeComKfApiClient.SyncPage(List.of(), "cursor-a", false));
        when(apiClient.syncMessages(second, "token-b", "shared-kf", ""))
                .thenReturn(new WeComKfApiClient.SyncPage(List.of(), "cursor-b", false));

        service.handleEvent(first, callbackXml("token-a", "shared-kf"));
        service.handleEvent(second, callbackXml("token-b", "shared-kf"));

        verify(stateStore).putValue("kf:cursor:1:shared-kf", "cursor-a");
        verify(stateStore).putValue("kf:cursor:2:shared-kf", "cursor-b");
    }

    private WeComTenantService.TenantCredentials tenant(long id, String code) {
        return new WeComTenantService.TenantCredentials(id, code, code, "corp-" + code,
                "secret-" + code, "token-" + code,
                "jWmYm7qr5nMoAUwZRjGtBxmz3KA1tkAj3ykkR6q2B2C", true);
    }

    private String callbackXml(String token, String openKfId) {
        return "<xml><MsgType>event</MsgType><Event>kf_msg_or_event</Event><Token>"
                + token + "</Token><OpenKfId>" + openKfId + "</OpenKfId></xml>";
    }
}
