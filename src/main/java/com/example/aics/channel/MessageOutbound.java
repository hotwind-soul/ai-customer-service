package com.example.aics.channel;

/**
 * 渠道中立出站消息（channel 包骨架，2026-08-07）。
 * 给客户微信的回复统一封装：文本 / 图片（mediaId 或本地素材）。
 */
public record MessageOutbound(
        String type,     // text / image
        String content,  // text 内容；image 时为空（走 mediaId / mediaUrl）
        String mediaId,  // image：企微媒体 ID（先上传素材）
        String mediaUrl  // image：本地素材 URL（send_msg 前需先转 mediaId）
) {

    public static MessageOutbound text(String content) {
        return new MessageOutbound("text", content, null, null);
    }

    public static MessageOutbound image(String mediaId) {
        return new MessageOutbound("image", null, mediaId, null);
    }
}
