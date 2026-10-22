package com.example.aics.agent;

import com.example.aics.channel.WeComImageSender;
import com.example.aics.channel.WeComKfService;
import com.example.aics.channel.WeComTenantService;
import com.example.aics.chat.ConversationService;
import com.example.aics.chat.SessionRegistry;
import com.example.aics.image.ImageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/** 将本地坐席回复按会话渠道投递到网页 WebSocket 或企微客服。 */
@Service
public class HumanReplyService {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final ConversationService conversations;
    private final SessionRegistry sessions;
    private final WeComKfService weCom;
    private final WeComImageSender weComImages;
    private final WeComTenantService tenants;
    private final ImageService imageService;
    private final AgentAccountService agents;

    /** 纯图消息在消息记录中的占位文本。 */
    public static final String IMAGE_PLACEHOLDER = "[图片]";

    public HumanReplyService(ConversationService conversations, SessionRegistry sessions,
                             WeComKfService weCom, WeComImageSender weComImages,
                             WeComTenantService tenants, ImageService imageService,
                             AgentAccountService agents) {
        this.conversations = conversations;
        this.sessions = sessions;
        this.weCom = weCom;
        this.weComImages = weComImages;
        this.tenants = tenants;
        this.imageService = imageService;
        this.agents = agents;
    }

    public Delivery send(long agentId, String conversationId, String content) {
        return send(agentId, conversationId, content, List.of());
    }

    /** 坐席回复（可附带 0..n 张图片）：web 渠道实时推送 + 落附件；企微渠道转临时媒体直发。 */
    public Delivery send(long agentId, String conversationId, String content,
                         List<Map<String, Object>> images) {
        List<Map<String, Object>> safeImages = images == null ? List.of() : images;
        Map<String, Object> route = conversations.getRoute(conversationId);
        String channel = String.valueOf(route.getOrDefault("channel", "web"));
        if ("kf".equals(channel)) {
            String externalUserId = string(route.get("external_userid"));
            String openKfId = string(route.get("open_kfid"));
            long tenantId = route.get("tenant_id") instanceof Number number ? number.longValue() : 0L;
            if (tenantId <= 0) {
                return new Delivery(false, false, "企微会话缺少企业归属，旧版单企业会话已停止发送");
            }
            var tenant = tenants.requireTenantById(tenantId, true);
            // 图片先发：任何一张失败视为整条失败，避免"图丢了只剩文字"造成误解
            for (Map<String, Object> image : safeImages) {
                try {
                    boolean sent = weComImages.sendUploaded(tenant, externalUserId, openKfId,
                            imageService.loadUploadedByName(fileNameOf(image)));
                    if (!sent) {
                        return new Delivery(false, false, "企微图片发送失败，请稍后重试");
                    }
                } catch (Exception e) {
                    return new Delivery(false, false, "企微图片发送失败，请稍后重试");
                }
            }
            // 纯图消息用占位文本标记，不把占位符原样发给企微客户
            boolean textOnly = content == null || content.isBlank() || IMAGE_PLACEHOLDER.equals(content);
            if (!textOnly) {
                boolean sent = weCom.sendText(tenantId, externalUserId, openKfId, content);
                if (externalUserId.isBlank() || !sent) {
                    return new Delivery(false, false, "企微消息发送失败，请稍后重试");
                }
            }
            long messageId = conversations.saveMessage(conversationId, "agent", content, null);
            conversations.saveImageAttachments(messageId, safeImages);
            return new Delivery(true, true, null);
        }

        long messageId = conversations.saveMessage(conversationId, "agent", content, null);
        conversations.saveImageAttachments(messageId, safeImages);
        var message = JSON.createObjectNode();
        message.put("type", "agent");
        message.put("name", agents.getAgentName(agentId));
        message.put("content", content);
        if (!safeImages.isEmpty()) {
            message.set("images", JSON.valueToTree(safeImages));
        }
        boolean liveDelivered = sessions.sendToCustomer(conversationId, message.toString());
        return new Delivery(true, liveDelivered, null);
    }

    private String string(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String fileNameOf(Map<String, Object> image) {
        String url = image.get("url") == null ? "" : String.valueOf(image.get("url"));
        int slash = url.lastIndexOf('/');
        return slash >= 0 ? url.substring(slash + 1) : url;
    }

    public record Delivery(boolean accepted, boolean liveDelivered, String error) {}
}
