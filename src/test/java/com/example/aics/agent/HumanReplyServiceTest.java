package com.example.aics.agent;

import com.example.aics.channel.WeComImageSender;
import com.example.aics.channel.WeComKfService;
import com.example.aics.channel.WeComTenantService;
import com.example.aics.chat.ConversationService;
import com.example.aics.chat.SessionRegistry;
import com.example.aics.image.ImageService;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class HumanReplyServiceTest {

    @Test
    void weComReplyUsesOriginalTenantAndKfAccount() {
        ConversationService conversations = mock(ConversationService.class);
        WeComKfService weCom = mock(WeComKfService.class);
        when(conversations.getRoute("conv-1")).thenReturn(Map.of(
                "channel", "kf", "tenant_id", 8L, "kf_account_id", 18L,
                "external_userid", "external-1", "open_kfid", "kf-18"));
        when(weCom.sendText(8L, "external-1", "kf-18", "人工回复"))
                .thenReturn(true);
        HumanReplyService service = new HumanReplyService(conversations,
                mock(SessionRegistry.class), weCom, mock(WeComImageSender.class),
                mock(WeComTenantService.class), mock(ImageService.class),
                mock(AgentAccountService.class));

        HumanReplyService.Delivery result = service.send(3L, "conv-1", "人工回复");

        assertThat(result.accepted()).isTrue();
        verify(weCom).sendText(8L, "external-1", "kf-18", "人工回复");
        verify(conversations).saveMessage(eq("conv-1"), eq("agent"), eq("人工回复"), isNull());
    }

    @Test
    void legacyWeComConversationWithoutTenantIsRejected() {
        ConversationService conversations = mock(ConversationService.class);
        WeComKfService weCom = mock(WeComKfService.class);
        when(conversations.getRoute("legacy-conv")).thenReturn(Map.of(
                "channel", "kf", "external_userid", "external-1", "open_kfid", "kf-18"));
        HumanReplyService service = new HumanReplyService(conversations,
                mock(SessionRegistry.class), weCom, mock(WeComImageSender.class),
                mock(WeComTenantService.class), mock(ImageService.class),
                mock(AgentAccountService.class));

        HumanReplyService.Delivery result = service.send(3L, "legacy-conv", "人工回复");

        assertThat(result.accepted()).isFalse();
        assertThat(result.error()).contains("缺少企业归属");
        verifyNoInteractions(weCom);
    }
}
