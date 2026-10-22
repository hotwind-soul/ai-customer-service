package com.example.aics.quality;

import com.example.aics.ai.ModelGateway;
import com.example.aics.config.AiConfigCatalog;
import com.example.aics.config.ConfigService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/** 管理员主动触发的可选 AI 复核；每次按管理员选择调用主模型或备用模型。 */
@Service
public class QualityJudgeService {
    private static final String PROMPT_SCHEMA_VERSION = "semantic-v3";
    private static final Logger log = LoggerFactory.getLogger(QualityJudgeService.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private final ModelGateway modelGateway;
    private final ConfigService config;

    public QualityJudgeService(ModelGateway modelGateway, ConfigService config) {
        this.modelGateway = modelGateway;
        this.config = config;
    }

    public List<ModelGateway.QualityJudgeModel> models() {
        return modelGateway.qualityJudgeModels();
    }

    public String modelName(String provider) {
        return models().stream()
                .filter(model -> model.provider().equals(provider))
                .map(ModelGateway.QualityJudgeModel::modelName)
                .findFirst()
                .orElse("");
    }

    /** 评测与客服回复的随机度分离，默认 0，且限制在低随机区间。 */
    public double temperature() {
        return Math.max(0.0, Math.min(config.getDouble("quality.judgeTemperature", 0.0), 0.2));
    }

    /** 记录评测结构版本和当前 Prompt 摘要，既可追溯又不把整段 Prompt 复制进历史表。 */
    public String promptVersion() {
        String prompt = configuredPrompt();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(prompt.getBytes(StandardCharsets.UTF_8));
            StringBuilder shortHash = new StringBuilder(12);
            for (int i = 0; i < 6; i++) shortHash.append(String.format("%02x", digest[i]));
            return PROMPT_SCHEMA_VERSION + ":" + shortHash;
        } catch (Exception ignored) {
            return PROMPT_SCHEMA_VERSION;
        }
    }

    public JudgeResult judge(List<QualityRuleEngine.Message> messages, String provider) throws Exception {
        String prompt = configuredPrompt();
        String promptVersion = promptVersion();
        StringBuilder transcript = new StringBuilder();
        for (QualityRuleEngine.Message message : messages) {
            if ("agent".equals(message.role())) continue;
            String speaker = "user".equals(message.role()) ? "客户" : "AI客服";
            transcript.append("[消息#").append(message.id()).append("] ")
                    .append(speaker).append("：").append(message.content()).append('\n');
            if (transcript.length() > 16000) break;
        }
        String request = prompt + "\n\n待复核对话：\n" + transcript;
        ModelGateway.QualityJudgeResponse response = callWithRateLimitRetry(request, provider);
        try {
            return parseResponse(response, promptVersion);
        } catch (JudgeResponseException firstFailure) {
            // 模型偶尔会包裹 Markdown、漏字段或输出非整数。仅对返回格式问题追加一次明确纠正重试，
            // 不切换管理员选择的模型，避免一次格式抖动直接把整条会话标成失败。
            log.warn("AI 裁判首次返回格式无效，使用同一模型纠正重试（provider={}, model={}）：{}",
                    provider, response.modelName(), firstFailure.getMessage());
            ModelGateway.QualityJudgeResponse corrected = callWithRateLimitRetry(
                    request + "\n\n重要：上一次输出未通过格式校验。请重新独立评分，只输出一个完整 JSON 对象；"
                            + "七个分数字段必须齐全且为整数，不要输出 Markdown 或额外说明。",
                    provider);
            try {
                return parseResponse(corrected, promptVersion);
            } catch (JudgeResponseException secondFailure) {
                throw new JudgeResponseException("invalid_response",
                        "评测模型连续两次未返回完整、可解析的 JSON 评分", secondFailure);
            }
        }
    }

    private String configuredPrompt() {
        String storeHours = config.get("store.businessHours", AiConfigCatalog.defaultValue("store.businessHours"));
        String agentHours = config.get("agent.workHours", AiConfigCatalog.defaultValue("agent.workHours"));
        return config.get("quality.judgePrompt", AiConfigCatalog.defaultValue("quality.judgePrompt"))
                .replace("{storeHours}", storeHours)
                .replace("{agentHours}", agentHours);
    }

    /** 429 指数退避：1s → 2s → 4s；重试期间始终使用管理员选定的同一模型。 */
    private ModelGateway.QualityJudgeResponse callWithRateLimitRetry(String request, String provider) throws Exception {
        ModelGateway.QualityJudgeResponse response;
        int attempt = 0;
        final int maxRetry = 3;
        final long backoffBaseMs = 1000;
        while (true) {
            try {
                response = modelGateway.qualityJudgeChat(request, provider);
                break;
            } catch (Exception e) {
                if (isRateLimited(e) && attempt < maxRetry) {
                    long waitMs = backoffBaseMs * (1L << attempt);
                    log.warn("AI 裁判调用被限流(429)，{}ms 后第 {} 次重试（provider={}）", waitMs, attempt + 1, provider);
                    try { Thread.sleep(waitMs); }
                    catch (InterruptedException ie) { Thread.currentThread().interrupt(); throw e; }
                    attempt++;
                } else {
                    throw e;
                }
            }
        }
        return response;
    }

    private JudgeResult parseResponse(ModelGateway.QualityJudgeResponse response, String promptVersion) throws JudgeResponseException {
        String output = response.content();
        if (output == null || output.isBlank()) {
            throw new JudgeResponseException("empty_response", "评测模型返回了空内容");
        }
        int start = output.indexOf('{'), end = output.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new JudgeResponseException("invalid_json", "评测模型没有返回 JSON 对象");
        }
        JsonNode node;
        try {
            node = JSON.readTree(output.substring(start, end + 1));
        } catch (Exception e) {
            throw new JudgeResponseException("invalid_json", "评测模型返回的 JSON 无法解析", e);
        }
        List<QualityRuleEngine.Issue> issues = new ArrayList<>();
        if (node.path("issues").isArray()) {
            for (JsonNode value : node.path("issues")) {
                if (value.isTextual()) {
                    String description = value.asText("").trim();
                    if (!description.isBlank()) issues.add(new QualityRuleEngine.Issue(
                            "ai_judge", "AI 复核", "overall", 0, description, "AI 裁判复核"));
                    continue;
                }
                if (!value.isObject()) continue;
                String description = value.path("problem").asText(
                        value.path("description").asText("")).trim();
                if (description.isBlank()) continue;
                String dimension = normalizeDimension(value.path("dimension").asText("overall"));
                String evidence = value.path("evidence").asText("").trim();
                long messageId = value.path("messageId").asLong(0L);
                if (messageId > 0) evidence = "消息#" + messageId + (evidence.isBlank() ? "" : "：" + evidence);
                if (evidence.isBlank()) evidence = "AI 裁判未返回具体原文，请人工复核";
                issues.add(new QualityRuleEngine.Issue(
                        "ai_judge", "AI 复核", dimension, 0, description, evidence));
            }
        }
        List<String> suggestions = new ArrayList<>();
        if (node.path("suggestions").isArray()) {
            for (JsonNode value : node.path("suggestions")) {
                String suggestion = value.asText("").trim();
                if (!suggestion.isBlank()) suggestions.add(suggestion);
            }
        }
        String summary = node.path("summary").asText("").trim();
        if (!summary.isBlank()) suggestions.add(0, "AI 复核摘要：" + summary);
        QualityRuleEngine.Score score = new QualityRuleEngine.Score(
                score(node, "intent", 20), score(node, "accuracy", 20),
                score(node, "context", 15), score(node, "safety", 20),
                score(node, "naturalness", 10), score(node, "resolution", 10),
                score(node, "handoff", 5), List.copyOf(issues), List.copyOf(suggestions));
        return new JudgeResult(score, response.provider(), response.modelName(), promptVersion);
    }

    private String normalizeDimension(String value) {
        String dimension = value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
        return List.of("intent", "accuracy", "context", "safety", "naturalness", "resolution", "handoff")
                .contains(dimension) ? dimension : "overall";
    }

    private int score(JsonNode node, String key, int max) throws JudgeResponseException {
        if (!node.has(key) || !node.path(key).canConvertToInt())
            throw new JudgeResponseException("missing_score", "评测模型缺少整数评分字段：" + key);
        return Math.max(0, Math.min(max, node.path(key).asInt()));
    }

    /** 判断异常是否由限流(429)引起：沿 cause 链检查关键字，兼容各家模型返回的不同措辞。 */
    private boolean isRateLimited(Throwable t) {
        Throwable cur = t;
        int depth = 0;
        while (cur != null && depth++ < 6) {
            String msg = cur.getMessage();
            if (msg != null) {
                String m = msg.toLowerCase();
                if (m.contains("429") || m.contains("too many requests")
                        || m.contains("rate limit") || m.contains("ratelimit")
                        || m.contains("请求过于频繁") || m.contains("请求频率")
                        || m.contains("请求次数过多") || m.contains("限流")) {
                    return true;
                }
            }
            cur = cur.getCause();
        }
        return false;
    }

    public record JudgeResult(QualityRuleEngine.Score score, String provider, String modelName,
                              String promptVersion) {
        /** 保留三参构造器，兼容现有测试和内部调用。 */
        public JudgeResult(QualityRuleEngine.Score score, String provider, String modelName) {
            this(score, provider, modelName, PROMPT_SCHEMA_VERSION);
        }
    }

    /** 可安全持久化和展示的返回格式错误；不包含模型原始输出，避免泄露对话内容。 */
    public static class JudgeResponseException extends Exception {
        private final String code;

        public JudgeResponseException(String code, String message) {
            super(message);
            this.code = code;
        }

        public JudgeResponseException(String code, String message, Throwable cause) {
            super(message, cause);
            this.code = code;
        }

        public String code() { return code; }
    }
}
