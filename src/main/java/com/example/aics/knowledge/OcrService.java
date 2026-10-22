package com.example.aics.knowledge;

import com.example.aics.ai.ModelGateway;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import org.springframework.stereotype.Service;

import java.util.Base64;

/**
 * 图片文字识别（OCR）：聊天记录截图 → 纯文字。
 *
 * 实现选型说明：没有接专门的 OCR 云服务（要多开账号多配密钥），
 * 而是复用已有的阿里云百炼 Key，调通义千问视觉模型 qwen-vl-max 读图——
 * 对中文聊天截图的识别率比传统 OCR 更高，还能自动理清对话顺序。
 * 费用：每张图约 2 分钱，知识库导入是低频操作，可忽略。
 * 模型调用统一走 ModelGateway（视觉模型出口唯一化，ADR-002）。
 */
@Service
public class OcrService {

    private final ModelGateway modelGateway;

    public OcrService(ModelGateway modelGateway) {
        this.modelGateway = modelGateway;
    }

    /**
     * 识别图片中的文字。
     * @param imageBytes 图片字节
     * @param mimeType   如 image/jpeg、image/png
     * @return 提取出的纯文字（保持对话顺序）
     */
    public String extractText(byte[] imageBytes, String mimeType) {
        String base64 = Base64.getEncoder().encodeToString(imageBytes);
        UserMessage message = UserMessage.from(
                TextContent.from(
                        "这是一张客服聊天记录截图。请逐行提取图中的所有文字，保持原有对话顺序，" +
                        "能区分说话人的用「客户：」「客服：」前缀。只输出文字内容，不要任何解释。"),
                ImageContent.from(base64, mimeType));
        return modelGateway.visionChat(message);
    }

    /**
     * 描述图片内容（企微客户发图场景，channel 包用）：客户发来任意图片（产品图/效果图/检查单等），
     * 视觉模型理解后转成文字描述，作为客户消息进 ChatEngine 上下文。
     * @return 图片内容描述文字
     */
    public String describeImage(byte[] imageBytes, String mimeType) {
        String base64 = Base64.getEncoder().encodeToString(imageBytes);
        UserMessage message = UserMessage.from(
                TextContent.from(
                        "请仔细看这张图片，用简洁的中文描述图片内容：图里是什么（产品/场景/文字/单据等）、" +
                        "有哪些关键信息。如果是文字/单据请把文字内容念出来。只说描述，不要任何评价。" +
                        "描述里不要出现「图片」「照片」「海报」「效果图」「看图」「发图」这些字样，" +
                        "用「画面」「影像资料」「单据内容」等表述代替。" +
                        "如果图片模糊、光线差、拍摄角度怪或你无法确认图里的关键内容，" +
                        "请在描述最前面写【不确定：原因】，再写下你能确认的部分；能确认时不要写【不确定】。"),
                ImageContent.from(base64, mimeType));
        return modelGateway.visionChat(message);
    }
}
