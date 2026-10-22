package com.example.aics.channel;

/**
 * 渠道中立入站消息（channel 包骨架，2026-08-07）。
 *
 * 企微 Kf 回调的 text/voice/location/image 事件统一归一化成这个模型，
 * 下游（ChatEngine 完整应答路由 / 转人工）只认 MessageInbound，不感知具体渠道。
 * 语音/图片等"先转后答"的字段（asrText / visionText）由各 Service 处理时回填。
 */
public record MessageInbound(
        String externalUserId,   // Kf 消息的 touser（EXTERNAL_USERID），即渠道客户唯一 ID
        String msgId,            // 消息 ID（回调去重用）
        String type,             // text / voice / location / image / video
        String content,          // text: 文字内容；location: 无（见 lat/lng）；其他: 媒体 mediaId
        Double lat,              // location 事件带经纬度
        Double lng,
        String asrText,          // voice：腾讯云 ASR 识别后的文字（异步回填）
        String visionText,       // image：qwen-vl-max 视觉理解后的文字（异步回填）
        long timestampMs         // 消息时间（毫秒）
) {

    /** 最终用于问答的文本：text 用原文，voice 用识别结果，image 用视觉理解结果 */
    public String answerText() {
        if ("text".equals(type)) return content;
        if ("voice".equals(type)) return asrText;
        if ("image".equals(type)) return visionText;
        return content;   // location/video 由调用方按兜底话术处理
    }
}
